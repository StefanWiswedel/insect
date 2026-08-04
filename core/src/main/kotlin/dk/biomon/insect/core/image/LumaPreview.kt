package dk.biomon.insect.core.image

/**
 * Turning a borrowed Y plane into something displayable.
 *
 * This lives in `:core` rather than next to the UI because it is arithmetic, and
 * arithmetic that goes wrong here fails in the least helpful way possible: a
 * black rectangle, on a phone, in a field. On the JVM it is a handful of tests.
 *
 * The whole job is **row stride**. A camera's Y plane is almost never `width`
 * bytes per row -- the hardware pads rows out to an alignment boundary, so
 * `rowStride` is commonly 640 for a 640-wide image but just as commonly 704, 768
 * or something else entirely. Treating the buffer as densely packed then either
 * shears the image diagonally or, when the buffer is shorter than
 * `width * height`, walks off the end of the data and produces zeros.
 */
object LumaPreview {

    fun outputWidth(width: Int, factor: Int): Int = width / factor

    fun outputHeight(height: Int, factor: Int): Int = height / factor

    /**
     * Box-average [factor]x[factor] blocks of a strided Y plane into a densely
     * packed grayscale buffer.
     *
     * @param luma source Y plane; pixel (x, y) is at `luma[y * rowStride + x]`.
     * @param out destination, `outputWidth * outputHeight` bytes, densely packed.
     */
    fun downsample(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        factor: Int,
        out: ByteArray,
    ) {
        require(factor >= 1) { "factor must be >= 1" }
        require(rowStride >= width) { "rowStride $rowStride < width $width" }
        val w = outputWidth(width, factor)
        val h = outputHeight(height, factor)
        require(out.size >= w * h) { "out too small: ${out.size} < ${w * h}" }

        val divisor = factor * factor
        var i = 0
        for (by in 0 until h) {
            val y0 = by * factor
            for (bx in 0 until w) {
                val x0 = bx * factor
                var sum = 0
                for (dy in 0 until factor) {
                    val rowBase = (y0 + dy) * rowStride
                    for (dx in 0 until factor) {
                        val idx = rowBase + x0 + dx
                        // A short final row is normal: some devices do not pad the
                        // last row out to the full stride. Treat the missing bytes
                        // as absent rather than reading past the end.
                        if (idx < luma.size) sum += luma[idx].toInt() and 0xFF
                    }
                }
                out[i++] = (sum / divisor).toByte()
            }
        }
    }

    /**
     * Expand densely packed grayscale to opaque ARGB_8888.
     *
     * Alpha is forced to 0xFF. A grayscale value written into the colour channels
     * without an alpha is fully transparent, which draws as nothing at all -- the
     * exact symptom that looks like a broken camera rather than a broken shift.
     */
    fun toArgb(gray: ByteArray, out: IntArray, count: Int = gray.size) {
        require(out.size >= count) { "out too small: ${out.size} < $count" }
        for (i in 0 until count) {
            val v = gray[i].toInt() and 0xFF
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }
}
