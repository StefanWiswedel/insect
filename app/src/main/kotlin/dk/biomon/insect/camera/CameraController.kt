package dk.biomon.insect.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import dk.biomon.insect.AppSettings
import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlin.math.abs

/** Identifies a still request so the JPEG that comes back can be named. */
data class StillRequest(
    val eventId: Long,
    val sequence: Int,
    val mode: String,
    val requestedAtMillis: Long,
    val blobsFullRes: List<dk.biomon.insect.core.blob.Blob>,
)

/**
 * The Camera2 session: two streams, one configuration, never reconfigured while
 * capturing.
 *
 * Camera2 rather than CameraX is a requirement, not a preference -- manual
 * focus, AWB lock and OIS control are exactly what CameraX abstracts away, and
 * all three are load-bearing here.
 *
 * The analysis stream (640x480 YUV, continuous) and the capture stream
 * (full-resolution JPEG, idle until triggered) belong to the **same** configured
 * session, so the capture surface is already allocated when a trigger fires.
 * Configuring a session on demand would add hundreds of milliseconds to a
 * latency budget that is already 200-300ms. The full-resolution stream is not
 * run continuously into a ring buffer: that would remove the latency but keep
 * the sensor reading out at 12MP all day, which is most of the power saving this
 * design exists to achieve.
 */
