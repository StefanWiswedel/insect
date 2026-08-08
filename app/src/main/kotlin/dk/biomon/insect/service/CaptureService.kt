package dk.biomon.insect.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
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
import dk.biomon.insect.store.PreviewRecorder
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
     * True while framing rather than recording. Nothing is written in this
     * state; see [PreviewRecorder]. Kept as its own flag rather than inferred
     * from the recorder type so the intent is legible at every use.
     */
    private var previewing = false

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
            ACTION_START_RECORDING -> {
                // Promoting a preview to a real session. The camera has to be
                // torn down and brought back up against a real recorder, so the
                // session owns every frame it ever sees -- a session that
                // inherited the preview's frames would have setup frames in it,
                // which is the whole thing preview exists to prevent.
                teardown("preview_ended")
                startSession(preview = false)
                return START_STICKY
            }
            ACTION_PREVIEW -> {
                if (!started) startSession(preview = true)
                return START_STICKY
            }
        }

        if (started) return START_STICKY

        if (intent != null) {
            // An intent with no action we recognise: the UI's plain "start a
            // session". Still an explicit request, so it is not the restart
            // path -- the discriminator below is a *null* intent, not an
            // unrecognised action.
            startSession(preview = false)
            return START_STICKY
        }

        // A null intent means the platform restarted us after killing the
        // process -- START_STICKY redelivers nothing. What we were doing is
        // therefore only knowable from what we wrote down before we died.
        //
        // Getting this wrong is not cosmetic. Defaulting to "recording" meant a
        // preview that got killed came back *recording*, opening a session
        // directory and writing frames nobody asked for -- the exact
        // contamination framing mode exists to prevent, arriving by a different
        // door.
        val previous = ResumeState.read(this)
        return when (previous.mode) {
            ResumeState.Mode.RECORDING -> {
                startSession(
                    preview = false,
                    resumedAfterKill = true,
                    previousSessionId = previous.sessionId,
                )
                START_STICKY
            }
            ResumeState.Mode.PREVIEW -> {
                // Come back to framing, which writes nothing. It is also the
                // safe direction to be wrong in.
                startSession(preview = true)
                START_STICKY
            }
            else -> {
                // We were stopped. Do not resurrect: an instrument the user has
                // switched off staying switched off matters more than uptime.
                //
                // startForeground first even though we are about to stop. A
                // service the platform restarted into the foreground must post
                // its notification promptly or the platform kills it with
                // ForegroundServiceDidNotStartInTimeException -- and crashing on
                // the way out is a worse way to decline than declining cleanly.
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("stopping"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                START_NOT_STICKY
            }
        }
    }

    /**
     * Swiping the app out of Recents.
     *
     * A running deployment must survive this -- the Activity is incidental and
     * the whole design assumes it is gone for nine hours. A *framing* preview
     * must not: nothing is being recorded, and holding the camera and a wake
     * lock open for a screen the user has just dismissed is pure cost with no
     * data to show for it.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (previewing) shutdown("preview_task_removed")
        super.onTaskRemoved(rootIntent)
    }

    private fun startSession(
        preview: Boolean = false,
        resumedAfterKill: Boolean = false,
        previousSessionId: String? = null,
    ) {
        started = true
        previewing = preview
        stopping = false
        settings = SettingsStore.get(applicationContext).settings.value

        // The typed overload is required from API 34 for a camera foreground
        // service, and the manifest declares the matching type. The camera
        // permission must already be granted or the platform refuses the start,
        // which is why the UI asks for it before offering the button.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(if (preview) "framing - not recording" else "starting"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
        acquireWakeLock()

        // Preview writes nothing: no session directory, no manifest, no
        // session id, nothing counted. See PreviewRecorder.
        recorder = if (preview) PreviewRecorder()
        else SessionStore.open(applicationContext, settings)

        // Written before anything else can kill us, so a restart knows what it
        // was interrupted doing rather than guessing.
        ResumeState.write(
            this,
            if (preview) ResumeState.Mode.PREVIEW else ResumeState.Mode.RECORDING,
            if (preview) null else recorder.session.sessionId,
        )

        // Non-negotiable #3: a session that exists only because the previous one
        // was killed must say so. Without this the corpus grows an extra session
        // directory, with an earlier one that simply stops mid-event, and
        // nothing anywhere connects the two.
        if (resumedAfterKill) {
            recorder.record(
                Degradation(
                    System.currentTimeMillis(),
                    "service",
                    "session started by a platform restart after the process was " +
                        "killed; the previous session (" +
                        (previousSessionId ?: "unknown") +
                        ") ended without a closing record",
                )
            )
        }

        powerLogger = PowerLogger(applicationContext, recorder)
        guards = GuardEvaluator(settings.guards, settings.capture, settings.trigger)

        camera = CameraController(applicationContext, settings, cameraCallbacks)
        pipeline = AnalysisPipeline(settings, recorder, camera)
        // Nothing is captured while framing, so no still is ever requested and
        // no event is ever opened. The analysis stream still runs, which is what
        // feeds the preview and the mask overlay.
        pipeline.captureAllowed = !preview
        cameraReady = true

        CaptureBus.publish {
            it.copy(
                running = !preview,
                previewing = preview,
                sessionId = if (preview) null else recorder.session.sessionId,
                analysisFps = settings.capture.analysisFps,
                focusDistanceDiopters = settings.focusDistanceDiopters,
                // Where it actually landed, not where it was meant to.
                storagePath = if (preview) null else recorder.session.directory.absolutePath,
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
                    thermalSeverity = reading.thermalSeverity,
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
                updateNotification(
                    if (previewing) "framing - not recording; ${state.describe()}"
                    else state.describe()
                )

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

    /**
     * Release everything the session owns, without stopping the service.
     *
     * Separate from [shutdown] because promoting a preview into a recording has
     * to tear the camera down and build it back up against a real recorder --
     * while the service keeps running, since it is holding the camera foreground
     * type either way. Calling [shutdown] there would schedule a `stopSelf` that
     * could land after the new session had started.
     */
    private fun teardown(reason: String) {
        if (!started) return
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
        // Cleared last, and only on a path that actually ran: its *absence* is
        // how a restart tells "the user stopped this" from "we were killed".
        ResumeState.clear(this)
        started = false
        previewing = false
    }

    private fun shutdown(reason: String) {
        if (stopping && !started) return
        teardown(reason)
        // Again, and unconditionally: teardown returns early when nothing was
        // started, and a stale resume record left behind by that path is exactly
        // what would make the service come back after being told to stop.
        ResumeState.clear(this)
        stopping = true
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
        // The only way to stop a nine-hour foreground service without opening
        // the app. Its absence was why the thing felt unkillable: the
        // notification is ongoing, so it cannot be swiped away, and tapping it
        // only opened the UI.
        val stop = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopLabel = if (previewing) "Close preview" else "Stop session"
        return Notification.Builder(this, InsectApp.CAPTURE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_launcher_foreground),
                    stopLabel,
                    stop,
                ).build()
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * What the service was doing, in storage that outlives the process.
     *
     * `START_STICKY` redelivers a null intent, so on a restart there is no other
     * way to know whether we were recording, framing, or already stopped.
     * `SharedPreferences` rather than the settings `DataStore` because this has
     * to be readable synchronously inside `onStartCommand`, before anything
     * suspending can run.
     */
    private object ResumeState {
        enum class Mode { RECORDING, PREVIEW, NONE }

        private const val FILE = "capture_service_resume"
        private const val KEY_MODE = "mode"
        private const val KEY_SESSION = "session_id"

        /** The mode read back, together with the session it belonged to. */
        class Snapshot(val mode: Mode, val sessionId: String?)

        private fun prefs(context: Context) =
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        fun write(context: Context, mode: Mode, sessionId: String?) {
            // commit(), not apply(): the whole point is to survive a kill that
            // may land in the next millisecond.
            prefs(context).edit()
                .putString(KEY_MODE, mode.name)
                .putString(KEY_SESSION, sessionId)
                .commit()
        }

        fun clear(context: Context) {
            prefs(context).edit().clear().commit()
        }

        fun read(context: Context): Snapshot {
            val p = prefs(context)
            val mode = runCatching { Mode.valueOf(p.getString(KEY_MODE, null) ?: "NONE") }
                .getOrDefault(Mode.NONE)
            return Snapshot(mode, p.getString(KEY_SESSION, null))
        }
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
        /** Distinct from the content intent's 0, or the two PendingIntents collide. */
        private const val STOP_REQUEST_CODE = 1
        private const val ACTION_PREVIEW = "dk.biomon.insect.PREVIEW"
        private const val ACTION_START_RECORDING = "dk.biomon.insect.START_RECORDING"

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

        /**
         * Framing mode: camera and analysis run, nothing is written. Uses the
         * same foreground service because the camera needs one either way.
         */
        fun startPreview(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_PREVIEW)
            context.startForegroundService(intent)
        }

        /** Promote a preview into a real recording session. */
        fun startRecording(context: Context) {
            val intent =
                Intent(context, CaptureService::class.java).setAction(ACTION_START_RECORDING)
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
