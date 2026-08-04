package dk.biomon.insect.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dk.biomon.insect.SessionRecorder
import dk.biomon.insect.core.manifest.PowerSample
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

        return PowerReading(
            batteryPercent = percent,
            currentMicroAmps = currentMicroAmps,
            temperatureCelsius = temperature,
            voltageMillivolts = voltage,
            charging = charging,
            watts = watts,
        )
    }

    /**
     * `ACTION_BATTERY_CHANGED` is sticky, so a null receiver returns the last
     * broadcast without registering anything. Cheap enough to call once a minute,
     * and it avoids holding a receiver across a nine-hour session.
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