class CameraController(
    private val context: Context,
    private val settings: AppSettings,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /**
         * An analysis image, on the analysis thread. The implementation must not
         * close it and must not retain it past the call -- see
         * docs/analysis-stream-contract.md.
         */
        fun onAnalysisImage(image: Image, frameIndexHint: Long)

        /** A JPEG and the still request it answers, on the capture thread. */
        fun onJpeg(jpeg: ByteArray, request: StillRequest?)

        fun onExposureChange(event: ExposureEvent, frameIndex: Long)

        /** Any camera-side failure. [recovered] false means the session is down. */
        fun onCameraError(message: String, recovered: Boolean)

        /** Called after a rebuild, because the scene may have moved meanwhile. */
        fun onSessionRestarted()

        fun onSessionConfigured(info: SessionGeometry)
    }

    data class SessionGeometry(
        val cameraId: String,
        val analysisSize: Size,
        val captureSize: Size,
        val parameterSummary: String,
        val focusDiopters: Float,
    )

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val analysisThread = HandlerThread("insect-analysis").apply { start() }
    private val analysisHandler = Handler(analysisThread.looper)
    private val captureThread = HandlerThread("insect-capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val captureExecutor = Executor { captureHandler.post(it) }

    // Written on the camera handler, read from the analysis thread when a
    // trigger fires, so none of these may be cached in a register.
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var analysisReader: ImageReader? = null
    @Volatile private var jpegReader: ImageReader? = null
    @Volatile private var parameters: CaptureParameters? = null
    private val exposureWatcher = ExposureWatcher()

    @Volatile private var previewSurface: Surface? = null
    @Volatile private var running = false
    @Volatile private var closing = false
    @Volatile private var analysisIntervalNs = 1_000_000_000L / settings.capture.analysisFps

    /** Session-start AWB convergence window; locked once it expires. */
    private var awbLockedAt = 0L
    private var awbLocked = false

    private var resultFrameIndex = 0L
    private var lastAnalysisTimestampNs = 0L
    private var restartAttempt = 0

    /** Still requests in flight, matched to JPEGs in arrival order. */
    private val pendingStills = ArrayDeque<StillRequest>()

    private var geometry: SessionGeometry? = null

    /** Analysis frames the pipeline never saw, aggregated for the manifest. */
    @Volatile var droppedFrames: Long = 0L
        private set

    fun start() {
        closing = false
        openCamera()
    }

    fun stop() {
        closing = true
        running = false
        closeSession()
        analysisThread.quitSafely()
        captureThread.quitSafely()
    }

    /** Thermal backoff changes this mid-session; it costs nothing to honour. */
    fun setAnalysisFps(fps: Int) {
        analysisIntervalNs = 1_000_000_000L / fps.coerceIn(1, 30)
    }

    /**
     * Attach or detach the preview. Changing it rebuilds the session, which is
     * why the deployment runs without one: the screen is off and nothing should
     * be reconfiguring the camera nine hours in.
     */
    fun setPreviewSurface(surface: Surface?) {
        if (previewSurface === surface) return
        previewSurface = surface
        if (running) {
            captureHandler.post { rebuildSession("preview surface changed") }
        }
    }

    /** Ask for one full-resolution frame. Cheap: the surface is already there. */
    fun requestStill(request: StillRequest) {
        val activeSession = session ?: return
        val activeDevice = device ?: return
        val reader = jpegReader ?: return
        captureHandler.post {
            try {
                val builder = activeDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE
                )
                builder.addTarget(reader.surface)
                parameters?.apply(builder, lockAwb = awbLocked)
                builder.set(CaptureRequest.JPEG_ORIENTATION, 0)
                synchronized(pendingStills) { pendingStills.addLast(request) }
                activeSession.capture(builder.build(), stillCallback, captureHandler)
            } catch (t: Throwable) {
                synchronized(pendingStills) { pendingStills.remove(request) }
                callbacks.onCameraError(
                    "still capture failed: ${t.javaClass.simpleName}: ${t.message}",
                    recovered = true,
                )
            }
        }
    }

    // --- camera lifecycle ----------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            val cameraId = selectCamera()
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: throw IllegalStateException("no stream configuration map")

            val analysisSize = closestTo(
                map.getOutputSizes(ImageFormat.YUV_420_888), ANALYSIS_TARGET
            )
            val captureSize = largest(map.getOutputSizes(ImageFormat.JPEG))

            analysisReader = ImageReader.newInstance(
                analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, ANALYSIS_BUFFERS
            ).apply { setOnImageAvailableListener(analysisListener, analysisHandler) }

            jpegReader = ImageReader.newInstance(
                captureSize.width, captureSize.height, ImageFormat.JPEG, JPEG_BUFFERS
            ).apply { setOnImageAvailableListener(jpegListener, captureHandler) }

            parameters = CaptureParameters(characteristics, settings)
            geometry = SessionGeometry(
                cameraId = cameraId,
                analysisSize = analysisSize,
                captureSize = captureSize,
                parameterSummary = parameters!!.describe(),
                focusDiopters = parameters!!.appliedFocusDiopters,
            )

            manager.openCamera(cameraId, deviceCallback, captureHandler)
        } catch (t: Throwable) {
            scheduleRestart("open failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            configureSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            device = null
            scheduleRestart("camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            device = null
            scheduleRestart("camera error $error")
        }
    }

    private fun configureSession(camera: CameraDevice) {
        try {
            val outputs = ArrayList<OutputConfiguration>(3)
            analysisReader?.surface?.let { outputs += OutputConfiguration(it) }
            jpegReader?.surface?.let { outputs += OutputConfiguration(it) }
            previewSurface?.let { outputs += OutputConfiguration(it) }

            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    captureExecutor,
                    sessionCallback,
                )
            )
        } catch (t: Throwable) {
            scheduleRestart("session configuration failed: ${t.message}")
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(configured: CameraCaptureSession) {
            session = configured
            awbLockedAt = System.currentTimeMillis() + AWB_CONVERGE_MILLIS
            awbLocked = false
            startRepeating()
            restartAttempt = 0
            running = true
            geometry?.let(callbacks::onSessionConfigured)
        }

        override fun onConfigureFailed(configured: CameraCaptureSession) {
            scheduleRestart("session configure failed")
        }
    }

    private fun startRepeating() {
        val activeSession = session ?: return
        val activeDevice = device ?: return
        try {
            val builder = activeDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            analysisReader?.surface?.let(builder::addTarget)
            previewSurface?.let(builder::addTarget)
            parameters?.apply(builder, lockAwb = awbLocked)
            activeSession.setRepeatingRequest(builder.build(), repeatingCallback, captureHandler)
        } catch (t: Throwable) {
            scheduleRestart("repeating request failed: ${t.message}")
        }
    }

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val now = System.currentTimeMillis()
            resultFrameIndex++

            // Let AWB converge before locking it: locking on the first frames
            // freezes whatever the camera guessed under a canopy, which is
            // usually wrong and is then wrong for nine hours.
            if (!awbLocked && now >= awbLockedAt) {
                awbLocked = true
                startRepeating()
            }

            exposureWatcher.onResult(result, now)?.let {
                callbacks.onExposureChange(it, resultFrameIndex)
            }
        }
    }

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            // Drop the oldest pending entry: the JPEG for it is never coming, and
            // leaving it queued would misname every subsequent frame.
            synchronized(pendingStills) { pendingStills.pollFirst() }
            callbacks.onCameraError("still capture failed, reason ${failure.reason}", true)
        }
    }

    // --- image delivery ------------------------------------------------------

    private val analysisListener = ImageReader.OnImageAvailableListener { reader ->
        // acquireLatestImage rather than acquireNext: if the pipeline overran,
        // the newest frame is the useful one and the stale ones are discarded by
        // the reader. What that costs is counted below rather than hidden.
        val image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            null
        } ?: return@OnImageAvailableListener

        try {
            val timestampNs = image.timestamp
            val interval = analysisIntervalNs
            if (lastAnalysisTimestampNs != 0L) {
                val gap = timestampNs - lastAnalysisTimestampNs
                if (gap < interval - interval / 10) {
                    // Arrived faster than the analysis rate asks for. Not a drop:
                    // the sensor runs at its own cadence and this is the rate
                    // control the design specifies.
                    return@OnImageAvailableListener
                }
                // A gap of more than one interval means frames the pipeline never
                // saw. Non-negotiable #3: counted, and surfaced by the caller.
                val missed = (gap / interval) - 1
                if (missed > 0) droppedFrames += missed
            }
            lastAnalysisTimestampNs = timestampNs
            callbacks.onAnalysisImage(image, resultFrameIndex)
        } catch (t: Throwable) {
            callbacks.onCameraError("analysis frame failed: ${t.message}", recovered = true)
        } finally {
            try {
                image.close()
            } catch (ignored: Throwable) {
                // Closing a already-invalid image is not worth reporting.
            }
        }
    }

    private val jpegListener = ImageReader.OnImageAvailableListener { reader ->
        val image = try {
            reader.acquireNextImage()
        } catch (t: Throwable) {
            null
        } ?: return@OnImageAvailableListener
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            // JPEGs come back in request order, so the oldest pending request is
            // this one. The tag would be more direct, but it only reaches
            // CaptureResult, which can arrive after the image.
            val request = synchronized(pendingStills) { pendingStills.pollFirst() }
            callbacks.onJpeg(bytes, request)
        } catch (t: Throwable) {
            callbacks.onCameraError("jpeg read failed: ${t.message}", recovered = true)
        } finally {
            try {
                image.close()
            } catch (ignored: Throwable) {
                // As above.
            }
        }
    }

    // --- recovery ------------------------------------------------------------

    /**
     * Rebuild rather than give up. A camera error must never end the session
     * (non-negotiable #4): the deployment is unattended and an evening of frames
     * is worth more than a clean failure. Backoff is capped so a permanently
     * broken camera does not spin the CPU for nine hours.
     */
    private fun scheduleRestart(reason: String) {
        if (closing) return
        running = false
        val attempt = ++restartAttempt
        val delay = (RESTART_BASE_MILLIS shl minOf(attempt - 1, 5)).coerceAtMost(RESTART_MAX_MILLIS)
        callbacks.onCameraError("$reason; rebuilding in ${delay}ms (attempt $attempt)", true)
        closeSession()
        captureHandler.postDelayed({
            if (!closing) {
                openCamera()
                callbacks.onSessionRestarted()
            }
        }, delay)
    }

    private fun rebuildSession(reason: String) {
        if (closing) return
        callbacks.onCameraError("rebuilding session: $reason", recovered = true)
        val camera = device
        try {
            session?.stopRepeating()
            session?.close()
        } catch (ignored: Throwable) {
            // A session that will not stop is a session about to be replaced.
        }
        session = null
        if (camera != null) configureSession(camera) else openCamera()
    }

    private fun closeSession() {
        try {
            session?.stopRepeating()
        } catch (ignored: Throwable) {
            // Already gone.
        }
        try {
            session?.close()
        } catch (ignored: Throwable) {
        }
        session = null
        try {
            device?.close()
        } catch (ignored: Throwable) {
        }
        device = null
        try {
            analysisReader?.close()
            jpegReader?.close()
        } catch (ignored: Throwable) {
        }
        analysisReader = null
        jpegReader = null
        synchronized(pendingStills) { pendingStills.clear() }
        lastAnalysisTimestampNs = 0L
    }

    private fun selectCamera(): String {
        val ids = manager.cameraIdList
        // The main back camera: the widest-angle back-facing one is usually the
        // ultrawide, which is not what the rig is aimed with, so prefer the one
        // whose focal length is the longest among back cameras.
        var best: String? = null
        var bestFocal = -1f
        for (id in ids) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                continue
            }
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.maxOrNull() ?: 0f
            if (best == null || focal > bestFocal) {
                best = id
                bestFocal = focal
            }
        }
        return best ?: ids.firstOrNull() ?: throw IllegalStateException("no cameras")
    }

    private fun closestTo(sizes: Array<Size>?, target: Size): Size {
        if (sizes.isNullOrEmpty()) return target
        return sizes.minByOrNull {
            abs(it.width - target.width) + abs(it.height - target.height)
        } ?: target
    }

    private fun largest(sizes: Array<Size>?): Size =
        sizes?.maxByOrNull { it.width.toLong() * it.height } ?: Size(1920, 1080)

    private companion object {
        val ANALYSIS_TARGET = Size(640, 480)
        const val ANALYSIS_BUFFERS = 3
        const val JPEG_BUFFERS = 3
        const val AWB_CONVERGE_MILLIS = 3_000L
        const val RESTART_BASE_MILLIS = 1_000L
        const val RESTART_MAX_MILLIS = 30_000L
    }
}
