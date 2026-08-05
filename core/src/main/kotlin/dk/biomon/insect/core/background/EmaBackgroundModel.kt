package dk.biomon.insect.core.background

import dk.biomon.insect.core.AnalysisFrame
import dk.biomon.insect.core.TriggerConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Result of differencing one analysis frame against the background.
 *
 * Every array here is **borrowed** and is overwritten by the next call to
 * [EmaBackgroundModel.process]. Copy anything you need to keep (the UI overlay
 * does exactly that, on its own buffer).
 */
class BackgroundResult(
    @JvmField val workWidth: Int,
    @JvmField val workHeight: Int,
    /** `true` where the pixel differs from the background by more than the local threshold. */
    @JvmField val mask: BooleanArray,
    /** `|current - background|` per working pixel. */
    @JvmField val residual: FloatArray,
    /** The locally normalised threshold actually applied, per working pixel. */
    @JvmField val threshold: FloatArray,
) {
    /** True while the model is still filling its noise statistics. */
    @JvmField var warmingUp: Boolean = true

    /** Pixels folded back into the background this frame by the forced refresh. */
    @JvmField var forcedRefreshPixels: Int = 0

    /** Fraction of the frame flagged foreground. Useful for spotting light changes. */
    @JvmField var foregroundFraction: Float = 0f
}

/**
 * Exponential-moving-average background with a locally normalised threshold.
 *
 * Three things distinguish it from the laptop's rolling median:
 *
 * * **One frame of state.** The laptop holds a window of frames to take a
 *   median; that does not fit on the phone. An EMA holds one float per pixel.
 * * **Locally normalised thresholds** (DESIGN.md 3.2). The lens vignettes and
 *   softens toward the corners, and bait sits in all four corners, so a single
 *   global threshold would make corner stations systematically less sensitive
 *   than centre ones -- a positional bias baked into the data. The frame is
 *   divided into a grid; each cell tracks its own residual mean, residual sigma
 *   *and mean brightness*, and the per-pixel threshold is bilinearly
 *   interpolated between cell centres so there are no seams at cell boundaries.
 *   The brightness term matters more than it looks: after a 4x downsample the
 *   noise term is small, so what really sets sensitivity is the floor beneath
 *   it, and a floor scaled by local brightness is what makes a corner station
 *   and a centre station respond to the same reflectance contrast.
 * * **Masked update** (DESIGN.md 3.3, the success paradox). Pixels currently
 *   flagged as foreground are *not* folded into the background, so an insect
 *   that sits still feeding does not dissolve into the background and have its
 *   residence time silently truncated. Because that alone would pin the model
 *   forever if the scene genuinely changed -- bait moved, shadow shifted -- a
 *   pixel held foreground for [TriggerConfig.forcedRefreshSeconds] is folded in
 *   regardless, and the count is reported so it reaches the manifest.
 *
 * Not thread-safe: it runs on the analysis thread only, per the
 * [AnalysisFrame] ownership rules.
 */
