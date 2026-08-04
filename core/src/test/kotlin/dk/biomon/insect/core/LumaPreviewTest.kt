package dk.biomon.insect.core

import dk.biomon.insect.core.image.LumaPreview
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LumaPreviewTest {

    /**
     * Build a Y plane where the row stride exceeds the width, and the padding is
     * filled with a value that would be glaringly obvious if it leaked into the
     * output. This is the case that produces a sheared or black preview when the
     * stride is ignored, and it is the common case on real hardware.
     */
    private fun stridedPlane(width: Int, height: Int, stride: Int, value: (Int, Int) -> Int): ByteArray {
        val buf = ByteArray(stride * height) { 0xEE.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                buf[y * stride + x] = value(x, y).toByte()
            }
        }
        return buf
    }

    @Test
    fun `downsample honours a row stride wider than the image`() {
        val width = 8
        val height = 4
        val stride = 16
        // Every pixel is 100; the padding is 0xEE. Any stride mistake pulls the
        // padding in and the average jumps.
        val plane = stridedPlane(width, height, stride) { _, _ -> 100 }
        val out = ByteArray(LumaPreview.outputWidth(width, 2) * LumaPreview.outputHeight(height, 2))

        LumaPreview.downsample(plane, width, height, stride, 2, out)

        assertEquals(8, out.size)
        for (v in out) {
            assertEquals(100, v.toInt() and 0xFF) {
                "stride padding leaked into the preview: ${out.map { it.toInt() and 0xFF }}"
            }
        }
    }

    @Test
    fun `downsample preserves spatial layout, not just average brightness`() {
        val width = 8
        val height = 4
        val stride = 12
        // Left half dark, right half bright. A stride bug shears this.
        val plane = stridedPlane(width, height, stride) { x, _ -> if (x < 4) 20 else 200 }
        val out = ByteArray(4 * 2)

        LumaPreview.downsample(plane, width, height, stride, 2, out)

        val rows = (0 until 2).map { y -> (0 until 4).map { x -> out[y * 4 + x].toInt() and 0xFF } }
        for (row in rows) {
            assertEquals(listOf(20, 20, 200, 200), row) { "layout not preserved: $rows" }
        }
    }

    @Test
    fun `downsample averages a block rather than sampling one corner`() {
        val plane = stridedPlane(2, 2, 2) { x, y -> if (x == 0 && y == 0) 200 else 0 }
        val out = ByteArray(1)
        LumaPreview.downsample(plane, 2, 2, 2, 2, out)
        assertEquals(50, out[0].toInt() and 0xFF)
    }

    @Test
    fun `a short final row does not read past the end of the buffer`() {
        // Some devices do not pad the last row out to a full stride.
        val width = 4
        val height = 2
        val stride = 8
        val plane = ByteArray(stride * (height - 1) + width) { 120 }
        val out = ByteArray(2)
        LumaPreview.downsample(plane, width, height, stride, 2, out)
        assertTrue(out.all { (it.toInt() and 0xFF) in 1..120 }) {
            "unexpected values: ${out.map { it.toInt() and 0xFF }}"
        }
    }

    /**
     * Grayscale written without an alpha channel is fully transparent, which
     * draws as nothing and looks exactly like a dead camera.
     */
    @Test
    fun `toArgb produces opaque pixels`() {
        val gray = byteArrayOf(0, 127.toByte(), 255.toByte())
        val out = IntArray(3)
        LumaPreview.toArgb(gray, out)

        assertEquals(0xFF, (out[0] ushr 24) and 0xFF) { "pixel 0 is transparent" }
        assertEquals(0xFF, (out[1] ushr 24) and 0xFF)
        assertEquals(0xFF, (out[2] ushr 24) and 0xFF)

        assertEquals(0xFF000000.toInt(), out[0]) { "black should be opaque black" }
        assertEquals(0xFFFFFFFF.toInt(), out[2]) { "white should be opaque white" }
        // Grey is equal in all three colour channels.
        assertEquals(127, (out[1] ushr 16) and 0xFF)
        assertEquals(127, (out[1] ushr 8) and 0xFF)
        assertEquals(127, out[1] and 0xFF)
    }

    @Test
    fun `an all-zero plane stays black rather than becoming transparent`() {
        val out = IntArray(4)
        LumaPreview.toArgb(ByteArray(4), out)
        assertTrue(out.all { it == 0xFF000000.toInt() })
    }
}
