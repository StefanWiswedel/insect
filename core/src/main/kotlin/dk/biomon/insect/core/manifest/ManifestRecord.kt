package dk.biomon.insect.core.manifest

import dk.biomon.insect.core.blob.Blob

/**
 * Minimal JSON emitter.
 *
 * Hand-rolled rather than pulled from a library so that :core stays free of
 * dependencies and the manifest format is byte-identical on the JVM (where it is
 * tested) and on the phone (where it is written).
 */
internal object Json {
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    fun obj(vararg fields: Pair<String, Any?>): String =
        fields.filter { it.second != null }.joinToString(
            prefix = "{", postfix = "}", separator = ","
        ) { (k, v) -> "\"${escape(k)}\":${value(v)}" }

    fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"${escape(v)}\""
        is Boolean, is Int, is Long, is Short, is Byte -> v.toString()
        is Float -> if (v.isFinite()) trim(v.toDouble()) else "null"
        is Double -> if (v.isFinite()) trim(v) else "null"
        is Enum<*> -> "\"${v.name.lowercase()}\""
        is Iterable<*> -> v.joinToString(prefix = "[", postfix = "]", separator = ",") { value(it) }
        else -> "\"${escape(v.toString())}\""
    }

    /** Keep the manifest readable: no 17-digit float noise. */
    private fun trim(d: Double): String {
        val s = String.format(java.util.Locale.US, "%.4f", d)
        return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }
}

/**
 * One line of the session manifest.
 *
 * The manifest is append-only JSON Lines, flushed as records occur and never
 * buffered to session end (DESIGN.md 4). A power cut mid-deployment costs at
 * most the line being written.
 */
sealed interface ManifestRecord {
    val type: String
    val atMillis: Long
    fun toJsonLine(): String
}

private fun line(type: String, atMillis: Long, vararg extra: Pair<String, Any?>): String =
    Json.obj("type" to type, "t" to atMillis, *extra)

data class SessionStart(
    override val atMillis: Long,
    val sessionId: String,
    val appVersion: String,
    val device: String,
    val androidRelease: String,
    val cameraId: String,
    val lensDescription: String,
    val focusDistanceDiopters: Float,
    val analysisWidth: Int,
    val analysisHeight: Int,
    val captureWidth: Int,
    val captureHeight: Int,
    val jpegQuality: Int,
    val configJson: String,
) : ManifestRecord {
    override val type: String get() = "session_start"
    override fun toJsonLine(): String = line(
        type, atMillis,
        "session_id" to sessionId,
        "app_version" to appVersion,
        "device" to device,
        "android" to androidRelease,
        "camera_id" to cameraId,
        "lens" to lensDescription,
        "focus_diopters" to focusDistanceDiopters,
        "analysis" to listOf(analysisWidth, analysisHeight),
        "capture" to listOf(captureWidth, captureHeight),
        "jpeg_quality" to jpegQuality,
        "config" to configJson,
    )
}

/**
 * A power sample. Non-negotiable #1: there is no bench test, so the deployment
 * is the measurement, at a fixed 60s interval for the whole session.
 */
data class PowerSample(
    override val atMillis: Long,
    val batteryPercent: Int,
    /** `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`, microamps. Negative = discharging. */
    val currentMicroAmps: Int,
    val temperatureCelsius: Float,
    val voltageMillivolts: Int,
    val charging: Boolean,
    val freeBytes: Long,
    /** Derived draw in watts, when voltage and current are both available. */
    val watts: Float?,
    /**
     * The platform's own thermal throttling state, independent of battery
     * temperature. Battery temperature is only refreshed when the system
     * broadcasts a battery change, which on a phone held at a steady charge can
     * be never -- the first real run logged an identical 28.1C sixteen times.
     * This one moves.
     */
    val thermalStatus: String? = null,
    /**
     * How old the battery reading is, in milliseconds, when that can be
     * determined. Large and growing means the platform is not refreshing it and
     * the temperature above should not be trusted.
     */
    val batteryAgeMillis: Long? = null,
) : ManifestRecord {
    override val type: String get() = "power"
    override fun toJsonLine(): String = line(
        type, atMillis,
        "battery_pct" to batteryPercent,
        "current_ua" to currentMicroAmps,
        "temp_c" to temperatureCelsius,
        "voltage_mv" to voltageMillivolts,
        "charging" to charging,
        "free_bytes" to freeBytes,
        "watts" to watts,
        "thermal_status" to thermalStatus,
        "battery_age_ms" to batteryAgeMillis,
    )
}

