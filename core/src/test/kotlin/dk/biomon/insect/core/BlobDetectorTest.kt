package dk.biomon.insect.core

import dk.biomon.insect.core.blob.BlobDetector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlobDetectorTest {

    private fun mask(w: Int, h: Int, vararg rects: Rect): BooleanArray {
        val m = BooleanArray(w * h)
        for (r in rects) {
            for (y in r.top until r.bottom) for (x in r.left until r.right) m[y * w + x] = true
        }
        return m
    }

    @Test
    fun `separate rectangles become separate blobs, largest first`() {
        val detector = BlobDetector(minAreaPx = 2)
        val m = mask(40, 30, Rect(2, 2, 6, 6), Rect(20, 20, 30, 28))
        val blobs = detector.detect(m, 40, 30)
        assertEquals(2, blobs.size)
        assertEquals(80, blobs[0].areaPx)
        assertEquals(16, blobs[1].areaPx)
        assertEquals(20, blobs[0].left)
        assertEquals(29, blobs[0].right)
        assertEquals(27, blobs[0].bottom)
    }

    @Test
    fun `diagonal pixels are not connected under four-connectivity`() {
        val detector = BlobDetector(minAreaPx = 1)
        val m = mask(10, 10, Rect(1, 1, 2, 2), Rect(2, 2, 3, 3))
        assertEquals(2, detector.detect(m, 10, 10).size)
    }

    @Test
    fun `single-pixel sensor noise is rejected by minimum area`() {
        val detector = BlobDetector(minAreaPx = 4)
        val m = mask(20, 20, Rect(3, 3, 4, 4), Rect(10, 10, 11, 11))
        assertTrue(detector.detect(m, 20, 20).isEmpty())
    }

    /**
     * The detector has no upper bound: deciding a large component is the light
     * changing needs shape and position too, so it belongs to
     * `IlluminationClassifier`, which needs the whole list to do it -- including
     * the blob count, one of its three signals. A detector that dropped large
     * components would destroy that evidence before the rule saw it.
     */
    @Test
    fun `large components are returned, not dropped, so the classifier can judge them`() {
        val detector = BlobDetector(minAreaPx = 2)
        val m = mask(20, 20, Rect(0, 0, 20, 10), Rect(15, 15, 18, 18))
        val blobs = detector.detect(m, 20, 20)
        assertEquals(2, blobs.size)
        assertEquals(200, blobs[0].areaPx)
        assertEquals(9, blobs[1].areaPx)
    }

    @Test
    fun `a fully set mask does not overflow the stack`() {
        val detector = BlobDetector(minAreaPx = 1)
        val w = 320
        val h = 240
        val m = BooleanArray(w * h) { true }
        val blobs = detector.detect(m, w, h)
        assertEquals(1, blobs.size)
        assertEquals(w * h, blobs[0].areaPx)
    }

    @Test
    fun `centroid is the mean of member pixels`() {
        val detector = BlobDetector(minAreaPx = 1)
        val m = mask(20, 20, Rect(4, 6, 8, 10))
        val b = detector.detect(m, 20, 20).single()
        assertEquals(5.5f, b.centroidX, 1e-4f)
        assertEquals(7.5f, b.centroidY, 1e-4f)
    }

    @Test
    fun `scaling maps working coordinates into full resolution`() {
        val detector = BlobDetector(minAreaPx = 1)
        val b = detector.detect(mask(160, 120, Rect(10, 20, 14, 26)), 160, 120).single()
        val scaled = b.scaleTo(24f, 24f)
        assertEquals(240, scaled.left)
        assertEquals(480, scaled.top)
        assertEquals(336, scaled.right)
        assertEquals(624, scaled.bottom)
    }
}
