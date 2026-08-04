package dk.biomon.insect.core

import java.util.Random
import kotlin.math.sqrt

/**
 * Synthetic analysis frames reproducing the optics Phase 0.2 measures.
 *
 * Two properties matter and both are physical, not arbitrary:
 *
 * * **Vignetting.** Brightness falls off toward the corners, so the *same*
 *   insect -- same reflectance, same size -- produces a smaller absolute luma
 *   step in a corner than at centre. Bait sits in all four corners, so any
 *   threshold expressed as an absolute luma step is a threshold that asks
 *   corner stations for more contrast than centre ones.
 * * **Shot-noise-dominated sensor noise**, sigma proportional to sqrt(signal).
 *   That means the dim corners are also *quieter* in absolute terms, which is
 *   why a single global threshold set by the bright, noisy centre lands far too
 *   high for them.
 */
class SyntheticScene(
    val width: Int = 640,
    val height: Int = 480,
    /** Luma at the optical centre. */
    private val centreLuma: Float = 220f,
    /** Fraction of centre luma remaining in the extreme corner. */
    private val cornerFalloff: Float = 0.32f,
    /** Noise sigma at the centre; elsewhere it scales as sqrt(luma). */
    private val centreNoiseSigma: Float = 2.5f,
    seed: Long = 42,
) {
    private val random = Random(seed)
    private val base = FloatArray(width * height)
    private val buffer = ByteArray(width * height)
    private val noiseScale = centreNoiseSigma / sqrt(centreLuma)
    private var index = 0L

    init {
        val cx = (width - 1) / 2f
        val cy = (height - 1) / 2f
        val maxR = sqrt(cx * cx + cy * cy)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - cx
                val dy = y - cy
                val r = sqrt(dx * dx + dy * dy) / maxR
                base[y * width + x] = centreLuma * (1f - (1f - cornerFalloff) * r * r)
            }
        }
    }

    /** Luma of the static scene at (x, y), before noise. */
    fun baseLuma(x: Int, y: Int): Float = base[y * width + x]

    /**
     * Render one frame.
     *
     * @param target optional rectangle darkened by [contrast] *relative to local
     *   illumination*, which is how a real insect behaves: it reflects the light
     *   that reaches it, so the same insect produces a much smaller absolute
     *   signal in a vignetted corner than at centre.
     */
    fun frame(target: Rect? = null, contrast: Float = 0.20f): AnalysisFrame {
        for (i in base.indices) {
            val b = base[i]
            val v = b + (random.nextGaussian().toFloat() * noiseScale * sqrt(b))
            buffer[i] = v.coerceIn(0f, 255f).toInt().toByte()
        }
        if (target != null) {
            for (y in target.top until target.bottom) {
                for (x in target.left until target.right) {
                    val i = y * width + x
                    buffer[i] = (base[i] * (1f - contrast)).coerceIn(0f, 255f).toInt().toByte()
                }
            }
        }
        return AnalysisFrame(
            width = width,
            height = height,
            rowStride = width,
            luma = buffer,
            timestampNs = index * 200_000_000L,
            wallClockMillis = 1_700_000_000_000L + index * 200,
            index = index++,
        )
    }
}

data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        fun centredOn(cx: Int, cy: Int, size: Int) =
            Rect(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
    }
}