/**
 * Auto-exposure moved. Logged for every change so that background-model
 * discontinuities can be correlated with exposure afterwards (DESIGN.md 3.4).
 */
data class ExposureChange(
    override val atMillis: Long,
    val exposureTimeNs: Long,
    val iso: Int,
    val aeState: String,
    val frameIndex: Long,
) : ManifestRecord {
    override val type: String get() = "exposure"
    override fun toJsonLine(): String = line(
        type, atMillis,
        "exposure_ns" to exposureTimeNs,
        "iso" to iso,
        "ae_state" to aeState,
        "frame" to frameIndex,
    )
}

/**
 * The locked focus distance was changed while the session was running.
 *
 * Focus is aimed per deployment from the main screen, so this happens at the
 * start of most sessions. It matters downstream for the same reason an exposure
 * change does: refocusing shifts sharpness across the whole frame, which the
 * background model reads as motion everywhere at once. A run of spurious events
 * right after one of these records is explained, not mysterious.
 */
data class FocusChanged(
    override val atMillis: Long,
    val fromDiopters: Float,
    val toDiopters: Float,
) : ManifestRecord {
    override val type: String get() = "focus"
    override fun toJsonLine(): String = line(
        type, atMillis,
        "from_diopters" to fromDiopters,
        "to_diopters" to toDiopters,
        "to_cm" to if (toDiopters > 0f) 100f / toDiopters else null,
    )
}

/**
 * The background model started converging and the trigger is held off.
 *
 * Recorded so the opening gap in every session is explicit. Without it, the
 * first half-minute of a deployment looks identical to a dead sensor, and the
 * alternative -- arming immediately -- produces a large spurious event while the
 * EMA is still settling, which is exactly what the first real run did.
 */
data class WarmupStarted(
    override val atMillis: Long,
    val seconds: Int,
) : ManifestRecord {
    override val type: String get() = "warmup_start"
    override fun toJsonLine(): String = line(type, atMillis, "seconds" to seconds)
}

/** The model converged; the trigger is live from here. */
data class WarmupEnded(
    override val atMillis: Long,
    val frames: Long,
    val durationMillis: Long,
) : ManifestRecord {
    override val type: String get() = "warmup_end"
    override fun toJsonLine(): String = line(
        type, atMillis, "frames" to frames, "duration_ms" to durationMillis
    )
}

data class EventStarted(
    override val atMillis: Long,
    val eventId: Long,
) : ManifestRecord {
    override val type: String get() = "event_start"
    override fun toJsonLine(): String = line(type, atMillis, "event" to eventId)
}

data class EventEnded(
    override val atMillis: Long,
    val eventId: Long,
    val frameCount: Int,
    val reason: String,
    val durationMillis: Long,
) : ManifestRecord {
    override val type: String get() = "event_end"
    override fun toJsonLine(): String = line(
        type, atMillis,
        "event" to eventId,
        "frames" to frameCount,
        "reason" to reason,
        "duration_ms" to durationMillis,
    )
}

/** One written frame, with the boxes that justified it, in full-resolution coords. */
data class FrameWritten(
    override val atMillis: Long,
    val eventId: Long,
    val sequence: Int,
    val filename: String,
    val mode: String,
    val bytes: Long,
    val blobs: List<Blob>,
) : ManifestRecord {
    override val type: String get() = "frame"
    override fun toJsonLine(): String = line(
        type, atMillis,
        "event" to eventId,
        "seq" to sequence,
        "file" to filename,
        "mode" to mode,
        "bytes" to bytes,
        "boxes" to blobs.map { listOf(it.left, it.top, it.right, it.bottom, it.areaPx) },
    )
}

/**
 * The capture rate changed inside an event. Without this, a 1fps stationary
 * stretch is indistinguishable downstream from dropped frames.
 */
data class RateChanged(
    override val atMillis: Long,
    val eventId: Long,
    val from: String,
    val to: String,
    val effectiveFps: Float,
) : ManifestRecord {
    override val type: String get() = "rate_change"
    override fun toJsonLine(): String = line(
        type, atMillis,
        "event" to eventId,
        "from" to from,
        "to" to to,
        "fps" to effectiveFps,
    )
}