class EmaBackgroundModel(
    private val config: TriggerConfig,
    analysisFps: Int,
) {
    private val forcedRefreshFrames: Int =
        max(1, config.forcedRefreshSeconds * max(1, analysisFps))

    /** Warm-up is configured in seconds; the model counts frames. */
    private val warmupFrames: Int = config.warmupSeconds * max(1, analysisFps)

    private var workWidth = 0
    private var workHeight = 0

    private lateinit var background: FloatArray
    private lateinit var current: FloatArray
    private lateinit var residual: FloatArray
    private lateinit var threshold: FloatArray
    private lateinit var mask: BooleanArray
    private lateinit var stuckFrames: IntArray

    /** Per-cell EMA of residual mean and of residual squared, background pixels only. */
    private lateinit var regionMean: FloatArray
    private lateinit var regionMeanSq: FloatArray
    private lateinit var regionThreshold: FloatArray
    /** Per-cell mean background luma, which is what vignetting shows up in. */
    private lateinit var regionLuma: FloatArray

    private var framesSeen = 0L
    private lateinit var result: BackgroundResult

    /** Frames processed since construction. Exposed for the manifest and UI. */
    val frameCount: Long get() = framesSeen

    /** Per-cell thresholds, row-major, for the UI overlay and for logging. */
    fun regionThresholds(): FloatArray = regionThreshold.copyOf()

    /**
     * Difference [frame] against the background and update the model.
     *
     * @param thresholdMultiplier scales the local threshold up when the disk
     *   guard wants the trigger to be more selective (DESIGN.md 4). 1.0 is normal.
     */
    fun process(frame: AnalysisFrame, thresholdMultiplier: Float = 1f): BackgroundResult {
        val ds = config.downsample
        val w = frame.width / ds
        val h = frame.height / ds
        require(w > 0 && h > 0) {
            "downsample $ds too aggressive for ${frame.width}x${frame.height}"
        }
        if (w != workWidth || h != workHeight) allocate(w, h)

        downsample(frame, ds)

        framesSeen++
        if (framesSeen == 1L) {
            System.arraycopy(current, 0, background, 0, current.size)
            java.util.Arrays.fill(mask, false)
            java.util.Arrays.fill(residual, 0f)
            result.warmingUp = true
            result.forcedRefreshPixels = 0
            result.foregroundFraction = 0f
            return result
        }

        computeRegionThresholds(thresholdMultiplier)

        // Threshold with the statistics gathered from previous frames, so this
        // frame's own blob cannot desensitise the cell it sits in.
        var foreground = 0
        for (i in current.indices) {
            val r = abs(current[i] - background[i])
            residual[i] = r
            val t = threshold[i]
            val hot = r > t
            mask[i] = hot
            if (hot) foreground++
        }

        val warming = framesSeen <= warmupFrames
        var forced = 0
        val alpha = config.backgroundAlpha
        for (i in current.indices) {
            if (mask[i] && !warming) {
                // Foreground: hold the background, but do not hold it forever.
                if (++stuckFrames[i] >= forcedRefreshFrames) {
                    background[i] = current[i]
                    stuckFrames[i] = 0
                    forced++
                }
            } else {
                stuckFrames[i] = 0
                background[i] += alpha * (current[i] - background[i])
            }
        }

        updateRegionStats()

        result.warmingUp = warming
        result.forcedRefreshPixels = forced
        result.foregroundFraction = foreground.toFloat() / current.size
        return result
    }

    /** Drop all state. Used after a camera restart, where the scene may have moved. */
    fun reset() {
        framesSeen = 0
        if (workWidth > 0) {
            java.util.Arrays.fill(stuckFrames, 0)
            java.util.Arrays.fill(regionMean, 0f)
            java.util.Arrays.fill(regionMeanSq, 0f)
        }
    }

    private fun allocate(w: Int, h: Int) {
        workWidth = w
        workHeight = h
        val n = w * h
        background = FloatArray(n)
        current = FloatArray(n)
        residual = FloatArray(n)
        threshold = FloatArray(n) { config.minThreshold }
        mask = BooleanArray(n)
        stuckFrames = IntArray(n)
        val cells = config.regionGridCols * config.regionGridRows
        regionMean = FloatArray(cells)
        regionMeanSq = FloatArray(cells)
        regionLuma = FloatArray(cells)
        regionThreshold = FloatArray(cells) { config.minThreshold }
        framesSeen = 0
        result = BackgroundResult(w, h, mask, residual, threshold)
    }

    /** Box-average [ds]x[ds] blocks of the Y plane into [current]. */
    private fun downsample(frame: AnalysisFrame, ds: Int) {
        val luma = frame.luma
        val stride = frame.rowStride
        val inv = 1f / (ds * ds)
        var out = 0
        for (by in 0 until workHeight) {
            val y0 = by * ds
            for (bx in 0 until workWidth) {
                val x0 = bx * ds
                var sum = 0
                for (dy in 0 until ds) {
                    var idx = (y0 + dy) * stride + x0
                    for (dx in 0 until ds) {
                        sum += luma[idx++].toInt() and 0xFF
                    }
                }
                current[out++] = sum * inv
            }
        }
    }

    /**
     * Turn per-cell noise statistics into a per-pixel threshold field, bilinearly
     * interpolated between cell centres.
     */
    private fun computeRegionThresholds(multiplier: Float) {
        val cols = config.regionGridCols
        val rows = config.regionGridRows
        computeRegionLuma()
        for (c in regionThreshold.indices) {
            val mean = regionMean[c]
            val variance = max(0f, regionMeanSq[c] - mean * mean)
            val noiseTerm = mean + config.noiseSigmas * sqrt(variance)
            // Ask each region for the same reflectance contrast, then let the
            // measured noise raise the bar where the region is genuinely noisy.
            val contrastTerm = config.minContrastFraction * regionLuma[c]
            val raw = max(contrastTerm, noiseTerm)
            regionThreshold[c] =
                min(config.maxThreshold, max(config.minThreshold, raw)) * multiplier
        }
        if (cols == 1 && rows == 1) {
            java.util.Arrays.fill(threshold, regionThreshold[0])
            return
        }
        val cellW = workWidth.toFloat() / cols
        val cellH = workHeight.toFloat() / rows
        var i = 0
        for (y in 0 until workHeight) {
            // Position relative to cell centres, so interpolation is symmetric.
            val fy = ((y + 0.5f) / cellH) - 0.5f
            val ry0 = fy.toInt().coerceIn(0, rows - 1)
            val ry1 = min(rows - 1, ry0 + 1)
            val wy = if (ry1 == ry0) 0f else (fy - ry0).coerceIn(0f, 1f)
            for (x in 0 until workWidth) {
                val fx = ((x + 0.5f) / cellW) - 0.5f
                val rx0 = fx.toInt().coerceIn(0, cols - 1)
                val rx1 = min(cols - 1, rx0 + 1)
                val wx = if (rx1 == rx0) 0f else (fx - rx0).coerceIn(0f, 1f)
                val t00 = regionThreshold[ry0 * cols + rx0]
                val t01 = regionThreshold[ry0 * cols + rx1]
                val t10 = regionThreshold[ry1 * cols + rx0]
                val t11 = regionThreshold[ry1 * cols + rx1]
                val top = t00 + (t01 - t00) * wx
                val bottom = t10 + (t11 - t10) * wx
                threshold[i++] = top + (bottom - top) * wy
            }
        }
    }

    /** Mean background luma per cell: the vignetting profile, measured live. */
    private fun computeRegionLuma() {
        val cols = config.regionGridCols
        val rows = config.regionGridRows
        val cellW = workWidth.toFloat() / cols
        val cellH = workHeight.toFloat() / rows
        val sum = FloatArray(regionLuma.size)
        val count = IntArray(regionLuma.size)
        var i = 0
        for (y in 0 until workHeight) {
            val rowBase = min(rows - 1, (y / cellH).toInt()) * cols
            for (x in 0 until workWidth) {
                val cell = rowBase + min(cols - 1, (x / cellW).toInt())
                sum[cell] += background[i++]
                count[cell]++
            }
        }
        for (c in regionLuma.indices) {
            if (count[c] > 0) regionLuma[c] = sum[c] / count[c]
        }
    }

    /** Fold this frame's background-pixel residuals into the per-cell statistics. */
    private fun updateRegionStats() {
        val cols = config.regionGridCols
        val rows = config.regionGridRows
        val cellW = workWidth.toFloat() / cols
        val cellH = workHeight.toFloat() / rows
        val cells = cols * rows
        val sum = FloatArray(cells)
        val sumSq = FloatArray(cells)
        val count = IntArray(cells)
        var i = 0
        for (y in 0 until workHeight) {
            val ry = min(rows - 1, (y / cellH).toInt())
            val rowBase = ry * cols
            for (x in 0 until workWidth) {
                if (!mask[i]) {
                    val cell = rowBase + min(cols - 1, (x / cellW).toInt())
                    val r = residual[i]
                    sum[cell] += r
                    sumSq[cell] += r * r
                    count[cell]++
                }
                i++
            }
        }
        val a = config.noiseAlpha
        for (c in 0 until cells) {
            if (count[c] == 0) continue // wholly foreground cell: keep prior stats
            val n = count[c].toFloat()
            val mean = sum[c] / n
            val meanSq = sumSq[c] / n
            if (framesSeen <= 2) {
                regionMean[c] = mean
                regionMeanSq[c] = meanSq
            } else {
                regionMean[c] += a * (mean - regionMean[c])
                regionMeanSq[c] += a * (meanSq - regionMeanSq[c])
            }
        }
    }
}
