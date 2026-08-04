package dk.biomon.insect.core.event

import dk.biomon.insect.core.CaptureConfig
import dk.biomon.insect.core.blob.Blob
import dk.biomon.insect.core.trigger.TriggerDecision
import kotlin.math.hypot

/** Capture rate mode within an event. */
enum class CaptureMode { MOVING, STATIONARY }

/** Why an event stopped. Always recorded -- nothing is silently dropped. */
enum class EventEndReason {
    MOTION_CEASED,
    FRAME_CAP,
    DISK_STOP,
    THERMAL_STOP,
    LOW_BATTERY,
    SESSION_STOP,
    CAMERA_ERROR,
}

/** Instructions the pipeline should act on, in order. */
sealed interface EventAction {
    data class EventStarted(val eventId: Long, val startMillis: Long) : EventAction

    /** Request one full-resolution JPEG. [blobs] are the boxes to record with it. */
    data class CaptureRequested(
        val eventId: Long,
        val sequence: Int,
        val mode: CaptureMode,
        val requestedAtMillis: Long,
        val blobs: List<Blob>,
    ) : EventAction

    /**
     * The capture rate changed within an event. Logged so that frame intervals
     * are reconstructable downstream and a 1fps stretch is not mistaken for
     * dropped frames (DESIGN.md 3.2).
     */
    data class RateChanged(
        val eventId: Long,
        val from: CaptureMode,
        val to: CaptureMode,
        val atMillis: Long,
        val effectiveFps: Float,
    ) : EventAction

    data class EventEnded(
        val eventId: Long,
        val endMillis: Long,
        val frameCount: Int,
        val reason: EventEndReason,
    ) : EventAction
}

/**
 * Turns a stream of [TriggerDecision]s into events and capture requests.
 *
 * Two things it exists to get right:
 *
 * * **Motion-adaptive rate.** Storage is the binding constraint -- ~30GB free
 *   and ~4MB a frame means a flat 3fps buys about 40 minutes of triggered
 *   capture for a whole day. So capture runs at full rate while the blob moves
 *   and drops to 1fps while it sits still feeding, which is where the frames are
 *   redundant, and returns to full rate the moment it moves again. Arrival,
 *   movement and departure all survive; only the stationary stretch is sampled
 *   sparsely.
 * * **One event, not many.** Continued motion extends the current event rather
 *   than starting a new one, and capture continues through
 *   [CaptureConfig.postRollMillis] of stillness before the event closes.
 *
 * Single-threaded, driven from the analysis thread.
 */
