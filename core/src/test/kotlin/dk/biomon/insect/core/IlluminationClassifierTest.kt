package dk.biomon.insect.core

import dk.biomon.insect.core.blob.Blob
import dk.biomon.insect.core.illumination.IlluminationClassifier
import dk.biomon.insect.core.illumination.IlluminationVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The tiered rule, checked against the measurements that motivated it.
 *
 * All of these work in full-resolution coordinates on the 6a's 4032x3024 sensor,
 * because that is the space the field numbers were measured in.
 */
class IlluminationClassifierTest {

    private val w = 4032
    private val h = 3024
    private val frameArea = w.toLong() * h

    private val config = TriggerConfig()
    private val classifier = IlluminationClassifier(config)

    /** A compact blob of [areaPx] centred at ([cx], [cy]), filling [fill] of its box. */
    private fun compact(areaPx: Int, cx: Int, cy: Int, fill: Float = 0.85f): Blob {
        val boxArea = areaPx / fill
        val side = sqrt(boxArea.toDouble()).roundToInt().coerceAtLeast(1)
        return Blob(
            left = cx - side / 2,
            top = cy - side / 2,
            right = cx + side / 2,
            bottom = cy + side / 2,
            areaPx = areaPx,
            centroidX = cx.toFloat(),
            centroidY = cy.toFloat(),
        )
    }

    /** A sprawling band spanning the frame horizontally: the cloud-shadow shape. */
    private fun band(areaPx: Int, top: Int, bottom: Int): Blob = Blob(
        left = 0, top = top, right = w - 1, bottom = bottom,
        areaPx = areaPx,
        centroidX = w / 2f, centroidY = (top + bottom) / 2f,
    )

    @Test
    fun `a walk-past is illumination on size alone`() {
        // ~2.9M px, about a quarter of the frame.
        val v = classifier.classify(listOf(compact(2_900_000, 1000, 800, fill = 0.9f)), w, h)
        assertEquals(IlluminationVerdict.ILLUMINATION_CERTAIN, v.verdict)
    }

    /**
     * The case the whole tiered design exists for. A moth at ~60,000px is 0.49%
     * of frame, close under the smallest observed false detection at 0.72%. A
     * single threshold cannot separate them; corroboration can, because a moth
     * is interior, compact and alone.
     */
    @Test
    fun `a wings-spread moth survives, even sitting just over the suspect gate`() {
        val moth = compact(60_000, w / 2, h / 2, fill = 0.62f)
        val v = classifier.classify(listOf(moth), w, h)
        assertEquals(IlluminationVerdict.DETECTION, v.verdict) {
            "a moth was suppressed: ${v.describe()}"
        }

        // And a large one, comfortably over the 0.5% gate, still survives on
        // shape and position alone.
        val bigMoth = compact(90_000, w / 2, h / 2, fill = 0.62f)
        val bv = classifier.classify(listOf(bigMoth), w, h)
        assertEquals(IlluminationVerdict.DETECTION, bv.verdict) {
            "a large moth over the gate was suppressed on 0 signals: ${bv.describe()}"
        }
        assertEquals(0, bv.corroborating) { "a moth should trip no signals" }
    }

    @Test
    fun `an ordinary insect is far below even the suspect gate`() {
        val fly = compact(2_400, w / 2, h / 2)
        val v = classifier.classify(listOf(fly), w, h)
        assertEquals(IlluminationVerdict.DETECTION, v.verdict)
        assertTrue(v.signals.areaFraction < config.illuminationSuspectFraction)
    }

