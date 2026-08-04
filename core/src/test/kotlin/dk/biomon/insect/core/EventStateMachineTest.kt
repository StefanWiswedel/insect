package dk.biomon.insect.core

import dk.biomon.insect.core.blob.Blob
import dk.biomon.insect.core.event.CaptureMode
import dk.biomon.insect.core.event.EventAction
import dk.biomon.insect.core.event.EventEndReason
import dk.biomon.insect.core.event.EventStateMachine
import dk.biomon.insect.core.trigger.TriggerDecision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventStateMachineTest {

    private fun decision(x: Float?, y: Float = 50f, index: Long = 0): TriggerDecision {
        val blobs = if (x == null) emptyList() else listOf(
            Blob(x.toInt() - 2, y.toInt() - 2, x.toInt() + 2, y.toInt() + 2, 25, x, y)
        )
        return TriggerDecision(
            frameIndex = index,
            wallClockMillis = index * 200,
            blobs = blobs,
            motion = blobs.isNotEmpty(),
            foregroundFraction = 0.01f,
            forcedRefreshPixels = 0,
            rejectedTooLarge = 0,
            warmingUp = false,
            workWidth = 160,
            workHeight = 120,
        )
    }

    private inline fun <reified T> List<EventAction>.of(): List<T> = filterIsInstance<T>()

    @Test
    fun `motion opens an event and captures at the moving rate`() {
        val config = CaptureConfig(movingFps = 4f)
        val sm = EventStateMachine(config)
        var now = 0L
        var captures = 0
        var started = 0
        // One second of steady movement at 5fps analysis.
        repeat(5) { i ->
            val actions = sm.onDecision(decision(10f + i * 10, index = i.toLong()), now)
            started += actions.of<EventAction.EventStarted>().size
            captures += actions.of<EventAction.CaptureRequested>().size
            now += 200
        }
        assertEquals(1, started)
        // 4fps over 1000ms, first capture at t=0: t=0, 250, 500, 750 -> analysis
        // frames land at 0, 200, 400, 600, 800, so 0/400/600/800 fire.
        assertTrue(captures in 3..5) { "expected ~4 captures in 1s at 4fps, got $captures" }
    }

    @Test
    fun `a stationary blob drops to the stationary rate and back up on movement`() {
        val config = CaptureConfig(movingFps = 4f, stationaryFps = 1f, stationaryFrames = 3)
        val sm = EventStateMachine(config)
        var now = 0L
        val rateChanges = mutableListOf<EventAction.RateChanged>()
        var stationaryCaptures = 0

        // Arrival: moving.
        repeat(4) { i ->
            sm.onDecision(decision(10f + i * 10, index = i.toLong()), now)
            now += 200
        }
        assertEquals(CaptureMode.MOVING, sm.currentMode)

        // Feeding: parked at the same centroid for 5 seconds.
        repeat(25) { i ->
            val actions = sm.onDecision(decision(50f, index = 100L + i), now)
            rateChanges += actions.of<EventAction.RateChanged>()
            stationaryCaptures += actions.of<EventAction.CaptureRequested>()
                .count { it.mode == CaptureMode.STATIONARY }
            now += 200
        }
        assertEquals(CaptureMode.STATIONARY, sm.currentMode)
        assertEquals(1, rateChanges.size)
        assertEquals(CaptureMode.STATIONARY, rateChanges[0].to)
        // ~5 seconds at 1fps, not ~20 frames at 4fps.
        assertTrue(stationaryCaptures in 3..6) {
            "expected ~5 stationary captures, got $stationaryCaptures"
        }

        // Departure: moves again, and the rate must recover on the very next frame.
        val resume = sm.onDecision(decision(90f, index = 200), now)
        assertEquals(CaptureMode.MOVING, sm.currentMode)
        assertEquals(1, resume.of<EventAction.RateChanged>().size)
        assertEquals(1, resume.of<EventAction.CaptureRequested>().size) {
            "resumed movement must capture immediately, not wait out the 1fps interval"
        }
    }

    @Test
    fun `post-roll keeps capturing after motion ceases and then closes the event`() {
        val config = CaptureConfig(postRollMillis = 1_000, movingFps = 4f)
        val sm = EventStateMachine(config)
        var now = 0L
        sm.onDecision(decision(20f), now)
        now += 200

        var postRollCaptures = 0
        var ended: EventAction.EventEnded? = null
        repeat(10) { i ->
            val actions = sm.onDecision(decision(null, index = i.toLong()), now)
            postRollCaptures += actions.of<EventAction.CaptureRequested>().size
            ended = ended ?: actions.of<EventAction.EventEnded>().firstOrNull()
            now += 200
        }
        assertTrue(postRollCaptures >= 2) { "post-roll captured nothing" }
        assertEquals(EventEndReason.MOTION_CEASED, ended?.reason)
    }

    @Test
    fun `continued motion extends one event rather than starting several`() {
        val config = CaptureConfig(postRollMillis = 1_000)
        val sm = EventStateMachine(config)
        var now = 0L
        var starts = 0
        repeat(50) { i ->
            // Motion every other frame: gaps well inside the post-roll window.
            val d = if (i % 2 == 0) decision(10f + i, index = i.toLong()) else decision(null, index = i.toLong())
            starts += sm.onDecision(d, now).of<EventAction.EventStarted>().size
            now += 200
        }
        assertEquals(1, starts)
    }

    @Test
    fun `the frame cap ends the event and suppresses restarts until motion stops`() {
        val config = CaptureConfig(maxFramesPerEvent = 5, movingFps = 5f, postRollMillis = 1_000)
        val sm = EventStateMachine(config)
        var now = 0L
        var ends = mutableListOf<EventAction.EventEnded>()
        var starts = 0
        repeat(40) { i ->
            val actions = sm.onDecision(decision(10f + i * 5, index = i.toLong()), now)
            ends += actions.of<EventAction.EventEnded>()
            starts += actions.of<EventAction.EventStarted>().size
            now += 200
        }
        assertEquals(1, ends.size)
        assertEquals(EventEndReason.FRAME_CAP, ends[0].reason)
        assertEquals(5, ends[0].frameCount)
        assertEquals(1, starts) { "suppressed state must not restart while motion continues" }

        // Motion stops for longer than the post-roll, then returns: new event.
        repeat(10) {
            sm.onDecision(decision(null), now)
            now += 200
        }
        val restart = sm.onDecision(decision(30f), now)
        assertEquals(1, restart.of<EventAction.EventStarted>().size)
    }

    @Test
    fun `a guard stop closes the open event with its reason`() {
        val sm = EventStateMachine(CaptureConfig())
        sm.onDecision(decision(20f), 0)
        val actions = sm.onDecision(
            decision(20f), 200, captureAllowed = false, stopReason = EventEndReason.DISK_STOP
        )
        assertEquals(EventEndReason.DISK_STOP, actions.of<EventAction.EventEnded>().single().reason)
        assertTrue(!sm.isActive)
    }

    @Test
    fun `the disk guard rate ceiling overrides the moving rate`() {
        val config = CaptureConfig(movingFps = 4f)
        val sm = EventStateMachine(config)
        var now = 0L
        var captures = 0
        repeat(25) { i ->
            captures += sm.onDecision(decision(10f + i * 10, index = i.toLong()), now, maxFps = 1f)
                .of<EventAction.CaptureRequested>().size
            now += 200
        }
        // 5 seconds capped at 1fps.
        assertTrue(captures in 4..7) { "expected ~5 captures under a 1fps cap, got $captures" }
    }

    @Test
    fun `a stall does not produce a burst of catch-up captures`() {
        val sm = EventStateMachine(CaptureConfig(movingFps = 4f))
        sm.onDecision(decision(10f), 0)
        // Analysis thread stalls for 10 seconds, then resumes.
        val actions = sm.onDecision(decision(80f), 10_000)
        assertEquals(1, actions.of<EventAction.CaptureRequested>().size)
        val next = sm.onDecision(decision(120f), 10_100)
        assertEquals(0, next.of<EventAction.CaptureRequested>().size)
    }
}