class EventStateMachine(
    private val config: CaptureConfig,
    private val clockStartEventId: Long = 1,
) {
    private var nextEventId = clockStartEventId

    private var activeEventId: Long = -1
    private var frameCount = 0
    private var sequence = 0
    private var mode = CaptureMode.MOVING
    private var stationaryStreak = 0
    private var lastCentroidX = Float.NaN
    private var lastCentroidY = Float.NaN
    private var lastMotionMillis = 0L
    private var nextCaptureDueMillis = 0L
    /** Set when an event hit the frame cap: no new event until motion actually stops. */
    private var suppressedUntilQuiet = false

    val isActive: Boolean get() = activeEventId >= 0
    val currentEventId: Long get() = activeEventId
    val currentMode: CaptureMode get() = mode
    val framesInEvent: Int get() = frameCount

    /**
     * Advance the machine by one analysis frame.
     *
     * @param maxFps ceiling imposed by the disk guard and thermal policy. The
     *   effective rate is the minimum of this and the mode's own rate.
     * @param captureAllowed false when a guard has stopped capture entirely; an
     *   open event is closed with [stopReason].
     */
    fun onDecision(
        decision: TriggerDecision,
        nowMillis: Long,
        maxFps: Float = Float.MAX_VALUE,
        captureAllowed: Boolean = true,
        stopReason: EventEndReason = EventEndReason.DISK_STOP,
    ): List<EventAction> {
        val actions = ArrayList<EventAction>(3)

        if (!captureAllowed) {
            if (isActive) actions += endEvent(nowMillis, stopReason)
            return actions
        }

        val blobs = decision.blobs
        val motion = decision.motion

        if (motion) {
            lastMotionMillis = nowMillis
            if (suppressedUntilQuiet) return actions
            if (!isActive) {
                startEvent(nowMillis)
                actions += EventAction.EventStarted(activeEventId, nowMillis)
            }
            actions += updateMode(blobs.first(), nowMillis, maxFps)
        } else {
            if (suppressedUntilQuiet && nowMillis - lastMotionMillis >= config.postRollMillis) {
                suppressedUntilQuiet = false
            }
            if (!isActive) return actions
            if (nowMillis - lastMotionMillis >= config.postRollMillis) {
                actions += endEvent(nowMillis, EventEndReason.MOTION_CEASED)
                return actions
            }
        }

        // Post-roll keeps capturing at the last known rate.
        if (isActive && nowMillis >= nextCaptureDueMillis) {
            val fps = effectiveFps(maxFps)
            actions += EventAction.CaptureRequested(
                eventId = activeEventId,
                sequence = sequence++,
                mode = mode,
                requestedAtMillis = nowMillis,
                blobs = blobs,
            )
            frameCount++
            val interval = (1000f / fps).toLong().coerceAtLeast(1)
            // Re-anchor rather than accumulate when we have fallen behind, so a
            // stall does not produce a burst of catch-up captures.
            nextCaptureDueMillis =
                if (nextCaptureDueMillis + interval < nowMillis) nowMillis + interval
                else nextCaptureDueMillis + interval

            if (frameCount >= config.maxFramesPerEvent) {
                actions += endEvent(nowMillis, EventEndReason.FRAME_CAP)
                suppressedUntilQuiet = true
            }
        }
        return actions
    }

    /** Close any open event, e.g. at session end or on a camera failure. */
    fun close(nowMillis: Long, reason: EventEndReason): List<EventAction> =
        if (isActive) listOf(endEvent(nowMillis, reason)) else emptyList()

    private fun startEvent(nowMillis: Long) {
        activeEventId = nextEventId++
        frameCount = 0
        sequence = 0
        mode = CaptureMode.MOVING
        stationaryStreak = 0
        lastCentroidX = Float.NaN
        lastCentroidY = Float.NaN
        nextCaptureDueMillis = nowMillis
    }

    private fun endEvent(nowMillis: Long, reason: EventEndReason): EventAction.EventEnded {
        val action = EventAction.EventEnded(activeEventId, nowMillis, frameCount, reason)
        activeEventId = -1
        frameCount = 0
        return action
    }

    private fun updateMode(blob: Blob, nowMillis: Long, maxFps: Float): List<EventAction> {
        val displacement = if (lastCentroidX.isNaN()) {
            Float.MAX_VALUE
        } else {
            hypot(blob.centroidX - lastCentroidX, blob.centroidY - lastCentroidY)
        }
        lastCentroidX = blob.centroidX
        lastCentroidY = blob.centroidY

        val previous = mode
        if (displacement < config.stationaryDisplacementPx) {
            stationaryStreak++
            if (mode == CaptureMode.MOVING && stationaryStreak >= config.stationaryFrames) {
                mode = CaptureMode.STATIONARY
            }
        } else {
            stationaryStreak = 0
            if (mode == CaptureMode.STATIONARY) {
                // Resumed movement: back to full rate immediately, do not wait
                // for the stationary-rate interval to expire.
                mode = CaptureMode.MOVING
                nextCaptureDueMillis = nowMillis
            }
        }
        return if (mode != previous) {
            listOf(
                EventAction.RateChanged(
                    eventId = activeEventId,
                    from = previous,
                    to = mode,
                    atMillis = nowMillis,
                    effectiveFps = effectiveFps(maxFps),
                )
            )
        } else {
            emptyList()
        }
    }

    private fun effectiveFps(maxFps: Float): Float {
        val modeFps = when (mode) {
            CaptureMode.MOVING -> config.movingFps
            CaptureMode.STATIONARY -> config.stationaryFps
        }
        return minOf(modeFps, maxFps).coerceAtLeast(0.05f)
    }
}
