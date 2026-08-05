package dk.biomon.insect.core

import dk.biomon.insect.core.event.CaptureMode
import dk.biomon.insect.core.event.EventAction
import dk.biomon.insect.core.event.EventStateMachine
import dk.biomon.insect.core.policy.GuardEvaluator
import dk.biomon.insect.core.trigger.MotionTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end through the parts that decide how much data a deployment produces.
 *
 * These are the arithmetic claims the whole design rests on, so they are checked
 * rather than asserted in prose.
 */
class SessionSimulationTest {

    private val megabytesPerFrame = 4.0

    /**
     * A five-minute feeding visit: the insect arrives, moves about for a few
     * seconds, feeds motionless for most of five minutes, then leaves.
     *
     * At a flat 3fps that visit costs over 3GB -- a tenth of the device's free
     * space for one insect. The whole point of varying the rate within an event
     * is that the stationary stretch is where the frames are redundant.
     */
    @Test
    fun `a five-minute feeding visit costs a fraction of a flat capture rate`() {
        val config = CaptureConfig(movingFps = 4f, stationaryFps = 1f, stationaryFrames = 5)
        val sm = EventStateMachine(config)
        val analysisIntervalMs = 200L
        var now = 0L
        var captured = 0
        var stationaryCaptures = 0
        var movingCaptures = 0
        val rateChanges = mutableListOf<EventAction.RateChanged>()

        fun step(x: Float?) {
            val actions = sm.onDecision(decisionAt(x, now / analysisIntervalMs), now)
            for (a in actions) {
                when (a) {
                    is EventAction.CaptureRequested -> {
                        captured++
                        if (a.mode == CaptureMode.STATIONARY) stationaryCaptures++ else movingCaptures++
                    }
                    is EventAction.RateChanged -> rateChanges += a
                    else -> Unit
                }
            }
            now += analysisIntervalMs
        }

        // Arrival: 4 seconds of movement.
        repeat(20) { step(20f + it * 3f) }
        // Feeding: 4 minutes 40 seconds parked.
        repeat(1400) { step(80f) }
        // Departure: 4 seconds of movement, then gone.
        repeat(20) { step(80f + it * 3f) }
        repeat(60) { step(null) }

        val flatRateFrames = (5 * 60 * 3)
        val savedFraction = 1.0 - captured.toDouble() / flatRateFrames
        val gigabytes = captured * megabytesPerFrame / 1024

        assertTrue(savedFraction > 0.6) {
            "adaptive capture saved only ${"%.0f".format(savedFraction * 100)}% " +
                "against a flat 3fps ($captured vs $flatRateFrames frames)"
        }
        assertTrue(gigabytes < 1.5) { "one visit still cost ${"%.2f".format(gigabytes)}GB" }

        // Arrival and departure must be captured densely: that is where the
        // behaviour is. Only the middle is sampled sparsely.
        assertTrue(movingCaptures >= 25) { "only $movingCaptures frames of movement captured" }
        assertTrue(stationaryCaptures > 200) { "feeding was under-sampled: $stationaryCaptures" }
        assertEquals(2, rateChanges.size) { "expected exactly one drop and one recovery" }
        assertEquals(CaptureMode.STATIONARY, rateChanges[0].to)
        assertEquals(CaptureMode.MOVING, rateChanges[1].to)
    }

    /**
     * The guards have to compose: disk pressure caps the rate, and the cap has to
     * actually reach the capture requests rather than living in a data class
     * nobody consults.
     */
    @Test
    fun `disk pressure reaches the capture rate and the trigger threshold together`() {
        val guards = GuardEvaluator(GuardConfig(), CaptureConfig(), TriggerConfig())
        val degraded = guards.evaluate(freeBytes = 3L * 1024 * 1024 * 1024, batteryPercent = 70, temperatureCelsius = 30f)

        val sm = EventStateMachine(CaptureConfig())
        var now = 0L
        var captured = 0
        repeat(50) { i ->
            captured += sm.onDecision(
                decisionAt(10f + i * 5, i.toLong()), now, maxFps = degraded.maxCaptureFps
            ).count { it is EventAction.CaptureRequested }
            now += 200
        }
        // Ten seconds of continuous motion, capped at 1fps.
        assertTrue(captured in 9..12) { "expected ~10 captures under disk pressure, got $captured" }
        assertTrue(degraded.thresholdMultiplier > 1f)
        assertTrue(degraded.captureAllowed) { "disk pressure degrades, it does not stop" }
    }

    /**
     * A whole quiet day must not fill the disk on its own: with no insects, the
     * trigger should fire on essentially nothing, so the storage cost of an
     * empty deployment is the manifest and nothing else.
     */
    @Test
    fun `an empty scene produces almost no captures over a long run`() {
        val scene = SyntheticScene(seed = 21)
        val trigger = MotionTrigger(TriggerConfig(warmupSeconds = 2), analysisFps = 5)
        val sm = EventStateMachine(CaptureConfig())
        var now = 0L
        var captured = 0
        // 3000 analysis frames is ten minutes at 5fps.
        repeat(3000) {
            val decision = trigger.onFrame(scene.frame())
            captured += sm.onDecision(decision, now).count { it is EventAction.CaptureRequested }
            now += 200
        }
        val perHour = captured * 6
        assertTrue(perHour < 300) {
            "an empty scene would produce $perHour frames/hour " +
                "(${"%.1f".format(perHour * megabytesPerFrame / 1024)}GB/h)"
        }
    }

    private fun decisionAt(x: Float?, index: Long) = dk.biomon.insect.core.trigger.TriggerDecision(
        frameIndex = index,
        wallClockMillis = index * 200,
        blobs = if (x == null) emptyList() else listOf(
            dk.biomon.insect.core.blob.Blob(
                x.toInt() - 3, 47, x.toInt() + 3, 53, 49, x, 50f
            )
        ),
        motion = x != null,
        foregroundFraction = 0.005f,
        forcedRefreshPixels = 0,
        rejectedTooLarge = 0,
        warmingUp = false,
        workWidth = 160,
        workHeight = 120,
    )
}
