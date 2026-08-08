package dk.biomon.insect.core.illumination

import dk.biomon.insect.core.TriggerConfig
import dk.biomon.insect.core.blob.Blob
import kotlin.math.hypot

/** What the rule decided, and on what grounds. */
enum class IlluminationVerdict {
    /** A subject. Capture normally. */
    DETECTION,

    /** Big enough that nothing biological could produce it. No corroboration needed. */
    ILLUMINATION_CERTAIN,

    /** Suspiciously large, and shape or position agreed. */
    ILLUMINATION_CORROBORATED,
    ;

    val isIllumination: Boolean get() = this != DETECTION
}

/**
 * The measurements behind a verdict, kept whole so the manifest and `SUMMARY.md`
 * can show why rather than only what.
 */
data class IlluminationSignals(
    /** Area of the largest blob, in whatever space it was measured. */
    val areaPx: Int,
    val frameAreaPx: Int,
    /** How many of the four frame edges the largest blob's box reaches. */
    val edgesTouched: Int,
    /** Whether two of them are an opposite pair (left+right or top+bottom). */
    val oppositeEdges: Boolean,
    /** Blob area over bounding-box area. A body fills its box; a gradient sprawls. */
    val fillRatio: Float,
    /** Blobs present in the same frame. */
    val blobCount: Int,
    /** Greatest centroid separation, as a fraction of the frame diagonal. */
    val spreadFraction: Float,
) {
    val areaFraction: Float
        get() = if (frameAreaPx <= 0) 0f else areaPx.toFloat() / frameAreaPx

    companion object {
        val EMPTY = IlluminationSignals(0, 0, 0, false, 0f, 0, 0f)
    }
}

/** A verdict together with the signals that produced it. */
data class IlluminationAssessment(
    val verdict: IlluminationVerdict,
    val signals: IlluminationSignals,
    /** Which corroborating tests fired. */
    val edgeSignal: Boolean,
    val fillSignal: Boolean,
    val countSignal: Boolean,
) {
    val corroborating: Int
        get() = (if (edgeSignal) 1 else 0) + (if (fillSignal) 1 else 0) +
            (if (countSignal) 1 else 0)

    /** One cell for a table, e.g. "illumination (2/3)" or "detection (1/3)". */
    fun describe(): String = when (verdict) {
        IlluminationVerdict.ILLUMINATION_CERTAIN -> "illumination (size)"
        IlluminationVerdict.ILLUMINATION_CORROBORATED -> "illumination ($corroborating/3)"
        IlluminationVerdict.DETECTION ->
            if (signals.areaPx == 0) "detection" else "detection ($corroborating/3)"
    }
}

/**
 * Decides whether a frame's blobs are a subject or the light changing.
 *
 * **Why not a single size threshold.** On the 12.19MP sensor a wings-spread moth
 * is ~60,000px (0.49% of frame) and the smallest observed false detection was
 * 88,000px (0.72%). A 1.5x gap: any single threshold either passes the artefacts
 * or suppresses the moths, and moth sessions are planned.
 *
 * So size is a **gate**, not a verdict:
 *
 * * At or above [TriggerConfig.illuminationAreaFraction] (2%) nothing biological
 *   is that large at this working distance, and the call is made on size alone.
 * * Between that and [TriggerConfig.illuminationSuspectFraction] (0.5%) the blob
 *   is examined, and needs [TriggerConfig.illuminationSignalsRequired] of three
 *   independent signals to agree before it is called illumination.
 * * Below the suspect gate it is a detection, untouched.
 *
 * The gate can afford to be loose because corroboration does the work: a moth is
 * interior, compact and alone, so it scores 0 of 3 and survives even if its size
 * puts it over the suspect gate. That is the property that makes this more
 * robust than a tighter number.
 *
 * Coordinate-agnostic: every signal is a ratio, so it gives the same answer on
 * working-space blobs (live) or full-resolution blobs recorded in the manifest
 * (retrospective). That is deliberate -- `SessionSummary` runs this same code
 * over recorded boxes, so the diagnostics can never drift from the live rule.
 */
class IlluminationClassifier(private val config: TriggerConfig) {

    fun classify(blobs: List<Blob>, frameWidth: Int, frameHeight: Int): IlluminationAssessment {
        if (blobs.isEmpty() || frameWidth <= 0 || frameHeight <= 0) {
            return IlluminationAssessment(
                IlluminationVerdict.DETECTION, IlluminationSignals.EMPTY, false, false, false
            )
        }

        val frameArea = frameWidth * frameHeight
        val largest = blobs.maxByOrNull { it.areaPx }!!

        val marginX = frameWidth * config.illuminationEdgeMarginFraction
        val marginY = frameHeight * config.illuminationEdgeMarginFraction
        val touchLeft = largest.left <= marginX
        val touchRight = largest.right >= frameWidth - 1 - marginX
        val touchTop = largest.top <= marginY
        val touchBottom = largest.bottom >= frameHeight - 1 - marginY
        val edges = (if (touchLeft) 1 else 0) + (if (touchRight) 1 else 0) +
            (if (touchTop) 1 else 0) + (if (touchBottom) 1 else 0)
        val opposite = (touchLeft && touchRight) || (touchTop && touchBottom)

        val boxArea = (largest.width.toLong() * largest.height).coerceAtLeast(1L)
        val fill = largest.areaPx / boxArea.toFloat()

        var spread = 0f
        for (i in blobs.indices) {
            for (j in i + 1 until blobs.size) {
                val d = hypot(
                    blobs[i].centroidX - blobs[j].centroidX,
                    blobs[i].centroidY - blobs[j].centroidY,
                )
                if (d > spread) spread = d
            }
        }
        val diagonal = hypot(frameWidth.toFloat(), frameHeight.toFloat())

        val signals = IlluminationSignals(
            areaPx = largest.areaPx,
            frameAreaPx = frameArea,
            edgesTouched = edges,
            oppositeEdges = opposite,
            fillRatio = fill,
            blobCount = blobs.size,
            spreadFraction = if (diagonal <= 0f) 0f else spread / diagonal,
        )

        // Two *opposite* edges, or three of four. Adjacent contact alone proves
        // nothing: bait sits in the corners of the board, so an insect at a
        // corner station legitimately reaches two adjacent edges.
        val edgeSignal = opposite || edges >= 3
        val fillSignal = fill < config.illuminationFillRatioMax
        val countSignal = blobs.size >= config.illuminationBlobCountMin &&
            signals.spreadFraction > config.illuminationSpreadFractionMin

        val verdict = when {
            signals.areaFraction >= config.illuminationAreaFraction ->
                IlluminationVerdict.ILLUMINATION_CERTAIN

            signals.areaFraction >= config.illuminationSuspectFraction &&
                (
                    (if (edgeSignal) 1 else 0) + (if (fillSignal) 1 else 0) +
                        (if (countSignal) 1 else 0)
                    ) >= config.illuminationSignalsRequired ->
                IlluminationVerdict.ILLUMINATION_CORROBORATED

            else -> IlluminationVerdict.DETECTION
        }

        return IlluminationAssessment(verdict, signals, edgeSignal, fillSignal, countSignal)
    }
}
