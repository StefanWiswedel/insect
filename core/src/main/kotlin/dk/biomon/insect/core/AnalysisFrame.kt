package dk.biomon.insect.core

/**
 * One frame off the low-resolution analysis stream.
 *
 * This is the contract between the capture core (which owns the Camera2 session
 * and the `ImageReader`) and the trigger pipeline (which owns the background
 * model). It is deliberately a plain data holder with no Android types so that
 * the trigger pipeline can be exercised on the JVM.
 *
 * ## Ownership rules -- read these before touching either side
 *
 * 1. **The producer owns the buffer.** [luma] is borrowed, not given. It is
 *    recycled as soon as [dk.biomon.insect.core.trigger.MotionTrigger.onFrame]
 *    returns. The consumer must not retain the array, wrap it in something that
 *    outlives the call, or hand it to another thread.
 * 2. **Delivery is synchronous and single-threaded.** Frames arrive on one
 *    dedicated analysis thread, in order, one at a time. The trigger pipeline is
 *    therefore not internally synchronised and must not be called from anywhere
 *    else.
 * 3. **The consumer must not block.** Anything slower than the analysis frame
 *    interval (200ms at 5fps) starves the `ImageReader` and drops frames. Disk
 *    and database work belongs on other threads.
 * 4. **Dropped frames are expected and must be reported, not hidden.** [index]
 *    is a monotonic counter assigned by the producer *before* any drop
 *    decision, so a gap in [index] at the consumer is exactly the number of
 *    frames dropped. Non-negotiable #3: they get logged.
 *
 * ## Format
 *
 * Y plane only, 8-bit, from a `YUV_420_888` image. Chroma is not copied -- the
 * background model is grayscale, and skipping chroma halves the copy cost.
 * Pixel (x, y) is at `luma[y * rowStride + x]` and is **unsigned**: use
 * [lumaAt], or `luma[i].toInt() and 0xFF`.
 */
class AnalysisFrame(
    /** Width in pixels of the valid image area. */
    @JvmField val width: Int,
    /** Height in pixels of the valid image area. */
    @JvmField val height: Int,
    /**
     * Distance in bytes between the starts of consecutive rows. May exceed
     * [width]; Camera2 pads rows and ignoring this shears the image.
     */
    @JvmField val rowStride: Int,
    /** Borrowed Y-plane buffer. See the ownership rules above. */
    @JvmField val luma: ByteArray,
    /** Sensor timestamp, same clock as `CaptureResult.SENSOR_TIMESTAMP`. */
    @JvmField val timestampNs: Long,
    /** Wall clock at capture, for correlating with the manifest and filenames. */
    @JvmField val wallClockMillis: Long,
    /** Monotonic frame counter assigned by the producer before any drop. */
    @JvmField val index: Long,
) {
    init {
        require(width > 0 && height > 0) { "empty frame: ${width}x$height" }
        require(rowStride >= width) { "rowStride $rowStride < width $width" }
        require(luma.size >= (height - 1) * rowStride + width) {
            "luma buffer too small: ${luma.size} for ${width}x$height stride $rowStride"
        }
    }

    /** Unsigned luma at (x, y). */
    fun lumaAt(x: Int, y: Int): Int = luma[y * rowStride + x].toInt() and 0xFF
}
