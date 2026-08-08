package dk.biomon.insect.core

/**
 * Tunables for the on-device trigger.
 *
 * Defaults are deliberately recall-oriented (DESIGN.md 3.2): it is better to
 * save 500 frames of a wind-blown leaf than to miss the one hoverfly. Precision
 * is the laptop pipeline's job.
 */
data class TriggerConfig(
    /**
     * Analysis frames are downsampled by this factor before differencing.
     *
     * **2, not 4.** The 4x downsample was chosen for noise reduction -- averaging
     * 16 sensor pixels drops sigma 4x -- but it costs 16x in *target area*, and
     * the target was already at the limit. At 31cm a fly is 1,500-3,000
     * full-resolution pixels, which at 4x is 2.4-4.7 working pixels against a
     * floor of 4: the rig could not reliably see its own subject, which is the
     * likeliest reason every early detection was an artefact.
     *
     * At 2x the working frame is 320x240 and the same fly is ~9-19 working
     * pixels, clear of any sensible floor. The noise trade is accepted: noise is
     * handled by the *amplitude* threshold, which is measured per region and
     * adapts, whereas lost target area cannot be recovered by anything
     * downstream.
     *
     * A setting so it can be reverted. Note that [minBlobAreaPx] is in working
     * pixels, so changing this changes what that floor means -- which is exactly
     * why the Detection geometry section of `SUMMARY.md` reports the ratio
     * between target size and floor on every run.
     */
    val downsample: Int = 2,
    /**
     * Time constant of the background EMA, in seconds.
     *
     * Expressed as a duration, not a per-frame learning rate. A fixed per-frame
     * alpha has a time constant of ~1/alpha *frames*, so thermal backoff from
     * 5fps to 2fps would make the background adapt 2.5x slower in wall-clock
     * terms -- which directly changes how long a stationary insect survives
     * before being absorbed, the success-paradox failure DESIGN.md 3.3 exists to
     * prevent. The per-frame alpha is derived from this and the actual interval
     * between frames.
     *
     * 10s matches the old 0.02-per-frame default at 5fps.
     */
    val backgroundTimeConstantSeconds: Float = 10f,
    /**
     * Time constant of the per-region noise statistics, in seconds. Same
     * reasoning; 20s matches the old 0.01-per-frame default at 5fps.
     */
    val noiseTimeConstantSeconds: Float = 20f,
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
    /**
     * Minimum blob area, in **working** (post-downsample) pixels, to count as
     * motion.
     *
     * Derived rather than scaled. Three questions set it:
     *
     * 1. *What must it reject?* Isolated threshold excursions. The amplitude
     *    threshold is already `regionMean + 4 sigma` with a brightness-relative
     *    floor, so a single working pixel crossing it is rare; what this floor
     *    adds is a demand for **spatial extent**. Three four-connected pixels is
     *    the smallest shape with extent on both axes -- a single pixel or a
     *    two-pixel domino can come from one hot sensor site surviving the box
     *    average, an L cannot.
     * 2. *What must it not reject?* The smallest target. At 31cm and 2x
     *    downsample that is ~9.4 working pixels, so a floor of 3 leaves a 3.1x
     *    margin, and 6.3x at the top of the range. The old floor of 4 at 4x
     *    downsample left a margin of **0.6x** -- the target was under the floor.
     * 3. *How much margin is enough?* Enough that a partly occluded insect, or
     *    one straddling a cell boundary, still clears it. 3x is; 1x is not.
     *
     * `DetectionGeometry` recomputes this relationship from the session's own
     * focus distance and reports it in `SUMMARY.md` every run, so a future
     * change to [downsample], analysis resolution or working distance cannot
     * quietly invalidate the derivation the way the last one did.
     */
    val minBlobAreaPx: Int = 3,
    /**
     * Upper bound on blob area, as a **fraction of frame area**. Anything larger
     * is an illumination change, not a subject (DESIGN.md 3.2).
     *
     * A fraction rather than a pixel count so it survives a resolution change:
     * the same 0.02 means the same thing whether the analysis stream is 640x480
     * or something else, and whether it is downsampled by 4 or not.
     *
     * The default is set from measurement. An insect at 25cm is roughly 4,900
     * full-resolution pixels; a person walking past the rig produced blobs of
     * ~2.9M pixels, about a quarter of the frame. 0.02 of a 12MP frame is
     * ~240,000 px -- about 40x the target and two orders of magnitude below the
     * walk-past -- so it separates the two cleanly with room on both sides.
     *
     * Blobs above this are **not discarded**. They raise an illumination event:
     * capture is suppressed for the frame, the background is re-baselined, and
     * an `illumination_event` record is written. Cloud shadow crossing the board
     * will do this repeatedly outdoors and the count is the point.
     *
     * This is the *certain* gate: at or above it the call is made on size alone.
     * See [illuminationSuspectFraction] for the corroborated tier.
     */
    val illuminationAreaFraction: Float = 0.02f,
    /**
     * Lower gate, above which a blob is *examined* rather than judged.
     *
     * 0.5% sits in the gap between a wings-spread moth (~60,000px, 0.49% of a
     * 12.19MP frame) and the smallest observed false detection (88,000px,
     * 0.72%). A blob over this gate must collect
     * [illuminationSignalsRequired] of three corroborating signals before it is
     * called illumination -- and a moth, being interior, compact and alone,
     * collects none, so it survives even when its size crosses the gate.
     */
    val illuminationSuspectFraction: Float = 0.005f,
    /** How close to an edge a bounding box must come to count as touching it. */
    val illuminationEdgeMarginFraction: Float = 0.01f,
    /** Blob area over bounding-box area, below which the blob is too sparse to be a body. */
    val illuminationFillRatioMax: Float = 0.35f,
    /** Simultaneous blobs needed for the count signal. */
    val illuminationBlobCountMin: Int = 3,
    /** How far apart those blobs must be, as a fraction of the frame diagonal. */
    val illuminationSpreadFractionMin: Float = 0.5f,
    /** Corroborating signals required to call a suspect blob illumination. */
    val illuminationSignalsRequired: Int = 2,
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
        require(backgroundTimeConstantSeconds > 0f)
        require(noiseTimeConstantSeconds > 0f)
        require(regionGridCols >= 1 && regionGridRows >= 1)
        require(minThreshold > 0f && maxThreshold >= minThreshold)
        require(minContrastFraction >= 0f && minContrastFraction < 1f)
        require(warmupSeconds >= 0)
        require(illuminationAreaFraction > 0f && illuminationAreaFraction <= 1f) {
            "illuminationAreaFraction must be a fraction of frame area"
        }
        require(illuminationSuspectFraction > 0f) {
            "illuminationSuspectFraction must be a fraction of frame area"
        }
        require(illuminationSuspectFraction <= illuminationAreaFraction) {
            "the suspect gate must not sit above the certain gate"
        }
        require(illuminationSignalsRequired in 0..3)
        require(illuminationBlobCountMin >= 1)
        require(illuminationFillRatioMax > 0f && illuminationFillRatioMax <= 1f)
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
     * Centroid speed, in downsampled pixels **per second**, below which a blob
     * counts as stationary.
     *
     * Per second rather than per frame: at 2fps an insect covers 2.5x more
     * ground between frames than at 5fps, so a per-frame threshold would read a
     * feeding insect as moving and hold capture at full rate -- pushing load up
     * at exactly the moment thermal backoff is trying to reduce it.
     *
     * 10 px/s matches the old 2px-per-frame default at 5fps.
     */
    val stationaryDisplacementPxPerSecond: Float = 10f,
    /**
     * How long a blob must stay slow before the capture rate drops. A frame
     * count here would mean 1s on a cool morning and 2.5s on a hot afternoon.
     */
    val stationarySeconds: Float = 1.0f,
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
        require(stationaryDisplacementPxPerSecond > 0f)
        require(stationarySeconds >= 0f)
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
     * reduced.
     *
     * Expected to be reached on an ordinary outdoor afternoon, not exceptionally.
     * Session 050826_0 measured the 6a idling at 32.1-33.5C *indoors* -- 3.4C
     * warmer than the 9a -- leaving only ~6.5C of headroom before this threshold,
     * which ambient plus an enclosure plus nine hours of continuous camera will
     * cover. The reduced-rate path is a normal operating mode here (DESIGN.md
     * 0.3), which is why every trigger duration is in seconds rather than frames.
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
