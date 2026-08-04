package dk.biomon.insect.core.policy

import dk.biomon.insect.core.CaptureConfig
import dk.biomon.insect.core.GuardConfig
import dk.biomon.insect.core.TriggerConfig

/** Storage pressure tiers (DESIGN.md 4). */
enum class DiskLevel { NORMAL, DEGRADED, STOPPED }

/** Battery-temperature tiers. */
enum class ThermalLevel { NOMINAL, WARN, HOT, CRITICAL }

/** Why the session should stop, when it should. */
enum class StopReason { NONE, DISK_FULL, LOW_BATTERY, OVERHEATED }

/**
 * The guards' combined verdict, recomputed whenever battery or storage is
 * sampled.
 */
data class GuardState(
    val disk: DiskLevel,
    val thermal: ThermalLevel,
    val batteryPercent: Int,
    val freeBytes: Long,
    val temperatureCelsius: Float,
    /** False when capture must stop. The service stays alive regardless. */
    val captureAllowed: Boolean,
    /** Ceiling on capture rate, in fps. */
    val maxCaptureFps: Float,
    /** Analysis stream framerate after thermal backoff. */
    val analysisFps: Int,
    /** Multiplier applied to the trigger threshold to become more selective. */
    val thresholdMultiplier: Float,
    val stopReason: StopReason,
) {
    /** A one-line summary for the manifest and the notification. */
    fun describe(): String = buildString {
        append("disk=").append(disk).append('/')
        append(freeBytes / (1024 * 1024)).append("MB")
        append(" thermal=").append(thermal).append('/')
        append(String.format(java.util.Locale.US, "%.1fC", temperatureCelsius))
        append(" batt=").append(batteryPercent).append('%')
        append(" capture=").append(if (captureAllowed) "on" else "off")
        append(" maxFps=").append(maxCaptureFps)
        append(" analysisFps=").append(analysisFps)
        append(" thr=x").append(thresholdMultiplier)
    }
}

/**
 * Evaluates disk, battery and thermal state into a [GuardState].
 *
 * Degradation is always preferred to stopping, and stopping is always preferred
 * to crashing: a degraded session is better than a lost one, and a session that
 * records why it ended is data where one that simply stops is an evening of
 * forensics. Every level change is returned so the caller can write it to the
 * manifest -- nothing degrades silently (non-negotiable #3).
 *
 * Thermal transitions carry hysteresis so a temperature sitting on a boundary
 * cannot flap the analysis framerate and fill the manifest with noise.
 */
class GuardEvaluator(
    private val guards: GuardConfig,
    private val capture: CaptureConfig,
    private val trigger: TriggerConfig,
    /** Degrees of hysteresis applied when cooling back down. */
    private val thermalHysteresis: Float = 2f,
) {
    private var lastThermal = ThermalLevel.NOMINAL
    private var lastDisk = DiskLevel.NORMAL

    /** Level changes since the previous evaluation, ready for the manifest. */
    var lastTransitions: List<String> = emptyList()
        private set

    fun evaluate(freeBytes: Long, batteryPercent: Int, temperatureCelsius: Float): GuardState {
        val transitions = ArrayList<String>(2)

        val disk = when {
            freeBytes < guards.stopFreeBytes -> DiskLevel.STOPPED
            freeBytes < guards.degradeFreeBytes -> DiskLevel.DEGRADED
            else -> DiskLevel.NORMAL
        }
        if (disk != lastDisk) {
            transitions += "disk ${lastDisk} -> $disk (${freeBytes / (1024 * 1024)}MB free)"
            lastDisk = disk
        }

        val thermal = thermalLevel(temperatureCelsius)
        if (thermal != lastThermal) {
            transitions += "thermal ${lastThermal} -> $thermal " +
                String.format(java.util.Locale.US, "(%.1fC)", temperatureCelsius)
            lastThermal = thermal
        }

        val lowBattery = batteryPercent in 0..guards.lowBatteryStopPercent
        val stopReason = when {
            disk == DiskLevel.STOPPED -> StopReason.DISK_FULL
            lowBattery -> StopReason.LOW_BATTERY
            thermal == ThermalLevel.CRITICAL -> StopReason.OVERHEATED
            else -> StopReason.NONE
        }

        val maxFps = when (disk) {
            DiskLevel.NORMAL -> capture.movingFps
            DiskLevel.DEGRADED -> guards.degradedMaxFps
            DiskLevel.STOPPED -> 0f
        }
        val analysisFps = when (thermal) {
            ThermalLevel.NOMINAL -> capture.analysisFps
            ThermalLevel.WARN -> (capture.analysisFps / 2).coerceAtLeast(2)
            ThermalLevel.HOT -> 1
            ThermalLevel.CRITICAL -> 1
        }
        val multiplier = when (disk) {
            DiskLevel.DEGRADED -> trigger.diskPressureThresholdMultiplier
            else -> 1f
        }

        lastTransitions = transitions
        return GuardState(
            disk = disk,
            thermal = thermal,
            batteryPercent = batteryPercent,
            freeBytes = freeBytes,
            temperatureCelsius = temperatureCelsius,
            captureAllowed = stopReason == StopReason.NONE,
            maxCaptureFps = maxFps,
            analysisFps = analysisFps,
            thresholdMultiplier = multiplier,
            stopReason = stopReason,
        )
    }

    private fun thermalLevel(t: Float): ThermalLevel {
        // Rising uses the configured thresholds; falling has to clear them by
        // [thermalHysteresis] before the level drops back.
        val h = thermalHysteresis
        fun crossed(threshold: Float, level: ThermalLevel): Boolean =
            if (lastThermal >= level) t >= threshold - h else t >= threshold
        return when {
            crossed(guards.thermalCriticalCelsius, ThermalLevel.CRITICAL) -> ThermalLevel.CRITICAL
            crossed(guards.thermalHotCelsius, ThermalLevel.HOT) -> ThermalLevel.HOT
            crossed(guards.thermalWarnCelsius, ThermalLevel.WARN) -> ThermalLevel.WARN
            else -> ThermalLevel.NOMINAL
        }
    }
}
