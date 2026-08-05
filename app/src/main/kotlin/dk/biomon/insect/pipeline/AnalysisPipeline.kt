package dk.biomon.insect.pipeline

import android.media.Image
import dk.biomon.insect.AppSettings
import dk.biomon.insect.CaptureBus
import dk.biomon.insect.MaskSnapshot
import dk.biomon.insect.PreviewFrame
import dk.biomon.insect.SessionRecorder
import dk.biomon.insect.camera.CameraController
import dk.biomon.insect.camera.StillRequest
import dk.biomon.insect.core.AnalysisFrame
import dk.biomon.insect.core.blob.Blob
import dk.biomon.insect.core.image.LumaPreview
import dk.biomon.insect.core.event.EventAction
import dk.biomon.insect.core.event.EventEndReason
import dk.biomon.insect.core.event.EventStateMachine
import dk.biomon.insect.core.manifest.Degradation
import dk.biomon.insect.core.manifest.ForcedRefresh
import dk.biomon.insect.core.manifest.RateChanged
import dk.biomon.insect.core.manifest.WarmupEnded
import dk.biomon.insect.core.manifest.WarmupStarted
import dk.biomon.insect.core.trigger.MotionTrigger
import dk.biomon.insect.core.trigger.TriggerDecision

/**
 * Where the analysis stream meets the trigger.
 *
 * Runs entirely on the camera's analysis thread and holds the only references to
 * [MotionTrigger] and [EventStateMachine], neither of which is internally
 * synchronised. Nothing here writes to disk: it returns decisions and issues
 * capture requests, and the recorder moves the actual bytes. Blocking this
 * thread for longer than the analysis interval starves the reader and drops
 * frames -- see docs/analysis-stream-contract.md.
 */
