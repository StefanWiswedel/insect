package dk.biomon.insect.core.trigger

import dk.biomon.insect.core.AnalysisFrame
import dk.biomon.insect.core.TriggerConfig
import dk.biomon.insect.core.background.EmaBackgroundModel
import dk.biomon.insect.core.blob.Blob
import dk.biomon.insect.core.blob.BlobDetector

/** What the trigger concluded about one analysis frame. */
data class TriggerDecision(
    val frameIndex: Long,
    val wallClockMillis: Long,
    /** Blobs in downsampled working coordinates, largest first. */
    val blobs: List<Blob>,
    /**
     * True once the model is past warm-up and at least one blob was found.
     * Always false on an [illumination] frame: capture is suppressed there.
     */
    val motion: Boolean,
    val foregroundFraction: Float,
    /**
     * Pixels the *per-pixel* forced refresh folded back into the background this
     * frame. Always 0 when [illumination] is true, where the whole-model
     * re-baseline supersedes it -- see [rebaselinedPixels].
     */
    val forcedRefreshPixels: Int,
    /** Components excluded for exceeding the area ceiling. */
    val rejectedTooLarge: Int,
    /**
     * True when a component covered more than
     * [TriggerConfig.illuminationAreaFraction] of the frame. The scene's
     * lighting changed; nothing here is a subject.
     */
    val illumination: Boolean,
    /** Area of the largest oversized component, in downsampled working pixels. */
    val illuminationAreaPx: Int,
    /** That area as a fraction of the frame -- the resolution-independent figure. */
    val illuminationAreaFraction: Float,
    /** Pixels adopted by the whole-model re-baseline, non-zero only on an illumination frame. */
    val rebaselinedPixels: Int,
    val warmingUp: Boolean,
    val workWidth: Int,
    val workHeight: Int,
) {
    val largest: Blob? get() = blobs.firstOrNull()
}

/**
 * Background model plus blob detection: the recall-oriented on-device filter.
 *
 * Generous by design (DESIGN.md 3.2). Anything plausible gets saved and the
 * laptop pipeline supplies precision; the failure that costs data is a missed
 * hoverfly, not a saved leaf.
 *
 * Runs on the analysis thread only, and does not retain [AnalysisFrame.luma].
 */
class MotionTrigger(
    private val config: TriggerConfig,
) {
    // Deliberately not told the analysis rate: the background model derives every
    // interval from frame timestamps, so it stays correct when thermal backoff
    // moves the rate underneath it.
    private val background = EmaBackgroundModel(config)
    private val detector = BlobDetector(config.minBlobAreaPx, config.illuminationAreaFraction)

    /**
     * Scales a working-coordinate blob into full-resolution capture coordinates,
     * given the size of the captured JPEG. Bounding boxes travel to the laptop as
     * metadata against the full frame, so they must be in its coordinate space.
     */
    fun captureScale(analysisWidth: Int, captureWidth: Int): Float =
        captureWidth.toFloat() / (analysisWidth / config.downsample)

    fun onFrame(frame: AnalysisFrame, thresholdMultiplier: Float = 1f): TriggerDecision {
        val result = background.process(frame, thresholdMultiplier)
        val blobs = if (result.warmingUp) {
            emptyList()
        } else {
            detector.detect(result.mask, result.workWidth, result.workHeight)
        }
        val workPixels = result.workWidth * result.workHeight

        // An oversized component is an illumination change, not a subject. Treat
        // it as an event in its own right rather than discarding it: outdoors,
        // cloud shadow crossing the board does this repeatedly, and how often the
        // light moved is something the laptop side needs to know.
        val oversizedAreaPx = if (result.warmingUp) 0 else detector.lastOversizedAreaPx
        val illumination = oversizedAreaPx > 0

        // The re-baseline supersedes any per-pixel forced refresh on this frame,
        // so exactly one refresh is reported. See EmaBackgroundModel.rebaseline.
        val rebaselined = if (illumination) background.rebaseline() else 0
        val forcedRefreshPixels = if (illumination) 0 else result.forcedRefreshPixels

        return TriggerDecision(
            frameIndex = frame.index,
            wallClockMillis = frame.wallClockMillis,
            // Nothing found on an illumination frame is trustworthy: the whole
            // residual field just moved. Suppress the small blobs too.
            blobs = if (illumination) emptyList() else blobs,
            motion = !illumination && blobs.isNotEmpty(),
            foregroundFraction = result.foregroundFraction,
            forcedRefreshPixels = forcedRefreshPixels,
            rejectedTooLarge = detector.lastRejectedTooLarge,
            illumination = illumination,
            illuminationAreaPx = oversizedAreaPx,
            illuminationAreaFraction =
                if (workPixels == 0) 0f else oversizedAreaPx.toFloat() / workPixels,
            rebaselinedPixels = rebaselined,
            warmingUp = result.warmingUp,
            workWidth = result.workWidth,
            workHeight = result.workHeight,
        )
    }

    /** Per-cell thresholds for the UI overlay. */
    fun regionThresholds(): FloatArray = background.regionThresholds()

    /** Called after a camera restart: the scene may have moved, so start again. */
    fun reset() = background.reset()
}
