package dk.biomon.insect.core

import dk.biomon.insect.core.policy.DiskLevel
import dk.biomon.insect.core.policy.GuardEvaluator
import dk.biomon.insect.core.policy.StopReason
import dk.biomon.insect.core.policy.ThermalLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuardEvaluatorTest {

    private val gb = 1024L * 1024 * 1024
    private fun evaluator() = GuardEvaluator(GuardConfig(), CaptureConfig(), TriggerConfig())

    @Test
    fun `plenty of space and a cool battery runs at full rate`() {
        val s = evaluator().evaluate(freeBytes = 20 * gb, batteryPercent = 90, temperatureCelsius = 28f)
        assertEquals(DiskLevel.NORMAL, s.disk)
        assertEquals(ThermalLevel.NOMINAL, s.thermal)
        assertTrue(s.captureAllowed)
        assertEquals(5, s.analysisFps)
        assertEquals(1f, s.thresholdMultiplier)
        assertEquals(StopReason.NONE, s.stopReason)
    }

    @Test
    fun `disk pressure degrades rather than stopping`() {
        val s = evaluator().evaluate(3 * gb, 80, 30f)
        assertEquals(DiskLevel.DEGRADED, s.disk)
        assertTrue(s.captureAllowed) { "degraded must keep capturing, just less" }
        assertEquals(1f, s.maxCaptureFps)
        assertTrue(s.thresholdMultiplier > 1f) { "degraded must become more selective" }
    }

    @Test
    fun `a nearly full disk stops capture with a reason`() {
        val s = evaluator().evaluate(512L * 1024 * 1024, 80, 30f)
        assertEquals(DiskLevel.STOPPED, s.disk)
        assertFalse(s.captureAllowed)
        assertEquals(StopReason.DISK_FULL, s.stopReason)
    }

    @Test
    fun `low battery stops the session cleanly`() {
        val s = evaluator().evaluate(20 * gb, 4, 30f)
        assertFalse(s.captureAllowed)
        assertEquals(StopReason.LOW_BATTERY, s.stopReason)
    }

    @Test
    fun `heat reduces the analysis rate before it stops anything`() {
        val e = evaluator()
        assertEquals(2, e.evaluate(20 * gb, 80, 41f).analysisFps)
        assertEquals(1, e.evaluate(20 * gb, 80, 46f).analysisFps)
        assertTrue(e.evaluate(20 * gb, 80, 46f).captureAllowed) { "hot is a degradation, not a stop" }
        val critical = e.evaluate(20 * gb, 80, 51f)
        assertFalse(critical.captureAllowed)
        assertEquals(StopReason.OVERHEATED, critical.stopReason)
    }

    @Test
    fun `thermal hysteresis stops the level from flapping on a boundary`() {
        val e = evaluator()
        assertEquals(ThermalLevel.NOMINAL, e.evaluate(20 * gb, 80, 39.9f).thermal)
        assertEquals(ThermalLevel.WARN, e.evaluate(20 * gb, 80, 40.1f).thermal)
        // Still WARN inside the hysteresis band...
        assertEquals(ThermalLevel.WARN, e.evaluate(20 * gb, 80, 39f).thermal)
        // ...and only NOMINAL once it has properly cooled.
        assertEquals(ThermalLevel.NOMINAL, e.evaluate(20 * gb, 80, 37.5f).thermal)
    }

    @Test
    fun `every level change is reported so it can reach the manifest`() {
        val e = evaluator()
        e.evaluate(20 * gb, 80, 30f)
        assertTrue(e.lastTransitions.isEmpty())
        e.evaluate(3 * gb, 80, 42f)
        assertEquals(2, e.lastTransitions.size) { "expected disk and thermal transitions: ${e.lastTransitions}" }
        e.evaluate(3 * gb, 80, 42f)
        assertTrue(e.lastTransitions.isEmpty()) { "steady state must not re-log" }
    }

    @Test
    fun `disk full outranks other stop reasons`() {
        val s = evaluator().evaluate(100L * 1024 * 1024, 3, 55f)
        assertEquals(StopReason.DISK_FULL, s.stopReason)
    }
}
