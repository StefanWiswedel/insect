package dk.biomon.insect.core.blob

/**
 * A connected region of the motion mask, in downsampled working coordinates.
 *
 * Bounding boxes are recorded as metadata and never used to crop on-device: the
 * phone writes full frames only, so cropping stays a lossless laptop-side
 * operation that can be re-run with different parameters later (DESIGN.md 4).
 */
data class Blob(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val areaPx: Int,
    val centroidX: Float,
    val centroidY: Float,
) {
    val width: Int get() = right - left + 1
    val height: Int get() = bottom - top + 1

    /** Scale this box into full-resolution sensor coordinates. */
    fun scaleTo(scaleX: Float, scaleY: Float): Blob = Blob(
        left = (left * scaleX).toInt(),
        top = (top * scaleY).toInt(),
        right = ((right + 1) * scaleX).toInt(),
        bottom = ((bottom + 1) * scaleY).toInt(),
        areaPx = (areaPx * scaleX * scaleY).toInt(),
        centroidX = centroidX * scaleX,
        centroidY = centroidY * scaleY,
    )
}

/**
 * Four-connected component labelling over a boolean motion mask.
 *
 * An iterative flood fill with an explicit int stack -- recursion would blow the
 * stack on a frame-wide light change, which is exactly the case that must not
 * crash the service. Buffers are retained between calls; the detector runs on
 * the analysis thread only and is not thread-safe.
 */
class BlobDetector(
    private val minAreaPx: Int,
) {
    private var visited = BooleanArray(0)
    private var stack = IntArray(0)

    /**
     * @param mask row-major motion mask, [width] * [height] entries.
     * @return every component at or above [minAreaPx], largest first.
     *
     * There is deliberately **no upper bound here**. Deciding that a large blob
     * is the light changing rather than a subject needs shape and position as
     * well as size, so it belongs to `IlluminationClassifier`, which sees the
     * whole list. A detector that dropped big components would destroy the
     * evidence that classifier runs on -- including the blob count, which is one
     * of its three signals.
     */
    fun detect(mask: BooleanArray, width: Int, height: Int): List<Blob> {
        val n = width * height
        if (visited.size != n) {
            visited = BooleanArray(n)
            stack = IntArray(n)
        } else {
            java.util.Arrays.fill(visited, false)
        }
        val blobs = ArrayList<Blob>()

        for (seed in 0 until n) {
            if (!mask[seed] || visited[seed]) continue
            var sp = 0
            stack[sp++] = seed
            visited[seed] = true
            var area = 0
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            var sumX = 0L
            var sumY = 0L

            while (sp > 0) {
                val p = stack[--sp]
                val x = p % width
                val y = p / width
                area++
                sumX += x
                sumY += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                if (x > 0 && mask[p - 1] && !visited[p - 1]) {
                    visited[p - 1] = true; stack[sp++] = p - 1
                }
                if (x < width - 1 && mask[p + 1] && !visited[p + 1]) {
                    visited[p + 1] = true; stack[sp++] = p + 1
                }
                if (y > 0 && mask[p - width] && !visited[p - width]) {
                    visited[p - width] = true; stack[sp++] = p - width
                }
                if (y < height - 1 && mask[p + width] && !visited[p + width]) {
                    visited[p + width] = true; stack[sp++] = p + width
                }
            }

            if (area < minAreaPx) continue
            blobs.add(
                Blob(
                    left = minX,
                    top = minY,
                    right = maxX,
                    bottom = maxY,
                    areaPx = area,
                    centroidX = sumX.toFloat() / area,
                    centroidY = sumY.toFloat() / area,
                )
            )
        }
        blobs.sortByDescending { it.areaPx }
        return blobs
    }
}
