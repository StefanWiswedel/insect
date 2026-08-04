package dk.biomon.insect.core

import dk.biomon.insect.core.trigger.MotionTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackgroundModelTest {

    private fun warmUp(trigger: MotionTrigger, scene: SyntheticScene, frames: Int = 60) {
        repeat(frames) { trigger.onFrame(scene.frame()) }
    }

    @Test
    fun `static scene does not trigger`() {
        val scene = SyntheticScene()
        val trigger = MotionTrigger(TriggerConfig(), analysisFps = 5)
        warmUp(trigger, scene)
        var triggered = 0
        repeat(50) { if (trigger.onFrame(scene.frame()).motion) triggered++ }
        assertTrue(triggered <= 2) { "static scene triggered $triggered/50 frames" }
    }

    @Test
    fun `moving target at centre triggers`() {
        val scene = SyntheticScene()
        val trigger = MotionTrigger(TriggerConfig(), analysisFps = 5)
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
        val local = TriggerConfig()
        val global = TriggerConfig(regionGridCols = 1, regionGridRows = 1)

        fun minContrast(config: TriggerConfig, target: Rect): Float {
            for (step in 1..40) {
                val contrast = step * 0.005f
                val scene = SyntheticScene(seed = 7)
                val trigger = MotionTrigger(config, analysisFps = 5)
                warmUp(trigger, scene, frames = 45)
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
        val config = TriggerConfig()
        for (target in listOf(
            Rect.centredOn(70, 70, 16),
            Rect.centredOn(570, 410, 16),
            Rect.centredOn(320, 240, 16),
        )) {
            val scene = SyntheticScene(seed = 11)
            val trigger = MotionTrigger(config, analysisFps = 5)
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
        val trigger = MotionTrigger(TriggerConfig(), analysisFps = 5)
        warmUp(trigger, scene)
        val target = Rect.centredOn(320, 240, 16)
        // 200 frames at 5fps is 40 seconds of sitting still. An unmasked EMA at
        // alpha 0.02 would have dissolved it inside a couple of seconds.
        var lastSeen = -1
        repeat(200) { i ->
            if (trigger.onFrame(scene.frame(target)).motion) lastSeen = i
        }
        assertEquals(199, lastSeen) { "target dissolved into the background at frame $lastSeen" }
    }

    /**
     * ...but a scene that genuinely changed must eventually re-baseline, or the
     * model stays pinned to a bait dish that was moved hours ago.
     */
    @Test
    fun `forced refresh re-baselines a permanently changed scene`() {
        val config = TriggerConfig(forcedRefreshSeconds = 4) // 20 frames at 5fps
        val scene = SyntheticScene(seed = 5)
        val trigger = MotionTrigger(config, analysisFps = 5)
        warmUp(trigger, scene)
        val moved = Rect.centredOn(320, 240, 40)
        var forcedTotal = 0
        var stillMotionAtEnd = true
        repeat(60) {
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
        val trigger = MotionTrigger(TriggerConfig(warmupFrames = 30), analysisFps = 5)
        val target = Rect.centredOn(320, 240, 40)
        repeat(30) {
            val d = trigger.onFrame(scene.frame(target))
            assertTrue(d.warmingUp)
            assertFalse(d.motion)
        }
    }

    @Test
    fun `frame-wide light change is rejected as too large`() {
        val scene = SyntheticScene(seed = 9)
        val trigger = MotionTrigger(TriggerConfig(), analysisFps = 5)
        warmUp(trigger, scene)
        val whole = Rect(0, 0, scene.width, scene.height)
        val d = trigger.onFrame(scene.frame(whole, contrast = 0.4f))
        assertTrue(d.rejectedTooLarge > 0) { "whole-frame change was not rejected" }
        assertTrue(d.blobs.isEmpty()) { "whole-frame change produced blobs: ${d.blobs.size}" }
    }

    @Test
    fun `threshold multiplier makes the trigger more selective under disk pressure`() {
        val scene = SyntheticScene(seed = 13)
        val trigger = MotionTrigger(TriggerConfig(), analysisFps = 5)
        warmUp(trigger, scene)
        val faint = Rect.centredOn(320, 240, 16)
        val normal = trigger.onFrame(scene.frame(faint, contrast = 0.10f))
        val selective = trigger.onFrame(scene.frame(faint, contrast = 0.10f), thresholdMultiplier = 4f)
        assertTrue(normal.blobs.sumOf { it.areaPx } >= selective.blobs.sumOf { it.areaPx }) {
            "raising the threshold did not reduce the flagged area"
        }
    }
}
