package dk.biomon.insect.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import dk.biomon.insect.SessionRecorder
import dk.biomon.insect.core.manifest.PowerSample
import dk.biomon.insect.core.policy.ThermalSeverity
import kotlin.math.abs

/**
 * One power reading. [watts] is null when the numbers do not support an honest
 * estimate rather than being filled in with a guess.
 */
data class PowerReading(
    val batteryPercent: Int,
    /** Microamps, as the platform reports it. See the sign discussion below. */
    val currentMicroAmps: Int,
    val temperatureCelsius: Float,
    val voltageMillivolts: Int,
    val charging: Boolean,
    val watts: Float?,
    /** Platform thermal throttling state; independent of battery temperature. */
    val thermalSeverity: ThermalSeverity = ThermalSeverity.NONE,
    /** Age of the underlying battery broadcast, when it can be determined. */
    val batteryAgeMillis: Long? = null,
)

/**
 * The self-instrumentation, and the only power measurement this rig will ever
 * have.
 *
 * There is no bench test: the deployment *is* the experiment (non-negotiable
 * #1). A sample goes into the manifest every 60s for the whole session, and
 * `analysis/power_report.py` answers the ~1.89W budget question from it
 * afterwards.
 *
 * ## Units and sign, which are the whole job here
 *
 * * `BATTERY_PROPERTY_CURRENT_NOW` is **microamps**. The AOSP contract says
 *   negative while discharging, positive while charging, and Pixels honour it --
 *   but several vendors invert it, and some report milliamps. So the magnitude
 *   is used for the wattage and the *reported sign is preserved verbatim in the
 *   manifest* rather than being normalised away. If a device turns out to be
 *   lying, the raw number is still there to re-derive from.
 * * `EXTRA_TEMPERATURE` is tenths of a degree Celsius.
 * * `EXTRA_VOLTAGE` is millivolts.
 * * Watts are only computed **while discharging**. On external power the current
 *   reading includes the charge going into the battery, which is not the load
 *   and would flatter or wreck the estimate depending on sign. The power bank is
 *   attached for real deployments, so this will usually be null -- and that is
 *   the honest answer, not a missing measurement. The percentage-drop method in
 *   the report script is the fallback, and it needs a run on internal battery.
 */
class PowerLogger(
    private val context: Context,
    private val recorder: SessionRecorder,
) {
    private val batteryManager: BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /**
     * The last battery broadcast we saw, and when we saw it change.
     *
     * The sticky intent is re-read on every sample -- that part was never
     * cached -- but the *platform* only refreshes it when it broadcasts a
     * battery change, and on a phone held at a steady charge that can be never.
     * The first field run logged an identical 28.1C sixteen times. Tracking when
     * the underlying values last moved turns "the temperature is not changing"
     * from a suspicion into a number in the manifest.
     */
    private var lastBatterySignature: String? = null
    private var lastBatteryChangeUptime: Long = 0

    /** Read the battery, write the manifest record, return the reading. */
    fun sample(atMillis: Long = System.currentTimeMillis()): PowerReading {
        val reading = read()
        recorder.record(
            PowerSample(
                atMillis = atMillis,
                batteryPercent = reading.batteryPercent,
                currentMicroAmps = reading.currentMicroAmps,
                temperatureCelsius = reading.temperatureCelsius,
                voltageMillivolts = reading.voltageMillivolts,
                charging = reading.charging,
                freeBytes = recorder.freeBytes(),
                watts = reading.watts,
                thermalStatus = reading.thermalSeverity.name.lowercase(),
                batteryAgeMillis = reading.batteryAgeMillis,
            )
        )
        return reading
    }

    /** Read without writing, for the notification and the UI between samples. */
    fun read(): PowerReading {
        val status = batteryStatus()

        val percent = batteryManager
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
            ?: percentFromIntent(status)

        val currentMicroAmps = batteryManager
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != Int.MIN_VALUE }
            ?: 0

        val temperature = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10f }
            ?: Float.NaN

        val voltage = status?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val chargeStatus = status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = plugged != 0 ||
            chargeStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            chargeStatus == BatteryManager.BATTERY_STATUS_FULL

        val watts = if (!charging && currentMicroAmps != 0 && voltage > 0) {
            abs(currentMicroAmps) * 1e-6f * voltage * 1e-3f
        } else {
            null
        }

        // Track whether the broadcast itself moved, so a frozen temperature is
        // visible as an age rather than inferred from identical numbers.
        val signature = "$percent/$temperature/$voltage/$chargeStatus/$plugged"
        val now = SystemClock.elapsedRealtime()
        if (signature != lastBatterySignature) {
            lastBatterySignature = signature
            lastBatteryChangeUptime = now
        }
        val age = if (lastBatteryChangeUptime > 0) now - lastBatteryChangeUptime else null

        return PowerReading(
            batteryPercent = percent,
            currentMicroAmps = currentMicroAmps,
            temperatureCelsius = temperature,
            voltageMillivolts = voltage,
            charging = charging,
            watts = watts,
            thermalSeverity = thermalSeverity(),
            batteryAgeMillis = age,
        )
    }

    /**
     * `PowerManager.getCurrentThermalStatus()` -- the platform's own view of how
     * hot it is, which updates whether or not a battery broadcast happens. This
     * is what makes the thermal guard trustworthy when battery temperature
     * latches.
     */
    private fun thermalSeverity(): ThermalSeverity {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalSeverity.NONE
        return try {
            when (powerManager?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalSeverity.NONE
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalSeverity.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalSeverity.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalSeverity.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalSeverity.CRITICAL
                else -> ThermalSeverity.NONE
            }
        } catch (t: Throwable) {
            ThermalSeverity.NONE
        }
    }

    /**
     * `ACTION_BATTERY_CHANGED` is sticky, so a null receiver returns the latest
     * broadcast without registering anything. This is genuinely re-read on every
     * sample; what it cannot do is make the platform broadcast more often than
     * it chooses to, which is why [batteryAgeMillis] exists alongside it.
     */
    private fun batteryStatus(): Intent? = try {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (t: Throwable) {
        null
    }

    private fun percentFromIntent(status: Intent?): Int {
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        // -1 rather than 0: an unknown battery level must not look like a flat one
        // and trip the low-battery shutdown.
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }
}
