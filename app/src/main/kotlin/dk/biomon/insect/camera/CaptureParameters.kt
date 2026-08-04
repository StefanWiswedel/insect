package dk.biomon.insect.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import dk.biomon.insect.AppSettings
import kotlin.math.abs

/**
 * The locked capture parameters (DESIGN.md 3.4).
 *
 * Autofocus hunting is a background-model catastrophe and a battery drain: every
 * refocus shifts the whole frame's sharpness, which the EMA background reads as
 * motion everywhere at once. So focus is fixed at the measured working distance,
 * white balance is locked, and OIS is off -- a stabiliser that nudges the sensor
 * to cancel handshake is, on a stand, a machine for injecting sub-pixel motion
 * into a static scene.
 *
 * Auto-exposure is the exception and stays on, because the light changes over
 * nine hours and a fixed exposure would be blown at noon or black at five. The
 * cost is that every AE adjustment is a background-model discontinuity, so every
 * one is logged and correlated afterwards.
 */
class CaptureParameters(
    private val characteristics: CameraCharacteristics,
    private val settings: AppSettings,
) {
    /**
     * The focus distance actually applied, in dioptres. May differ from the
     * requested value: a lens cannot focus closer than its minimum, and asking
     * for it silently yields something else.
     */
    val appliedFocusDiopters: Float = clampFocus(settings.focusDistanceDiopters)

    private val supportsFocusDistance: Boolean =
        (characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f

    private val supportsOisOff: Boolean =
        characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF) ?: false

    /** Describes what was and was not honoured, for the session_start record. */
    fun describe(): String = buildString {
        append("focus=")
        if (supportsFocusDistance) append(appliedFocusDiopters).append("D")
        else append("fixed(no manual focus control)")
        append(" ois=").append(if (supportsOisOff) "off" else "unsupported")
        append(" awb=locked ae=on q").append(settings.capture.jpegQuality)
    }

    /**
     * Apply the locked parameters.
     *
     * @param lockAwb false during the brief convergence window at session start,
     *   true thereafter. Locking white balance before it has settled freezes
     *   whatever the camera guessed in its first few frames, which outdoors under
     *   a canopy is usually wrong.
     */
    fun apply(builder: CaptureRequest.Builder, lockAwb: Boolean) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        if (supportsFocusDistance) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, appliedFocusDiopters)
        } else {
            // A fixed-focus lens is already what we want; asking for OFF on a
            // device with no manual control can produce an unconfigured lens.
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        }

        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)

        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, lockAwb)

        if (supportsOisOff) {
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            )
        }
        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )

        builder.set(CaptureRequest.JPEG_QUALITY, settings.capture.jpegQuality.toByte())
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
        builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
    }

    private fun clampFocus(requested: Float): Float {
        val minDistance =
            characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        // LENS_INFO_MINIMUM_FOCUS_DISTANCE is the *largest* dioptre value the
        // lens supports -- closest focus. 0 means fixed focus.
        if (minDistance <= 0f) return 0f
        return requested.coerceIn(0f, minDistance)
    }
}

/**
 * Watches capture results for exposure movement.
 *
 * Emitting a record per frame would put 160,000 lines in a day's manifest and
 * drown everything else, so a change is only reported when it is big enough to
 * matter to a background model and not more often than [minIntervalMillis].
 */
class ExposureWatcher(
    private val minIntervalMillis: Long = 5_000,
    private val relativeChange: Float = 0.15f,
) {
    private var lastExposureNs = 0L
    private var lastIso = 0
    private var lastReportedAt = 0L

    /** Returns a triple to log, or null when nothing worth recording changed. */
    fun onResult(result: CaptureResult, nowMillis: Long): ExposureEvent? {
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)

        val first = lastExposureNs == 0L
        val exposureMoved = !first &&
            abs(exposure - lastExposureNs).toFloat() / lastExposureNs > relativeChange
        val isoMoved = !first && lastIso > 0 &&
            abs(iso - lastIso).toFloat() / lastIso > relativeChange

        if (!first && !exposureMoved && !isoMoved) return null
        if (!first && nowMillis - lastReportedAt < minIntervalMillis) return null

        lastExposureNs = exposure
        lastIso = iso
        lastReportedAt = nowMillis
        return ExposureEvent(exposure, iso, aeStateName(aeState))
    }

    private fun aeStateName(state: Int?): String = when (state) {
        CaptureResult.CONTROL_AE_STATE_INACTIVE -> "inactive"
        CaptureResult.CONTROL_AE_STATE_SEARCHING -> "searching"
        CaptureResult.CONTROL_AE_STATE_CONVERGED -> "converged"
        CaptureResult.CONTROL_AE_STATE_LOCKED -> "locked"
        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "flash_required"
        CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "precapture"
        else -> "unknown"
    }
}

data class ExposureEvent(val exposureTimeNs: Long, val iso: Int, val aeState: String)
