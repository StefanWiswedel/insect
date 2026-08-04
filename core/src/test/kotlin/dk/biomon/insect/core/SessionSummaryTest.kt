package dk.biomon.insect.core

import dk.biomon.insect.core.blob.Blob
import dk.biomon.insect.core.manifest.Degradation
import dk.biomon.insect.core.manifest.ErrorRecord
import dk.biomon.insect.core.manifest.EventEnded
import dk.biomon.insect.core.manifest.EventStarted
import dk.biomon.insect.core.manifest.FocusChanged
import dk.biomon.insect.core.manifest.FrameWritten
import dk.biomon.insect.core.manifest.PowerSample
import dk.biomon.insect.core.manifest.SessionEnd
import dk.biomon.insect.core.manifest.SessionStart
import dk.biomon.insect.core.report.SessionSummary
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionSummaryTest {

    private val t0 = 1_754_305_353_000L

    private fun started() = SessionStart(
        atMillis = t0,
        sessionId = "040826_0",
        appVersion = "0.1.0",
        device = "Google Pixel 9a",
        androidRelease = "15",
        cameraId = "0",
        lensDescription = "focus=3.23D ois=off awb=locked ae=on q85",
        focusDistanceDiopters = 3.23f,
        analysisWidth = 640,
        analysisHeight = 480,
        captureWidth = 4080,
        captureHeight = 3072,
        jpegQuality = 85,
        configJson = "{}",
    )

    private fun power(offsetMs: Long, pct: Int, temp: Float) = PowerSample(
        atMillis = t0 + offsetMs,
        batteryPercent = pct,
        currentMicroAmps = -420_000,
        temperatureCelsius = temp,
        voltageMillivolts = 3_900,
        charging = false,
        freeBytes = 20_000_000_000L,
        watts = 1.6f,
    )

    @Test
    fun `a summary is renderable before the session ends`() {
        val s = SessionSummary()
        s.observe(started())
        s.observe(power(60_000, 98, 31.0f))
        val md = s.render(t0 + 120_000)

        assertTrue(md.contains("040826_0"))
        assertTrue(md.contains("Google Pixel 9a"))
        // The key property: it says plainly that it is not a finished record.
        assertTrue(md.contains("Session still open, or ended without warning")) { md }
        assertTrue(md.contains("none recorded")) { md }
    }

    @Test
    fun `totals, battery and temperature aggregate across the session`() {
        val s = SessionSummary()
        s.observe(started())
        s.observe(power(60_000, 100, 28.0f))
        s.observe(power(120_000, 90, 42.0f))
        s.observe(power(180_000, 80, 35.0f))
        s.observe(EventStarted(t0 + 1_000, 1))
        s.observe(
            FrameWritten(
                t0 + 1_100, 1, 0, "a.jpg", "moving", 4_000_000,
                listOf(Blob(0, 0, 10, 10, 121, 5f, 5f)),
            )
        )
        s.observe(
            FrameWritten(
                t0 + 1_400, 1, 1, "b.jpg", "moving", 4_200_000,
                listOf(Blob(0, 0, 20, 20, 441, 10f, 10f)),
            )
        )
        s.observe(EventEnded(t0 + 9_000, 1, 2, "motion_ceased", 8_000))

        val md = s.render()

        assertTrue(md.contains("**Battery start**: 100%")) { md }
        assertTrue(md.contains("**Battery end**: 80%")) { md }
        assertTrue(md.contains("**Battery minimum**: 80%")) { md }
        assertTrue(md.contains("**Temperature min**: 28.0 C")) { md }
        assertTrue(md.contains("**Temperature max**: 42.0 C")) { md }
        assertTrue(md.contains("**Temperature mean**: 35.0 C")) { md }
        assertTrue(md.contains("**Frames**: 2")) { md }
        assertTrue(md.contains("**Events**: 1")) { md }
        assertTrue(md.contains("8.20 MB")) { md }
        // Peak blob area is the largest seen in the event, not the last.
        assertTrue(md.contains("| 441 |")) { md }
    }

    @Test
    fun `degradations, errors and focus changes are listed with timestamps`() {
        val s = SessionSummary()
        s.observe(started())
        s.observe(Degradation(t0 + 3_600_000, "thermal", "thermal NOMINAL -> REDUCED (40.2C)"))
        s.observe(ErrorRecord(t0 + 7_200_000, "camera", "camera error 3; rebuilding", true))
        s.observe(FocusChanged(t0 + 30_000, 5.0f, 3.23f))

        val md = s.render()

        assertTrue(md.contains("thermal NOMINAL -> REDUCED")) { md }
        assertTrue(md.contains("(recovered) camera")) { md }
        assertTrue(md.contains("3.23 D (31 cm)")) { md }
        assertTrue(md.contains("5.00 D (20 cm) -> 3.23 D (31 cm)")) { md }
    }

    @Test
    fun `a clean end records the reason and stops calling itself open`() {
        val s = SessionSummary()
        s.observe(started())
        s.observe(SessionEnd(t0 + 32_400_000, "low_battery", 12, 3400, 14_000_000_000L, 32_400_000))
        val md = s.render()

        assertFalse(md.contains("Session still open")) { md }
        assertTrue(md.contains("**Termination**: low_battery")) { md }
        assertTrue(md.contains("9h 00m 00s")) { md }
        assertTrue(md.contains("14.00 GB")) { md }
    }

    @Test
    fun `an empty session says so rather than rendering blank sections`() {
        val s = SessionSummary()
        s.observe(started())
        val md = s.render(t0 + 1000)
        assertTrue(md.contains("No events. Nothing triggered the motion detector.")) { md }
        assertTrue(md.contains("None. The session ran at full rate throughout.")) { md }
    }

    @Test
    fun `an event still open is shown as open rather than as zero duration`() {
        val s = SessionSummary()
        s.observe(started())
        s.observe(EventStarted(t0 + 5_000, 7))
        s.observe(
            FrameWritten(t0 + 5_100, 7, 0, "x.jpg", "moving", 4_000_000, emptyList())
        )
        val md = s.render(t0 + 6_000)
        assertTrue(md.contains("| 7 |")) { md }
        assertTrue(md.contains("| open |")) { md }
    }
}
