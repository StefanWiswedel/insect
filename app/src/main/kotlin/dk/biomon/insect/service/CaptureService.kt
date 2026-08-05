package dk.biomon.insect.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.Image
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import dk.biomon.insect.AppSettings
import dk.biomon.insect.CaptureBus
import dk.biomon.insect.InsectApp
import dk.biomon.insect.R
import dk.biomon.insect.SessionRecorder
import dk.biomon.insect.camera.CameraController
import dk.biomon.insect.camera.ExposureEvent
import dk.biomon.insect.camera.StillRequest
import dk.biomon.insect.core.event.EventEndReason
import dk.biomon.insect.core.manifest.Degradation
import dk.biomon.insect.core.manifest.ErrorRecord
import dk.biomon.insect.core.manifest.ExposureChange
import dk.biomon.insect.core.manifest.FocusChanged
import dk.biomon.insect.core.manifest.SessionStart
import dk.biomon.insect.core.policy.GuardEvaluator
import dk.biomon.insect.core.policy.StopReason
import dk.biomon.insect.pipeline.AnalysisPipeline
import dk.biomon.insect.power.PowerLogger
import dk.biomon.insect.store.SessionStore
import dk.biomon.insect.ui.MainActivity
import dk.biomon.insect.ui.SettingsStore

/**
 * The deployment.
 *
 * A foreground service holding a partial wake lock, running with the screen off
 * for nine hours in a box behind a stick pile. Everything about it is shaped by
 * two facts: nobody is watching, and it can be killed at any instant without
 * warning.
 *
 * So: it never binds to the UI (the Activity is incidental and may not exist),
 * it never ends a session because something failed (a camera error rebuilds, a
 * storage error is recorded), and it never stops without writing down why. A
 * session that records why it ended is data; a session that simply stops is an
 * evening of forensics.
 */
class CaptureService : Service() {

    private lateinit var settings: AppSettings
    private lateinit var recorder: SessionRecorder
    private lateinit var camera: CameraController
    private lateinit var pipeline: AnalysisPipeline
    private lateinit var powerLogger: PowerLogger
    private lateinit var guards: GuardEvaluator

    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false
    private var stopping = false