    /**
     * The observed false detections: 88k-236k px on a static indoor scene. Under
     * the 2% certain gate, so size alone would have passed them -- which is what
     * happened. With corroboration they are caught.
     */
    @Test
    fun `an edge-spanning sprawling band is caught by corroboration`() {
        // 0.72% of frame, spanning left to right, sparse in its box.
        val v = classifier.classify(listOf(band(88_000, 1200, 1400)), w, h)
        assertEquals(IlluminationVerdict.ILLUMINATION_CORROBORATED, v.verdict) {
            "88k px band read as ${v.describe()}"
        }
        assertTrue(v.edgeSignal) { "left-right span was not an edge signal" }
        assertTrue(v.fillSignal) { "a band should be sparse in its bounding box" }
    }

    @Test
    fun `several separated blobs at once are a global change`() {
        val blobs = listOf(
            compact(70_000, 300, 300),
            compact(65_000, w - 300, 300),
            compact(64_000, w / 2, h - 300),
        )
        val v = classifier.classify(blobs, w, h)
        assertTrue(v.countSignal) { "three widely separated blobs did not trip the count signal" }
    }

    /**
     * Bait sits in the corners of the board, so an insect at a corner station
     * legitimately reaches two *adjacent* edges. That must not count.
     */
    @Test
    fun `an insect at a corner bait station is not condemned by edge contact`() {
        val corner = Blob(
            left = 0, top = 0, right = 300, bottom = 300,
            areaPx = 76_000, centroidX = 150f, centroidY = 150f,
        )
        val v = classifier.classify(listOf(corner), w, h)
        assertEquals(2, v.signals.edgesTouched)
        assertFalse(v.signals.oppositeEdges)
        assertFalse(v.edgeSignal) {
            "two adjacent edges were treated as evidence of illumination"
        }
        assertEquals(IlluminationVerdict.DETECTION, v.verdict) { v.describe() }
    }

    @Test
    fun `one signal is not enough`() {
        // Over the gate and sparse, but interior and alone: 1 of 3.
        val sparse = Blob(
            left = 1500, top = 1200, right = 2500, bottom = 1700,
            areaPx = 80_000, centroidX = 2000f, centroidY = 1450f,
        )
        val v = classifier.classify(listOf(sparse), w, h)
        assertTrue(v.fillSignal)
        assertEquals(1, v.corroborating)
        assertEquals(IlluminationVerdict.DETECTION, v.verdict) {
            "one signal was enough: ${v.describe()}"
        }
    }

    @Test
    fun `no blobs is a detection with empty signals, not a crash`() {
        val v = classifier.classify(emptyList(), w, h)
        assertEquals(IlluminationVerdict.DETECTION, v.verdict)
        assertEquals(0, v.signals.areaPx)
    }

    /**
     * The classifier is used live on working-space blobs and retrospectively on
     * full-resolution blobs from the manifest. Every signal is a ratio, so both
     * must give the same verdict -- otherwise the diagnostics in SUMMARY.md
     * would not describe the rule the device actually ran.
     */
    @Test
    fun `the verdict is the same in working and full-resolution coordinates`() {
        val scale = 1f / 12.6f // 4032 -> 320 working px
        val cases = listOf(
            band(236_000, 1000, 1600),
            band(88_000, 1200, 1400),
            compact(60_000, w / 2, h / 2, fill = 0.62f),
            compact(2_900_000, 1000, 800, fill = 0.9f),
            compact(2_400, w / 2, h / 2),
        )
        for (full in cases) {
            val scaled = Blob(
                left = (full.left * scale).toInt(),
                top = (full.top * scale).toInt(),
                right = (full.right * scale).toInt(),
                bottom = (full.bottom * scale).toInt(),
                areaPx = (full.areaPx * scale * scale).toInt().coerceAtLeast(1),
                centroidX = full.centroidX * scale,
                centroidY = full.centroidY * scale,
            )
            val fullVerdict = classifier.classify(listOf(full), w, h).verdict
            val workVerdict = classifier
                .classify(listOf(scaled), (w * scale).toInt(), (h * scale).toInt())
                .verdict
            assertEquals(fullVerdict, workVerdict) {
                "verdict changed with coordinate space for a ${full.areaPx}px blob"
            }
        }
    }
}
