package dk.biomon.insect.core

import dk.biomon.insect.core.geometry.DetectionGeometry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The arithmetic that nobody had done, which is why the rig was blind.
 *
 * These are not tests of a formula for its own sake. Each one pins a claim the
 * deployment depends on, and the first one reproduces the actual failure.
 */
class DetectionGeometryTest {

    private val captureW = 4032
    private val captureH = 3024
    private val analysisW = 640
    private val analysisH = 480
    private val focus3_23D = 3.23f

    private fun assess(downsample: Int, minBlobAreaPx: Int) = DetectionGeometry.assess(
        focusDiopters = focus3_23D,
        captureWidth = captureW,
        captureHeight = captureH,
        analysisWidth = analysisW,
        analysisHeight = analysisH,
        downsample = downsample,
        minBlobAreaPx = minBlobAreaPx,
    )!!

    /**
     * The bug, as a test. At 4x downsample with a floor of 4, the smallest
     * expected insect could not form a blob big enough to trigger -- so nothing
     * the rig detected could have been one.
     */
    @Test
    fun `the old configuration was blind to its own target`() {
        val old = assess(downsample = 4, minBlobAreaPx = 4)
        assertTrue(old.workingMinPx < 5f) { "expected ~2.4 working px, got ${old.workingMinPx}" }
        assertTrue(old.blind) {
            "4x downsample with a floor of 4 should register as blind: " +
                "${old.workingMinPx}px target vs ${old.minBlobAreaPx}px floor"
        }
    }

    @Test
    fun `the shipped configuration clears the floor with margin`() {
        val now = assess(
            downsample = TriggerConfig().downsample,
            minBlobAreaPx = TriggerConfig().minBlobAreaPx,
        )
        assertFalse(now.blind) { "still blind: ${now.workingMinPx} vs ${now.minBlobAreaPx}" }
        assertFalse(now.marginal) {
            "only ${now.ratioMin}x margin, under the ${DetectionGeometry.SAFE_RATIO}x floor"
        }
        // ~9.4 to ~18.9 working px at 2x, as derived in Config.kt.
        assertTrue(now.workingMinPx in 8f..11f) { "workingMin ${now.workingMinPx}" }
        assertTrue(now.workingMaxPx in 17f..21f) { "workingMax ${now.workingMaxPx}" }
        assertTrue(now.ratioMin >= 3f) { "margin only ${now.ratioMin}x" }
    }

    /** Halving the downsample quadruples target area: the trade being made. */
    @Test
    fun `target area scales with the square of the downsample factor`() {
        val at4 = assess(downsample = 4, minBlobAreaPx = 3)
        val at2 = assess(downsample = 2, minBlobAreaPx = 3)
        val ratio = at2.workingMinPx / at4.workingMinPx
        assertTrue(ratio in 3.5f..4.5f) { "expected ~4x more target area, got ${ratio}x" }
    }

    /** Moving the rig further away shrinks the subject as the inverse square. */
    @Test
    fun `working distance enters as an inverse square`() {
        val near = DetectionGeometry.expectedAreaPx(
            DetectionGeometry.ANCHOR_AREA_MIN_PX, 31f, captureW.toLong() * captureH
        )
        val far = DetectionGeometry.expectedAreaPx(
            DetectionGeometry.ANCHOR_AREA_MIN_PX, 62f, captureW.toLong() * captureH
        )
        assertTrue(near / far in 3.5f..4.5f) { "doubling distance should quarter area" }
    }

    /**
     * A rig set far enough back becomes blind again, and the summary has to say
     * so. This is the guard that makes the geometry section worth printing.
     */
    @Test
    fun `moving the rig back far enough is reported as blind`() {
        val far = DetectionGeometry.assess(
            focusDiopters = 0.8f, // 125 cm
            captureWidth = captureW,
            captureHeight = captureH,
            analysisWidth = analysisW,
            analysisHeight = analysisH,
            downsample = 2,
            minBlobAreaPx = 3,
        )!!
        assertTrue(far.blind) { "at 125cm the target should be under the floor" }
    }

    @Test
    fun `focus at infinity is reported as not computable rather than guessed`() {
        assertNull(
            DetectionGeometry.assess(0f, captureW, captureH, analysisW, analysisH, 2, 3)
        )
    }
}