    /**
     * Set once the camera exists. The static hooks are called from the UI thread
     * at arbitrary times, including before the session has built anything, and
     * touching a lateinit from there would throw.
     */
    @Volatile
    private var cameraReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown("user_stopped")
                return START_NOT_STICKY
            }
        }
        if (!started) startSession()
        // START_STICKY: if the OS kills us for memory, come back and keep going.
        // The session resumes into a new session directory rather than pretending
        // to be the old one -- see the note in startSession.
        return START_STICKY
    }

    private fun startSession() {
        started = true
        settings = SettingsStore.get(applicationContext).settings.value

        // The typed overload is required from API 34 for a camera foreground
        // service, and the manifest declares the matching type. The camera
        // permission must already be granted or the platform refuses the start,
        // which is why the UI asks for it before offering the button.
        startForeground(
            NOTIFICATION_ID,
            buildNotification("starting"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
        acquireWakeLock()

        recorder = SessionStore.open(applicationContext, settings)
        powerLogger = PowerLogger(applicationContext, recorder)
        guards = GuardEvaluator(settings.guards, settings.capture, settings.trigger)

        camera = CameraController(applicationContext, settings, cameraCallbacks)
        pipeline = AnalysisPipeline(settings, recorder, camera)
        cameraReady = true

        CaptureBus.publish {
            it.copy(
                running = true,
                sessionId = recorder.session.sessionId,
                analysisFps = settings.capture.analysisFps,
                focusDistanceDiopters = settings.focusDistanceDiopters,
                // Where it actually landed, not where it was meant to.
                storagePath = recorder.session.directory.absolutePath,
                storageFallback = !SessionStore.hasSharedStorage(),
            )
        }

        camera.start()
        mainHandler.post(powerTick)
    }

    /**
     * Apply a focus change and record it.
     *
     * Refocusing shifts sharpness across the whole frame, which the background
     * model reads as motion everywhere at once, so the manifest has to say when
     * it happened -- otherwise the burst of spurious events that follows looks
     * like an unexplained swarm.
     */
    private fun applyFocus(diopters: Float) {
        val before = camera.appliedFocusDiopters()
        camera.setFocusDiopters(diopters)
        val after = camera.appliedFocusDiopters()
        if (after == before) return
        recorder.record(FocusChanged(System.currentTimeMillis(), before, after))
        CaptureBus.publish { it.copy(focusDistanceDiopters = after) }
    }

    private val cameraCallbacks = object : CameraController.Callbacks {
        override fun onAnalysisImage(image: Image, frameIndexHint: Long) {
            if (stopping) return
            pipeline.onImage(image, System.currentTimeMillis())
        }

        override fun onJpeg(jpeg: ByteArray, request: StillRequest?) {
            if (request == null) {
                // A JPEG with no matching request means the queue and the stream
                // have diverged, which would misname everything after it. Say so
                // rather than writing a frame under a guessed event.
                recorder.record(
                    ErrorRecord(
                        System.currentTimeMillis(),
                        "capture",
                        "JPEG arrived with no pending request; frame discarded",
                        recovered = true,
                    )
                )
                return
            }
            recorder.writeFrame(
                eventId = request.eventId,
                sequence = request.sequence,
                mode = request.mode,
                wallClockMillis = request.requestedAtMillis,
                jpeg = jpeg,
                blobs = request.blobsFullRes,
            )
            CaptureBus.publish { it.copy(stats = recorder.stats.value) }
        }

        override fun onExposureChange(event: ExposureEvent, frameIndex: Long) {
            recorder.record(
                ExposureChange(
                    atMillis = System.currentTimeMillis(),
                    exposureTimeNs = event.exposureTimeNs,
                    iso = event.iso,
                    aeState = event.aeState,
                    frameIndex = frameIndex,
                )
            )
        }

        override fun onCameraError(message: String, recovered: Boolean) {
            recorder.record(
                ErrorRecord(System.currentTimeMillis(), "camera", message, recovered)
            )
            CaptureBus.publish { it.copy(lastError = message) }
        }

        override fun onSessionRestarted() {
            pipeline.onCameraRestarted(System.currentTimeMillis())
        }

        override fun onSessionConfigured(info: CameraController.SessionGeometry) {
            pipeline.onGeometry(
                analysisW = info.analysisSize.width,
                captureW = info.captureSize.width,
                captureH = info.captureSize.height,
            )
            recorder.record(
                SessionStart(
                    atMillis = recorder.session.startedAtMillis,
                    sessionId = recorder.session.sessionId,
                    appVersion = appVersion(),
                    device = "${Build.MANUFACTURER} ${Build.MODEL}",
                    androidRelease = Build.VERSION.RELEASE,
                    cameraId = info.cameraId,
                    lensDescription = info.parameterSummary,
                    focusDistanceDiopters = info.focusDiopters,
                    analysisWidth = info.analysisSize.width,
                    analysisHeight = info.analysisSize.height,
                    captureWidth = info.captureSize.width,
                    captureHeight = info.captureSize.height,
                    jpegQuality = settings.capture.jpegQuality,
                    configJson = settings.trigger.toString(),
                )
            )
            CaptureBus.publish { it.copy(focusDistanceDiopters = info.focusDiopters) }
        }
    }

    /**
     * The 60s heartbeat: one power sample and one guard evaluation.
     *
     * Non-negotiable #1 fixes the sampling interval, and reusing the same tick for
     * the guards means a thermal or disk transition can never be more than a
     * minute stale -- which is the right granularity for both, and one timer
     * rather than two waking the CPU.
     */
    private val powerTick = object : Runnable {
        override fun run() {
            if (stopping) return
            val now = System.currentTimeMillis()
            val reading = try {
                powerLogger.sample(now)
            } catch (t: Throwable) {
                recorder.record(
                    ErrorRecord(now, "power", "sample failed: ${t.message}", recovered = true)
                )
                null
            }

            if (reading != null) {
                val state = guards.evaluate(
                    freeBytes = recorder.freeBytes(),
                    batteryPercent = reading.batteryPercent,
                    temperatureCelsius = if (reading.temperatureCelsius.isNaN()) 0f
                    else reading.temperatureCelsius,
                )

                for (transition in guards.lastTransitions) {
                    recorder.record(Degradation(now, "guard", transition))
                }

                pipeline.maxCaptureFps = state.maxCaptureFps
                pipeline.thresholdMultiplier = state.thresholdMultiplier
                pipeline.captureAllowed = state.captureAllowed
                pipeline.stopReason = when (state.stopReason) {
                    StopReason.DISK_FULL -> EventEndReason.DISK_STOP
                    StopReason.LOW_BATTERY -> EventEndReason.LOW_BATTERY
                    StopReason.OVERHEATED -> EventEndReason.THERMAL_STOP
                    StopReason.NONE -> EventEndReason.SESSION_STOP
                }
                camera.setAnalysisFps(state.analysisFps)

                CaptureBus.publish {
                    it.copy(
                        guard = state,
                        analysisFps = state.analysisFps,
                        stats = recorder.stats.value,
                    )
                }
                updateNotification(state.describe())

                when (state.stopReason) {
                    // A full disk stops capture but keeps the service alive, so
                    // the session ends cleanly and the manifest stays writable.
                    StopReason.DISK_FULL -> if (!stopping) {
                        recorder.record(
                            Degradation(now, "disk", "capture stopped: ${state.describe()}")
                        )
                    }
                    // A flat battery is the end of the deployment either way; use
                    // the last few percent to close everything properly.
                    StopReason.LOW_BATTERY -> {
                        shutdown("low_battery")
                        return
                    }
                    StopReason.OVERHEATED -> if (!stopping) {
                        recorder.record(
                            Degradation(now, "thermal", "capture stopped: ${state.describe()}")
                        )
                    }
                    StopReason.NONE -> Unit
                }
            }

            mainHandler.postDelayed(this, settings.guards.powerSampleIntervalMillis)
        }
    }

    private fun shutdown(reason: String) {
        if (stopping) return
        stopping = true
        cameraReady = false
        mainHandler.removeCallbacksAndMessages(null)
        val now = System.currentTimeMillis()
        try {
            pipeline.close(now, EventEndReason.SESSION_STOP)
        } catch (t: Throwable) {
            // Closing a pipeline that never opened is not worth failing over.
        }
        try {
            camera.stop()
        } catch (ignored: Throwable) {
        }
        try {
            recorder.close(reason, now)
        } catch (ignored: Throwable) {
        }
        releaseWakeLock()
        CaptureBus.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // onDestroy is not guaranteed -- that is the whole premise -- so this is
        // a courtesy, not the mechanism. The manifest is already durable.
        shutdown("service_destroyed")
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * A partial wake lock, held for the session.
     *
     * The screen is off and Doze will try to suspend everything; a foreground
     * service with `foregroundServiceType="camera"` plus this lock is what keeps
     * the CPU available for the analysis stream. The battery-optimisation
     * exemption the UI offers is the other half -- without it, Doze can still
     * defer the timer that drives the power log.
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (ignored: Throwable) {
            // A lock that will not release is being torn down with the process.
        }
        wakeLock = null
    }

    private fun buildNotification(detail: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stats = if (::recorder.isInitialized) recorder.stats.value else null
        val title = if (::recorder.isInitialized) {
            getString(R.string.notification_title, recorder.session.sessionId)
        } else {
            getString(R.string.app_name)
        }
        val text = if (stats != null) {
            "${stats.events} events, ${stats.frames} frames - $detail"
        } else {
            detail
        }
        return Notification.Builder(this, InsectApp.CAPTURE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(detail: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(detail))
        } catch (ignored: Throwable) {
            // A notification that will not update is not a reason to stop.
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (t: Throwable) {
        "unknown"
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "biomon:insect-capture"
        private const val ACTION_STOP = "dk.biomon.insect.STOP"

        /**
         * Held statically because the UI comes and goes and the service must not
         * depend on it existing -- the deployment runs for nine hours with no
         * Activity at all.
         */
        @Volatile
        private var instance: CaptureService? = null

        fun start(context: Context) {
            val intent = Intent(context, CaptureService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        /**
         * Re-aim the lens on a running session. No-op when nothing is running:
         * the value is persisted by the settings store either way and takes
         * effect at the next session start.
         */
        fun setFocusDiopters(diopters: Float) {
            val service = instance ?: return
            if (!service.started || service.stopping || !service.cameraReady) return
            service.applyFocus(diopters)
        }
    }
}
