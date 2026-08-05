package dk.biomon.insect.core

import dk.biomon.insect.core.trigger.MotionTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackgroundModelTest {

    /**
     * Run the trigger over a static scene for a *duration*, not a frame count.
     *
     * Everything in this file is expressed in seconds for the same reason the
     * model is: at 2fps a 60-frame warm-up is half a minute and at 5fps it is
     * twelve seconds, so a frame-count helper would quietly test something
     * different in each rate-varying case.
     */
    private fun warmUp(trigger: MotionTrigger, scene: SyntheticScene, seconds: Float = 12f) {
        repeat(scene.framesFor(seconds)) { trigger.onFrame(scene.frame()) }
    }

    @Test
    fun `static scene does not trigger`() {
        val scene = SyntheticScene()
        val trigger = MotionTrigger(TriggerConfig(warmupSeconds = 2))
        warmUp(trigger, scene)
        var triggered = 0
        repeat(50) { if (trigger.onFrame(scene.frame()).motion) triggered++ }
        assertTrue(triggered <= 2) { "static scene triggered $triggered/50 frames" }
    }

    @Test
    fun `moving target at centre triggers`() {
        val scene = SyntheticScene()
        val trigger = MotionTrigger(TriggerConfig(warmupSeconds = 2))
        warmUp(trigger, scene)
        val target = Rect.centredOn(scene.width / 2, scene.height / 2, 16)
        val decision = trigger.onFrame(scene.frame(target))
        assertTrue(decision.motion) { "centre target missed" }
        val blob = decision.largest!!
        assertTrue(blob.centroidX in 70f..90f) { "centroid x ${blob.centroidX}" }
        assertTrue(blob.centroidY in 50f..70f) { "centroid y ${blob.centroidY}" }
    }

    /**
     * The Phase 0.2 concern, as a measurement rather than a pass/fail: how much
     * more reflectance contrast does an insect need in a vignetted corner than
     * at centre before the trigger sees it?
     *
     * With a single global threshold that ratio is the vignetting factor itself,
     * because a constant luma step is a much larger fraction of a dim corner
     * than of a bright centre. With per-region normalisation it should collapse
     * to roughly 1 -- the same insect triggers wherever it lands, which is the
     * only way "which corner did they visit" is a variable you can analyse
     * rather than an artefact of the lens.
     */
    @Test
    fun `local normalisation equalises sensitivity across the frame`() {
        val centre = Rect.centredOn(320, 240, 16)
        // A corner bait station, set in from the frame edge the way a dish on the
        // board actually sits.
        val corner = Rect.centredOn(48, 48, 16)
        val local = TriggerConfig(warmupSeconds = 2)
        val global = TriggerConfig(regionGridCols = 1, regionGridRows = 1, warmupSeconds = 2)

        fun minContrast(config: TriggerConfig, target: Rect): Float {
            for (step in 1..40) {
                val contrast = step * 0.005f
                val scene = SyntheticScene(seed = 7)
                val trigger = MotionTrigger(config)
                warmUp(trigger, scene, seconds = 9f)
                if ((0 until 3).any { trigger.onFrame(scene.frame(target, contrast)).motion }) {
                    return contrast
                }
            }
            return Float.MAX_VALUE
        }

        val globalRatio = minContrast(global, corner) / minContrast(global, centre)
        val localRatio = minContrast(local, corner) / minContrast(local, centre)

        assertTrue(globalRatio > 1.5f) {
            "a global threshold was expected to need materially more contrast in " +
                "the corner (got ${"%.2f".format(globalRatio)}x); if this no longer " +
                "holds, the synthetic scene has stopped reproducing the optics and " +
                "the test is worthless"
        }
        assertTrue(localRatio < 1.2f) {
            "per-region normalisation still biased by ${"%.2f".format(localRatio)}x"
        }
        assertTrue(localRatio < globalRatio / 1.3f) {
            "local $localRatio was not a material improvement on global $globalRatio"
        }
    }

    @Test
    fun `corner and centre targets of equal reflectance are both detected locally`() {
        val config = TriggerConfig(warmupSeconds = 2)
        for (target in listOf(
            Rect.centredOn(70, 70, 16),
            Rect.centredOn(570, 410, 16),
            Rect.centredOn(320, 240, 16),
        )) {
            val scene = SyntheticScene(seed = 11)
            val trigger = MotionTrigger(config)
            warmUp(trigger, scene)
            assertTrue((0 until 5).any { trigger.onFrame(scene.frame(target)).motion }) {
                "missed target at $target"
            }
        }
    }

    /**
     * DESIGN.md 3.3, the success paradox. An insect that sits still must not be
     * absorbed into the background and have its residence time truncated.
     */
    @Test
    fun `stationary target is not absorbed into the background`() {
        val scene = SyntheticScene(seed = 3)
        val trigger = MotionTrigger(TriggerConfig(warmupSeconds = 2))
        warmUp(trigger, scene)
        val target = Rect.centredOn(320, 240, 16)
        // 40 seconds of sitting still. An unmasked EMA with a 10s time constant
        // would have dissolved it inside a few seconds.
        val frames = scene.framesFor(40f)
        var lastSeen = -1
        repeat(frames) { i ->
            if (trigger.onFrame(scene.frame(target)).motion) lastSeen = i
        }
        assertEquals(frames - 1, lastSeen) {
            "target dissolved into the background at frame $lastSeen"
        }
    }

    /**
     * ...but a scene that genuinely changed must eventually re-baseline, or the
     * model stays pinned to a bait dish that was moved hours ago.
     */
    @Test
    fun `forced refresh re-baselines a permanently changed scene`() {
        val config = TriggerConfig(forcedRefreshSeconds = 4, warmupSeconds = 2)
        val scene = SyntheticScene(seed = 5)
        val trigger = MotionTrigger(config)
        warmUp(trigger, scene)
        val moved = Rect.centredOn(320, 240, 40)
        var forcedTotal = 0
        var stillMotionAtEnd = true
        repeat(scene.framesFor(12f)) {
            val d = trigger.onFrame(scene.frame(moved))
            forcedTotal += d.forcedRefreshPixels
            stillMotionAtEnd = d.motion
        }
        assertTrue(forcedTotal > 0) { "forced refresh never fired" }
        assertFalse(stillMotionAtEnd) { "scene never re-baselined after forced refresh" }
    }

    @Test
    fun `warmup suppresses triggering`() {
        val scene = SyntheticScene()
        val trigger = MotionTrigger(TriggerConfig(warmupSeconds = 6))
        val target = Rect.centredOn(320, 240, 40)
        repeat(scene.framesFor(6f)) {
            val d = trigger.onFrame(scene.frame(target))
            assertTrue(d.warmingUp)
            assertFalse(d.motion)
        }
    }

    @Test
    fun `frame-wide light change is rejected as too large`() {
        val scene = SyntheticScene(seed = 9)
        val trigger = MotionTrigger(TriggerConfig(warmupSeconds = 2))
        warmUp(trigger, scene)
        val whole = Rect(0, 0, scene.width, scene.height)
        val d = trigger.onFrame(scene.frame(whole, contrast = 0.4f))
        assertTrue(d.rejectedTooLarge > 0) { "whole-frame change was not rejected" }
        assertTrue(d.blobs.isEmpty()) { "whole-frame change produced blobs: ${d.blobs.size}" }
    }

    /**
     * The measurement that set the default: walking past the rig produced blobs
     * of ~2.9M full-resolution pixels, about a quarter of the frame, while an
     * insect at 25cm is ~4,900. Anything in between is the light changing.
     *
     * The threshold is a *fraction* of frame area rather than a pixel count so
     * it means the same thing at any resolution or downsample factor -- which is
     * the same failure shape as the frames-vs-time bug class, one axis over.
     */
    @Test
    fun `an oversized blob is an illumination event, not a detection`() {
        val scene = SyntheticScene(seed = 33)
        val trigger = MotionTrigger(TriggerConfig(warmupSeconds = 2))
        warmUp(trigger, scene)
        // A quarter of the frame darkening at once: the walk-past case.
        val quarter = Rect(0, 0, scene.width / 2, scene.height)
        val d = trigger.onFrame(scene.frame(quarter, contrast = 0.4f))

        assertTrue(d.illumination) { "a quarter-frame blob was not called illumination" }
        assertFalse(d.motion) { "an illumination event must suppress capture" }
        assertTrue(d.blobs.isEmpty()) { "illumination frame still offered blobs" }
        assertTrue(d.illuminationAreaPx > 0) { "no area recorded for the event" }
        assertTrue(d.illuminationAreaFraction > 0.02f) {
            "area fraction ${d.illuminationAreaFraction} below the threshold that fired"
        }
    }

    /**
     * An insect must not be mistaken for weather. 4,900 full-resolution pixels
     * of a 12MP frame is ~0.0004 -- fifty times under the 0.02 threshold -- so
     * there is a wide margin, and this pins the near side of it.
     */
    @Test
    fun `an insect-sized blob is a detection, not an illumination event`() {
        val scene = SyntheticScene(seed = 35)
        val trigger = MotionTrigger(TriggerConfig(warmupSeconds = 2))
        warmUp(trigger, scene)
        val insect = Rect.centredOn(320, 240, 16)
        val d = trigger.onFrame(scene.frame(insect))
        assertFalse(d.illumination) { "an insect-sized blob raised an illumination event" }
        assertTrue(d.motion) { "insect missed" }
    }

    /**
     * The threshold is a fraction of frame area, so the *same scene* must give
     * the same verdict whatever the working resolution is. Downsample changes
     * the working pixel count by 16x between these two.
     */
    @Test
    fun `the illumination threshold is resolution-independent`() {
        for (downsample in listOf(1, 2, 4, 8)) {
            val config = TriggerConfig(downsample = downsample, warmupSeconds = 2)
            val big = SyntheticScene(seed = 37)
            val bigTrigger = MotionTrigger(config)
            warmUp(bigTrigger, big)
            val quarter = Rect(0, 0, big.width / 2, big.height)
            assertTrue(bigTrigger.onFrame(big.frame(quarter, contrast = 0.4f)).illumination) {
                "quarter-frame change missed at downsample $downsample"
            }

            val small = SyntheticScene(seed = 37)
            val smallTrigger = MotionTrigger(config)
            warmUp(smallTrigger, small)
            val insect = Rect.centredOn(320, 240, 16)
            assertFalse(smallTrigger.onFrame(small.frame(insect)).illumination) {
                "insect-sized change called illumination at downsample $downsample"
            }
        }
    }

    /**
     * A global brightness shift makes the background stale everywhere, so the
     * model re-baselines wholesale rather than waiting for the per-pixel forced
     * refresh to release pixels one at a time over two minutes.
     */
    @Test
    fun `an illumination event re-baselines the model so the scene settles at once`() {
        val scene = SyntheticScene(seed = 39)
        val trigger = MotionTrigger(TriggerConfig(warmupSeconds = 2))
        warmUp(trigger, scene)
        val shadow = Rect(0, 0, scene.width, scene.height)

        val event = trigger.onFrame(scene.frame(shadow, contrast = 0.3f))
        assertTrue(event.illumination)
        assertTrue(event.rebaselinedPixels > 0) { "no re-baseline on an illumination event" }

        // The shadow persists, but the model has already adopted it: the very
        // next frame is quiet. Without the re-baseline this would keep reporting
        // the whole board as motion until forcedRefreshSeconds elapsed.
        val next = trigger.onFrame(scene.frame(shadow, contrast = 0.3f))
        assertFalse(next.illumination) { "still illuminating one frame after the re-baseline" }
        assertFalse(next.motion) { "scene did not settle after the re-baseline" }
    }

    /**
     * The interaction the two refresh paths could get wrong: one stale-model
     * event must produce one refresh, not two. `forcedRefreshPixels` is zeroed
     * on an illumination frame because `rebaselinedPixels` supersedes it, and
     * the re-baseline zeroes every pixel's foreground timer so none can reach
     * its own deadline immediately afterwards either.
     */
    @Test
    fun `an illumination event does not also report a forced refresh`() {
        // A short forced-refresh window, so the per-pixel path is primed to fire
        // at exactly the moment the illumination event lands.
        val config = TriggerConfig(forcedRefreshSeconds = 2, warmupSeconds = 2)
        val scene = SyntheticScene(seed = 41)
        val trigger = MotionTrigger(config)
        warmUp(trigger, scene)

        val shadow = Rect(0, 0, scene.width, scene.height)
        var illuminationFrames = 0
        var doubleReported = 0
        var forcedAfterEvent = 0
        for (i in 0 until scene.framesFor(6f)) {
            val d = trigger.onFrame(scene.frame(shadow, contrast = 0.3f))
            if (d.illumination) {
                illuminationFrames++
                if (d.forcedRefreshPixels > 0) doubleReported++
            } else if (illuminationFrames > 0 && d.forcedRefreshPixels > 0) {
                forcedAfterEvent++
            }
        }
        assertTrue(illuminationFrames > 0) { "the shadow never raised an illumination event" }
        assertEquals(0, doubleReported) {
            "$doubleReported frames reported both a forced refresh and an illumination event"
        }
        assertEquals(0, forcedAfterEvent) {
            "the re-baseline did not reset the per-pixel timers: $forcedAfterEvent " +
                "forced refreshes followed it"
        }
    }

    @Test
    fun `threshold multiplier makes the trigger more selective under disk pressure`() {
        val scene = SyntheticScene(seed = 13)
        val trigger = MotionTrigger(TriggerConfig(warmupSeconds = 2))
        warmUp(trigger, scene)
        val faint = Rect.centredOn(320, 240, 16)
        val normal = trigger.onFrame(scene.frame(faint, contrast = 0.10f))
        val selective =
            trigger.onFrame(scene.frame(faint, contrast = 0.10f), thresholdMultiplier = 4f)
        assertTrue(normal.blobs.sumOf { it.areaPx } >= selective.blobs.sumOf { it.areaPx }) {
            "raising the threshold did not reduce the flagged area"
        }
    }

    /**
     * Warm-up is a duration, not a frame count, so halving the analysis rate
     * must not halve the time the model gets to converge.
     */
    @Test
    fun `warmup lasts the configured seconds regardless of analysis rate`() {
        for (fps in listOf(2, 3, 5, 8)) {
            val scene = SyntheticScene(fps = fps, seed = 17)
            val trigger = MotionTrigger(TriggerConfig(warmupSeconds = 4))
            var endedAtSeconds = Float.MAX_VALUE
            for (i in 0 until scene.framesFor(20f)) {
                if (!trigger.onFrame(scene.frame()).warmingUp) {
                    endedAtSeconds = i.toFloat() / fps
                    break
                }
            }
            // One frame of quantisation, and no more: the window is 4 seconds at
            // every rate, not 4 seconds at 5fps and 10 at 2fps.
            val slack = 1f / fps
            assertTrue(endedAtSeconds in 4f..(4f + 2 * slack)) {
                "warm-up ended at ${endedAtSeconds}s at $fps fps, not the configured 4s"
            }
        }
    }

    /**
     * The failure this exists to prevent: the first real deployment armed the
     * trigger six seconds in and opened with a 184-frame event that was the EMA
     * still settling. Thirty seconds of warm-up covers the convergence.
     */
    @Test
    fun `the default warmup outlasts the settling that produced a spurious opening event`() {
        val scene = SyntheticScene(seed = 19)
        val trigger = MotionTrigger(TriggerConfig())
        // Seven seconds in -- when the first field run fired event 1.
        repeat(scene.framesFor(7f)) { trigger.onFrame(scene.frame()) }
        assertTrue(trigger.onFrame(scene.frame()).warmingUp) {
            "trigger armed within the window that produced the spurious opening event"
        }
    }

    /**
     * The core of the frames-vs-time audit for the background EMA.
     *
     * A step change in the scene must fade at the same *wall-clock* rate at any
     * analysis rate. With a fixed per-frame alpha it did not: at 2fps the
     * background adapted 2.5x more slowly in seconds, so a scene that had
     * genuinely changed stayed flagged as motion for two and a half times as
     * long on a hot afternoon as on a cool morning. Reading the manifest, that
     * looks like insects being more active in the heat.
     *
     * The step is applied to the *unmasked* path by keeping it below the trigger
     * threshold, so what is measured is the EMA itself rather than the masked
     * update.
     */
    @Test
    fun `background convergence is measured in seconds, not frames`() {
        val convergence = listOf(2, 5, 10).map { fps ->
            val scene = SyntheticScene(fps = fps, seed = 23)
            val model = dk.biomon.insect.core.background.EmaBackgroundModel(
                TriggerConfig(warmupSeconds = 0, minContrastFraction = 0.5f)
            )
            // Settle on the plain scene.
            repeat(scene.framesFor(5f)) { model.process(scene.frame()) }
            // Then hold a sub-threshold change and time how long the residual
            // takes to decay to 1/e of its initial value -- the time constant.
            val faint = Rect(0, 0, scene.width, scene.height)
            var initial = -1f
            var seconds = Float.MAX_VALUE
            for (i in 0 until scene.framesFor(60f)) {
                val r = model.process(scene.frame(faint, contrast = 0.05f))
                val mean = r.residual.average().toFloat()
                if (i == 0) initial = mean
                if (initial > 0f && mean <= initial / Math.E.toFloat()) {
                    seconds = (i + 1).toFloat() / fps
                    break
                }
            }
            assertTrue(seconds < 60f) { "never converged at $fps fps" }
            fps to seconds
        }
        val values = convergence.map { it.second }
        val spread = values.max() / values.min()
        assertTrue(spread < 1.35f) {
            "background time constant varied with the analysis rate: $convergence"
        }
    }

    /**
     * The forced refresh is the release valve on the masked update, and how long
     * a moved bait dish pins the model is a duration the operator sets in
     * seconds. Counting frames instead would make a 120s release valve take five
     * minutes once thermal backoff halved the rate -- with no record that it had.
     */
    @Test
    fun `forced refresh fires after the configured seconds at any analysis rate`() {
        val fired = listOf(2, 5, 10).map { fps ->
            val scene = SyntheticScene(fps = fps, seed = 29)
            val trigger = MotionTrigger(TriggerConfig(forcedRefreshSeconds = 6, warmupSeconds = 2))
            warmUp(trigger, scene, seconds = 8f)
            val moved = Rect.centredOn(320, 240, 40)
            var seconds = Float.MAX_VALUE
            for (i in 0 until scene.framesFor(30f)) {
                if (trigger.onFrame(scene.frame(moved)).forcedRefreshPixels > 0) {
                    seconds = (i + 1).toFloat() / fps
                    break
                }
            }
            assertTrue(seconds < 30f) { "forced refresh never fired at $fps fps" }
            fps to seconds
        }
        for ((fps, seconds) in fired) {
            // One frame of quantisation either side of the configured 6s.
            assertTrue(seconds in 5.5f..7.0f) {
                "forced refresh fired at ${seconds}s at $fps fps, not the configured 6s: $fired"
            }
        }
    }

    /**
     * Deriving the step from timestamps is right, but trusting it without limit
     * is not: a stream that stalls for five minutes and then delivers one frame
     * would credit that frame with five minutes of confident observation and
     * replace the background wholesale. The step is clamped, so the model
     * forgets slowly after a stall rather than instantly.
     *
     * The change here is deliberately sub-threshold so it exercises the ordinary
     * EMA path rather than the masked one.
     */
    @Test
    fun `a long gap between frames does not wipe the background in one step`() {
        // minContrastFraction 0.5 puts the threshold far above the change, so
        // nothing is masked and what is measured is the EMA itself.
        val config = TriggerConfig(warmupSeconds = 0, minContrastFraction = 0.5f)
        val model = dk.biomon.insect.core.background.EmaBackgroundModel(config)
        val scene = SyntheticScene(seed = 31)
        repeat(scene.framesFor(5f)) { model.process(scene.frame()) }
        val faint = Rect(0, 0, scene.width, scene.height)
        val before = model.process(scene.frame(faint, contrast = 0.05f)).residual.average()
        // A frame arriving five minutes late.
        val late = scene.frame(faint, contrast = 0.05f)
        val stalled = AnalysisFrame(
            late.width, late.height, late.rowStride, late.luma,
            timestampNs = late.timestampNs + 300_000_000_000L,
            wallClockMillis = late.wallClockMillis + 300_000,
            index = late.index,
        )
        model.process(stalled)
        val after = model.process(scene.frame(faint, contrast = 0.05f)).residual.average()
        // Two clamped seconds against a 10s time constant leave ~80% standing.
        // Crediting the full 300s would have left essentially nothing.
        assertTrue(after > before * 0.5) {
            "a stalled frame collapsed the residual from $before to $after"
        }
    }
}
