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
    /** True once the model is past warm-up and at least one blob was found. */
    val motion: Boolean,
    val foregroundFraction: Float,
    /** Pixels the forced refresh folded back into the background this frame. */
    val forcedRefreshPixels: Int,
    /** Components dropped for being implausibly large (light change, not insect). */
    val rejectedTooLarge: Int,
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
    analysisFps: Int,
) {
    private val background = EmaBackgroundModel(config, analysisFps)
    private val detector = BlobDetector(config.minBlobAreaPx, config.maxBlobAreaFraction)

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
        return TriggerDecision(
            frameIndex = frame.index,
            wallClockMillis = frame.wallClockMillis,
            blobs = blobs,
            motion = blobs.isNotEmpty(),
            foregroundFraction = result.foregroundFraction,
            forcedRefreshPixels = result.forcedRefreshPixels,
            rejectedTooLarge = detector.lastRejectedTooLarge,
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