/**
 * Any degradation at all: thermal backoff, disk pressure, dropped analysis
 * frames, forced background refresh. Non-negotiable #3 -- a session that quietly
 * captured nothing for four hours is worse than one that failed loudly at hour
 * one.
 */
data class Degradation(
    override val atMillis: Long,
    val kind: String,
    val detail: String,
) : ManifestRecord {
    override val type: String get() = "degradation"
    override fun toJsonLine(): String =
        line(type, atMillis, "kind" to kind, "detail" to detail)
}

/** The background model force-refreshed pixels it had been holding as foreground. */
data class ForcedRefresh(
    override val atMillis: Long,
    val pixels: Int,
    val workPixels: Int,
    val frameIndex: Long,
) : ManifestRecord {
    override val type: String get() = "forced_refresh"
    override fun toJsonLine(): String = line(
        type, atMillis,
        "pixels" to pixels,
        "of" to workPixels,
        "frame" to frameIndex,
    )
}

/**
 * The scene's lighting changed rather than something moving in it: a component
 * larger than `illuminationAreaFraction` of the frame.
 *
 * Written *instead of* a detection, not as well as one -- capture is suppressed
 * for the frame. It is also written instead of the [ForcedRefresh] that the
 * whole-model re-baseline supersedes, so one stale-model event produces one
 * record, never two.
 *
 * These are counted rather than discarded because outdoors they are the weather:
 * cloud shadow crossing the board will raise them repeatedly, and their rate is
 * how the laptop side tells a cloudy afternoon's thin detection record from a
 * broken rig's.
 */
data class IlluminationEvent(
    override val atMillis: Long,
    /** Largest oversized component, in downsampled working pixels. */
    val areaPx: Int,
    /** Working pixels in the whole frame, so [areaPx] can be read as a fraction. */
    val workPixels: Int,
    /** Pixels the background model re-baselined in response. */
    val rebaselinedPixels: Int,
    val frameIndex: Long,
    /** `size` when the certain gate alone decided it, otherwise `corroborated`. */
    val basis: String = "size",
    val edgesTouched: Int = 0,
    val oppositeEdges: Boolean = false,
    val fillRatio: Float = 0f,
    val blobCount: Int = 0,
    val spreadFraction: Float = 0f,
    /** How many of the three corroborating signals fired. */
    val signals: Int = 0,
) : ManifestRecord {
    override val type: String get() = "illumination_event"
    override fun toJsonLine(): String = line(
        type, atMillis,
        "area_px" to areaPx,
        "of" to workPixels,
        "area_fraction" to (if (workPixels == 0) 0f else areaPx.toFloat() / workPixels),
        "rebaselined_px" to rebaselinedPixels,
        "frame" to frameIndex,
        // Why, not only what: a threshold nobody can audit is a threshold
        // nobody can tune.
        "basis" to basis,
        "edges" to edgesTouched,
        "opposite_edges" to oppositeEdges,
        "fill_ratio" to fillRatio,
        "blobs" to blobCount,
        "spread_fraction" to spreadFraction,
        "signals" to signals,
    )
}

/** A recoverable failure. The session continues; the record says it happened. */
data class ErrorRecord(
    override val atMillis: Long,
    val component: String,
    val message: String,
    val recovered: Boolean,
) : ManifestRecord {
    override val type: String get() = "error"
    override fun toJsonLine(): String = line(
        type, atMillis,
        "component" to component,
        "message" to message,
        "recovered" to recovered,
    )
}

/**
 * Why the session ended, written whenever the app gets the chance. Its absence
 * is itself informative: it means the phone died or was killed outright.
 */
data class SessionEnd(
    override val atMillis: Long,
    val reason: String,
    val events: Long,
    val frames: Long,
    val bytesWritten: Long,
    val durationMillis: Long,
) : ManifestRecord {
    override val type: String get() = "session_end"
    override fun toJsonLine(): String = line(
        type, atMillis,
        "reason" to reason,
        "events" to events,
        "frames" to frames,
        "bytes" to bytesWritten,
        "duration_ms" to durationMillis,
    )
}
