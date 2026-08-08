package dk.biomon.insect.core.geometry

/**
 * How big the subject actually is in the pixels the trigger works on.
 *
 * This exists because the rig was blind to its own target and nothing said so.
 * At 31cm a fly is roughly 1,500-3,000 full-resolution pixels; with the analysis
 * stream at 640x480 and a 4x downsample, that is **2.4-4.7 working pixels**
 * against a `minBlobAreaPx` floor of 4. The target was at or under the noise
 * floor, so almost nothing that triggered could have been an insect -- which is
 * consistent with every detection in the first sessions being an artefact.
 *
 * The failure was not the constant. It was that no part of the system related
 * the optics to the working resolution, so a downsample chosen for noise
 * reduction silently spent 16x the target's area to buy it. Every quantity here
 * is reported in `SUMMARY.md` on every run so that cannot recur unnoticed.
 */
object DetectionGeometry {

    /** Working distance the reference measurement was taken at. */
    const val ANCHOR_DISTANCE_CM = 31f

    /**
     * Full-resolution area of a typical target at [ANCHOR_DISTANCE_CM], on the
     * 12.19MP (4032x3024) sensor the measurement was made with. A fly-sized
     * insect: the low end is a small hoverfly, the high end a blowfly.
     */
    const val ANCHOR_AREA_MIN_PX = 1_500f
    const val ANCHOR_AREA_MAX_PX = 3_000f
    private const val ANCHOR_SENSOR_PX = 4032f * 3024f

    /** Ratio below which the target is not reliably separable from noise. */
    const val SAFE_RATIO = 2f

    /**
     * Expected full-resolution area of the subject at [distanceCm], on a sensor
     * of [capturePixels].
     *
     * Angular size goes as 1/distance, so pixel *area* goes as 1/distance^2.
     * Scaled by sensor resolution as well, so the figure stays right if the
     * capture size changes.
     */
    fun expectedAreaPx(anchorAreaPx: Float, distanceCm: Float, capturePixels: Long): Float {
        if (distanceCm <= 0f || capturePixels <= 0L) return 0f
        val distanceScale = (ANCHOR_DISTANCE_CM / distanceCm).let { it * it }
        val sensorScale = capturePixels / ANCHOR_SENSOR_PX
        return anchorAreaPx * distanceScale * sensorScale
    }

    /**
     * Convert a full-resolution area into working (post-downsample) pixels.
     *
     * The trigger never sees full-resolution pixels: it sees the analysis stream
     * reduced by [downsample]. This is the conversion the design was missing.
     */
    fun toWorkingPx(fullResAreaPx: Float, capturePixels: Long, workingPixels: Long): Float {
        if (capturePixels <= 0L || workingPixels <= 0L) return 0f
        return fullResAreaPx * (workingPixels.toDouble() / capturePixels).toFloat()
    }

    /** Focus in dioptres to working distance in cm. 0 D is infinity. */
    fun distanceCm(diopters: Float): Float =
        if (diopters <= 0.01f) Float.MAX_VALUE else 100f / diopters

    /**
     * Everything the summary needs, computed together so the numbers cannot
     * disagree with each other.
     */
    data class Assessment(
        val distanceCm: Float,
        val fullResMinPx: Float,
        val fullResMaxPx: Float,
        val workingMinPx: Float,
        val workingMaxPx: Float,
        val minBlobAreaPx: Int,
        val workingWidth: Int,
        val workingHeight: Int,
    ) {
        /** Smallest target as a multiple of the noise floor. Under 1 means blind. */
        val ratioMin: Float get() = if (minBlobAreaPx <= 0) 0f else workingMinPx / minBlobAreaPx
        val ratioMax: Float get() = if (minBlobAreaPx <= 0) 0f else workingMaxPx / minBlobAreaPx

        val blind: Boolean get() = ratioMin < 1f
        val marginal: Boolean get() = !blind && ratioMin < SAFE_RATIO
    }

    fun assess(
        focusDiopters: Float,
        captureWidth: Int,
        captureHeight: Int,
        analysisWidth: Int,
        analysisHeight: Int,
        downsample: Int,
        minBlobAreaPx: Int,
    ): Assessment? {
        if (captureWidth <= 0 || analysisWidth <= 0 || downsample < 1) return null
        val capturePixels = captureWidth.toLong() * captureHeight
        val workingWidth = analysisWidth / downsample
        val workingHeight = analysisHeight / downsample
        val workingPixels = workingWidth.toLong() * workingHeight
        if (workingPixels <= 0L) return null
        val distance = distanceCm(focusDiopters)
        if (distance == Float.MAX_VALUE) return null

        val fullMin = expectedAreaPx(ANCHOR_AREA_MIN_PX, distance, capturePixels)
        val fullMax = expectedAreaPx(ANCHOR_AREA_MAX_PX, distance, capturePixels)
        return Assessment(
            distanceCm = distance,
            fullResMinPx = fullMin,
            fullResMaxPx = fullMax,
            workingMinPx = toWorkingPx(fullMin, capturePixels, workingPixels),
            workingMaxPx = toWorkingPx(fullMax, capturePixels, workingPixels),
            minBlobAreaPx = minBlobAreaPx,
            workingWidth = workingWidth,
            workingHeight = workingHeight,
        )
    }
}
