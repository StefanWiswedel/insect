package dk.biomon.insect.core.trigger

import dk.biomon.insect.core.AnalysisFrame
import dk.biomon.insect.core.TriggerConfig
import dk.biomon.insect.core.background.EmaBackgroundModel
import dk.biomon.insect.core.blob.Blob
import dk.biomon.insect.core.blob.BlobDetector
import dk.biomon.insect.core.illumination.IlluminationAssessment
import dk.biomon.insect.core.illumination.IlluminationClassifier

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
    /**
     * The illumination rule's verdict on this frame, with the measurements
     * behind it. Present on every frame that had blobs, not only on the ones
     * that were suppressed, so a detection can be shown as the near-miss it was.
     */
    val assessment: IlluminationAssessment,
    /**
     * True when the rule called this frame illumination rather than a subject:
     * either on size alone, or on size plus corroborating shape and position.
     */
    val illumination: Boolean,
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
    private val detector = BlobDetector(config.minBlobAreaPx)
    private val classifier = IlluminationClassifier(config)

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
        // Size gates entry, then shape and position decide. A blob big enough to
        // be the light changing is treated as an event in its own right rather
        // than discarded: outdoors, cloud shadow crossing the board does this
        // repeatedly, and how often the light moved is something the laptop side
        // needs to know.
        val assessment = classifier.classify(blobs, result.workWidth, result.workHeight)
        val illumination = assessment.verdict.isIllumination

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
            assessment = assessment,
            illumination = illumination,
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
