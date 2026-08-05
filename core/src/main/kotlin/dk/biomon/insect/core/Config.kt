package dk.biomon.insect.core

/**
 * Tunables for the on-device trigger.
 *
 * Defaults are deliberately recall-oriented (DESIGN.md 3.2): it is better to
 * save 500 frames of a wind-blown leaf than to miss the one hoverfly. Precision
 * is the laptop pipeline's job.
 */
data class TriggerConfig(
    /** Analysis frames are downsampled by this factor before differencing. */
    val downsample: Int = 4,
    /** EMA learning rate for the background. Smaller adapts more slowly. */
    val backgroundAlpha: Float = 0.02f,
    /** EMA learning rate for per-region noise statistics. */
    val noiseAlpha: Float = 0.01f,
    /** Threshold = regionMean + [noiseSigmas] * regionSigma, floored below. */
    val noiseSigmas: Float = 4.0f,
    /**
     * Floor on the local threshold expressed as a *fraction of local
     * brightness*, not as an absolute luma step.
     *
     * This is the part that actually cancels vignetting. Downsampling by 4
     * averages 16 pixels and drops sensor noise by the same factor, so on a
     * daylight scene the noise term is tiny and whatever floor sits under it is
     * what really decides sensitivity. An absolute floor is a constant luma
     * step, so in a corner at 32% of centre brightness it demands roughly three
     * times the reflectance contrast -- exactly the positional bias DESIGN.md
     * 3.2 exists to remove. A fractional floor asks for the same *contrast*
     * everywhere, which is what "the same insect" actually produces.
     */
    val minContrastFraction: Float = 0.03f,
    /**
     * Absolute floor under everything, in 8-bit luma. Guards against
     * quantisation noise in a very dark region; not the operative limit in
     * daylight.
     */
    val minThreshold: Float = 2.0f,
    /** Ceiling on the local threshold, so a noisy region cannot go blind. */
    val maxThreshold: Float = 60.0f,
    /**
     * Grid used for local normalisation.
     *
     * Phase 0.2 *reports* on a 3x3 grid because that is a readable table, but
     * 3x3 is too coarse to actually run on: vignetting is radial, so a corner
     * cell that stretches a third of the way to the centre averages over a wide
     * brightness range and under-corrects at the corner itself. 8x6 cells cost
     * nothing measurable and track the falloff properly.
     */
    val regionGridCols: Int = 8,
    val regionGridRows: Int = 6,
    /** Minimum blob area, in downsampled pixels, to count as motion. */
    val minBlobAreaPx: Int = 4,
    /** Blobs bigger than this fraction of the frame are light changes, not insects. */
    val maxBlobAreaFraction: Float = 0.25f,
    /**
     * A pixel held foreground for this long is folded back into the background
     * regardless (DESIGN.md 3.3). Stops a moved bait dish or a shifted shadow
     * from pinning the model forever.
     */
    val forcedRefreshSeconds: Int = 120,
    /**
     * Seconds the background model is given to converge before the trigger is
     * allowed to fire at all.
     *
     * Expressed in seconds rather than frames because it is a property of the
     * scene, not of the analysis rate: the EMA needs roughly this long to settle
     * whether it is being fed 2fps or 5fps. Six seconds was not enough -- the
     * first real deployment opened with a 2m20s, 184-frame event seven seconds
     * in, which was the model still converging rather than anything alive.
     */
    val warmupSeconds: Int = 30,
    /** Multiplier applied to the threshold when the disk guard is degraded. */
    val diskPressureThresholdMultiplier: Float = 1.5f,
) {
    init {
        require(downsample >= 1) { "downsample must be >= 1" }
        require(backgroundAlpha > 0f && backgroundAlpha <= 1f)
        require(noiseAlpha > 0f && noiseAlpha <= 1f)
        require(regionGridCols >= 1 && regionGridRows >= 1)
        require(minThreshold > 0f && maxThreshold >= minThreshold)
        require(minContrastFraction >= 0f && minContrastFraction < 1f)
        require(warmupSeconds >= 0)
    }
}

/** Tunables for the capture stream and event assembly. */
data class CaptureConfig(
    /** Analysis stream framerate, before any thermal backoff. */
    val analysisFps: Int = 5,
    /** Capture rate while a blob is moving. */
    val movingFps: Float = 4f,
    /** Capture rate while a blob is present but stationary. */
    val stationaryFps: Float = 1f,
    /**
     * Centroid displacement, in downsampled pixels, below which a blob counts as
     * stationary.
     */
    val stationaryDisplacementPx: Float = 2.0f,
    /** Consecutive stationary analysis frames before dropping to [stationaryFps]. */
    val stationaryFrames: Int = 5,
    /** Capture continues this long after motion ceases. */
    val postRollMillis: Long = 5_000,
    /** JPEG quality. Below ~q75 blocking artefacts inflate laptop-side blob counts. */
    val jpegQuality: Int = 85,
    /** Hard cap on frames in a single event, so one waving leaf cannot fill the disk. */
    val maxFramesPerEvent: Int = 3_000,
) {
    init {
        require(analysisFps in 1..30)
        require(movingFps > 0f && stationaryFps > 0f)
        require(jpegQuality >= 85) { "DESIGN.md 3.4 sets a q85 floor" }
    }
}

/** Storage and thermal guard thresholds. */
data class GuardConfig(
    /** Below this much free space, degrade. */
    val degradeFreeBytes: Long = 5L * 1024 * 1024 * 1024,
    /** Below this much free space, stop capture and close the session cleanly. */
    val stopFreeBytes: Long = 1L * 1024 * 1024 * 1024,
    /** Capture rate ceiling while degraded. */
    val degradedMaxFps: Float = 1f,
    /** Battery percentage at which the session shuts down gracefully. */
    val lowBatteryStopPercent: Int = 5,
    /**
     * Battery temperature (Celsius) above which the analysis framerate is
     * reduced. The Tensor G1 runs warm and the phone sits closed up outdoors, so
     * this is expected to be reached on a hot afternoon rather than being an
     * exceptional condition.
     */
    val thermalReduceCelsius: Float = 40f,
    /**
     * Battery temperature above which capture stops. The service stays alive and
     * the manifest stays open, so the session ends cleanly and says why.
     */
    val thermalStopCelsius: Float = 45f,
    /** Interval between power-log samples. Non-negotiable #1 fixes this at 60s. */
    val powerSampleIntervalMillis: Long = 60_000,
) {
    init {
        require(stopFreeBytes < degradeFreeBytes)
        require(thermalReduceCelsius < thermalStopCelsius)
    }
}