class AnalysisPipeline(
    private val settings: AppSettings,
    private val recorder: SessionRecorder,
    private val camera: CameraController,
) {
    private val trigger = MotionTrigger(settings.trigger, settings.capture.analysisFps)
    private val events = EventStateMachine(settings.capture)

    /** Reused across frames: the Y plane copy the contract says A owns. */
    private var lumaBuffer = ByteArray(0)
    private var frameIndex = 0L
    private var eventStartMillis = 0L

    /** Set by the service as the guards move. */
    @Volatile var maxCaptureFps: Float = settings.capture.movingFps
    @Volatile var thresholdMultiplier: Float = 1f
    @Volatile var captureAllowed: Boolean = true
    @Volatile var stopReason: EventEndReason = EventEndReason.DISK_STOP

    /** Full-resolution capture geometry, for scaling blob boxes. */
    @Volatile private var captureWidth = 0
    @Volatile private var captureHeight = 0
    @Volatile private var analysisWidth = 0

    /** Warm-up bookkeeping, so the opening gap is explicit in the manifest. */
    private var warmupStartMillis = 0L
    private var warmupEnded = false
    private var lastPublishMillis = 0L
    private var lastDroppedReported = 0L
    private var lastDropRecordMillis = 0L

    fun onGeometry(analysisW: Int, captureW: Int, captureH: Int) {
        analysisWidth = analysisW
        captureWidth = captureW
        captureHeight = captureH
    }

    /**
     * Record the warm-up window.
     *
     * The trigger is held off while the EMA converges, and without a record that
     * opening gap is indistinguishable from a dead sensor. Arming immediately is
     * the worse alternative: the first field run opened with a 2m20s, 184-frame
     * event seven seconds in, which was the model settling rather than anything
     * alive.
     */
    private fun noteWarmup(decision: TriggerDecision, nowMillis: Long) {
        if (warmupStartMillis == 0L) {
            warmupStartMillis = nowMillis
            recorder.record(WarmupStarted(nowMillis, settings.trigger.warmupSeconds))
            return
        }
        if (!warmupEnded && !decision.warmingUp) {
            warmupEnded = true
            recorder.record(
                WarmupEnded(nowMillis, decision.frameIndex, nowMillis - warmupStartMillis)
            )
        }
    }

    /** The scene may have moved during a camera rebuild; start the model again. */
    fun onCameraRestarted(nowMillis: Long) {
        trigger.reset()
        // A reset means the model converges again, so the warm-up window reopens.
        warmupStartMillis = 0L
        warmupEnded = false
        for (action in events.close(nowMillis, EventEndReason.CAMERA_ERROR)) {
            handle(action, nowMillis, emptyList())
        }
    }

    fun close(nowMillis: Long, reason: EventEndReason) {
        for (action in events.close(nowMillis, reason)) {
            handle(action, nowMillis, emptyList())
        }
    }

    /**
     * One analysis image. The [Image] is borrowed and closed by the caller; only
     * the Y plane is copied, and only for the duration of this call.
     */
    fun onImage(image: Image, nowMillis: Long) {
        val frame = copyLuma(image, nowMillis) ?: return
        val decision = trigger.onFrame(frame, thresholdMultiplier)
        noteWarmup(decision, nowMillis)

        if (decision.forcedRefreshPixels > 0) {
            recorder.record(
                ForcedRefresh(
                    atMillis = nowMillis,
                    pixels = decision.forcedRefreshPixels,
                    workPixels = decision.workWidth * decision.workHeight,
                    frameIndex = decision.frameIndex,
                )
            )
        }

        reportDrops(nowMillis)

        val blobsFullRes = scaleBlobs(decision.blobs, frame.width)
        val actions = events.onDecision(
            decision = decision,
            nowMillis = nowMillis,
            maxFps = maxCaptureFps,
            captureAllowed = captureAllowed,
            stopReason = stopReason,
        )
        for (action in actions) handle(action, nowMillis, blobsFullRes)

        publish(decision, frame, nowMillis)
    }

    private fun handle(action: EventAction, nowMillis: Long, blobsFullRes: List<Blob>) {
        when (action) {
            is EventAction.EventStarted -> {
                eventStartMillis = action.startMillis
                recorder.eventStarted(action.eventId, action.startMillis)
            }

            is EventAction.CaptureRequested -> camera.requestStill(
                StillRequest(
                    eventId = action.eventId,
                    sequence = action.sequence,
                    mode = action.mode.name.lowercase(),
                    requestedAtMillis = action.requestedAtMillis,
                    blobsFullRes = blobsFullRes,
                )
            )

            is EventAction.RateChanged -> recorder.record(
                RateChanged(
                    atMillis = action.atMillis,
                    eventId = action.eventId,
                    from = action.from.name.lowercase(),
                    to = action.to.name.lowercase(),
                    effectiveFps = action.effectiveFps,
                )
            )

            is EventAction.EventEnded -> recorder.eventEnded(
                eventId = action.eventId,
                atMillis = action.endMillis,
                frames = action.frameCount,
                reason = action.reason,
                durationMillis = action.endMillis - eventStartMillis,
            )
        }
    }

    /**
     * Copy the Y plane, honouring rowStride.
     *
     * `pixelStride` is 1 on every device that reports YUV_420_888 correctly, but
     * the trigger indexes `luma[y * rowStride + x]` directly, so a device that
     * interleaved luma would silently produce a garbage background model rather
     * than an error. De-interleaving is the cheap insurance.
     */
    private fun copyLuma(image: Image, nowMillis: Long): AnalysisFrame? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val width = image.width
            val height = image.height

            val needed = if (pixelStride == 1) rowStride * height else width * height
            if (lumaBuffer.size < needed) lumaBuffer = ByteArray(needed)

            val effectiveStride: Int
            if (pixelStride == 1) {
                buffer.rewind()
                val available = minOf(needed, buffer.remaining())
                buffer.get(lumaBuffer, 0, available)
                effectiveStride = rowStride
            } else {
                buffer.rewind()
                val row = ByteArray(rowStride)
                for (y in 0 until height) {
                    val toRead = minOf(rowStride, buffer.remaining())
                    if (toRead <= 0) break
                    buffer.get(row, 0, toRead)
                    var src = 0
                    var dst = y * width
                    for (x in 0 until width) {
                        lumaBuffer[dst++] = row[src]
                        src += pixelStride
                    }
                }
                effectiveStride = width
            }

            AnalysisFrame(
                width = width,
                height = height,
                rowStride = effectiveStride,
                luma = lumaBuffer,
                timestampNs = image.timestamp,
                wallClockMillis = nowMillis,
                index = frameIndex++,
            )
        } catch (t: Throwable) {
            recorder.record(
                Degradation(nowMillis, "analysis_frame", "unreadable: ${t.message}")
            )
            null
        }
    }

    private fun scaleBlobs(blobs: List<Blob>, analysisFrameWidth: Int): List<Blob> {
        if (blobs.isEmpty() || captureWidth <= 0) return blobs
        val scale = trigger.captureScale(analysisFrameWidth, captureWidth)
        return blobs.map { it.scaleTo(scale, scale) }
    }

    /**
     * Frames the pipeline never saw. Reported in aggregate rather than per drop:
     * a stall produces a burst, and one line per lost frame would bury the
     * manifest in exactly the situation where the rest of it matters most.
     */
    private fun reportDrops(nowMillis: Long) {
        val total = camera.droppedFrames
        if (total <= lastDroppedReported) return
        if (nowMillis - lastDropRecordMillis < DROP_REPORT_INTERVAL_MILLIS) return
        val delta = total - lastDroppedReported
        lastDroppedReported = total
        lastDropRecordMillis = nowMillis
        recorder.record(
            Degradation(
                nowMillis,
                "dropped_frames",
                "$delta analysis frame(s) not seen by the trigger ($total this session)",
            )
        )
    }

    /**
     * Publish to the UI a few times a second, never per frame, and always with a
     * copy of the mask -- the live one is recycled the moment this returns.
     */
    private fun publish(
        decision: TriggerDecision,
        frame: AnalysisFrame,
        nowMillis: Long,
    ) {
        if (nowMillis - lastPublishMillis < PUBLISH_INTERVAL_MILLIS) return
        lastPublishMillis = nowMillis
        val snapshot = maskSnapshot(decision)
        // Only while somebody is looking. During a deployment the screen is off
        // and this allocates nothing.
        val preview = if (CaptureBus.previewWanted) previewFrame(frame) else null
        CaptureBus.publish { state ->
            state.copy(
                mask = snapshot,
                preview = preview ?: state.preview.takeIf { CaptureBus.previewWanted },
                warmingUp = decision.warmingUp,
                captureMode = if (events.isActive) events.currentMode.name.lowercase() else null,
                activeEventId = if (events.isActive) events.currentEventId else null,
            )
        }
    }

    /**
     * Half-resolution copy of the luma for the UI.
     *
     * The downsample lives in :core because it is the row-stride arithmetic, and
     * getting that wrong shows up as a black or sheared rectangle rather than as
     * an error. A fresh array each time rather than a reused one: the UI reads it
     * on another thread, and a recycled buffer would tear. At 320x240 and a few
     * hertz that is trivial garbage, and only while the screen is on.
     */
    private fun previewFrame(frame: AnalysisFrame): PreviewFrame {
        val w = LumaPreview.outputWidth(frame.width, PREVIEW_DOWNSAMPLE)
        val h = LumaPreview.outputHeight(frame.height, PREVIEW_DOWNSAMPLE)
        val out = ByteArray(w * h)
        LumaPreview.downsample(
            luma = frame.luma,
            width = frame.width,
            height = frame.height,
            rowStride = frame.rowStride,
            factor = PREVIEW_DOWNSAMPLE,
            out = out,
        )
        return PreviewFrame(w, h, out)
    }

    private fun maskSnapshot(decision: TriggerDecision): MaskSnapshot? {
        val blobs = decision.blobs
        val w = decision.workWidth
        val h = decision.workHeight
        if (w <= 0 || h <= 0) return null
        // Rebuilding the mask from the blob boxes rather than copying the model's
        // buffer keeps the overlay allocation small and, more usefully, shows the
        // operator what actually triggered rather than every noisy pixel.
        val bytes = ByteArray(w * h)
        for (blob in blobs) {
            for (y in blob.top..blob.bottom) {
                if (y !in 0 until h) continue
                val base = y * w
                for (x in blob.left..blob.right) {
                    if (x in 0 until w) bytes[base + x] = 1
                }
            }
        }
        return MaskSnapshot(w, h, bytes, blobs)
    }

    private companion object {
        const val PREVIEW_DOWNSAMPLE = 2
        const val PUBLISH_INTERVAL_MILLIS = 250L
        const val DROP_REPORT_INTERVAL_MILLIS = 10_000L
    }
}
