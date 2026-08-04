package dk.biomon.insect.core

import dk.biomon.insect.core.blob.Blob
import dk.biomon.insect.core.manifest.Degradation
import dk.biomon.insect.core.manifest.FrameWritten
import dk.biomon.insect.core.manifest.PowerSample
import dk.biomon.insect.core.naming.FrameNaming
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NamingAndManifestTest {

    @Test
    fun `session ids keep the laptop pipeline's convention`() {
        assertEquals("040826_0", FrameNaming.sessionId(LocalDate.of(2026, 8, 4), 0))
        assertEquals("311226_2", FrameNaming.sessionId(LocalDate.of(2026, 12, 31), 2))
    }

    @Test
    fun `frame names round-trip`() {
        val name = FrameNaming.frameName("040826_0", 7, 1_754_305_353_456L, 42)
        val parsed = FrameNaming.parse(name)!!
        assertEquals("040826_0", parsed.sessionId)
        assertEquals(7L, parsed.eventId)
        assertEquals(1_754_305_353_456L, parsed.wallClockMillis)
        assertEquals(42, parsed.sequence)
    }

    /**
     * If the database is lost entirely, frames must still group into events and
     * order in time by filename alone (DESIGN.md 4).
     */
    @Test
    fun `lexicographic order is event order then time order`() {
        val base = 1_754_305_353_456L
        val names = listOf(
            FrameNaming.frameName("040826_0", 2, base + 5_000, 0),
            FrameNaming.frameName("040826_0", 1, base + 1_000, 3),
            FrameNaming.frameName("040826_0", 1, base, 1),
            FrameNaming.frameName("040826_0", 10, base + 9_000, 0),
        )
        val sorted = names.sorted().map { FrameNaming.parse(it)!! }
        assertEquals(listOf(1L, 1L, 2L, 10L), sorted.map { it.eventId })
        assertTrue(sorted[0].wallClockMillis < sorted[1].wallClockMillis)
    }

    @Test
    fun `foreign filenames parse to null rather than throwing`() {
        assertNull(FrameNaming.parse("IMG_0001.jpg"))
        assertNull(FrameNaming.parse("040826_0_e00007_notatimestamp_f00042.jpg"))
        assertNull(FrameNaming.parse("040826_0_e00007_20260804T112233456Z_f00042.txt"))
        assertNull(FrameNaming.parse("e00007_20260804T112233456Z_f00042.jpg"))
    }

    @Test
    fun `power samples serialise every field the deployment has to answer`() {
        val json = PowerSample(
            atMillis = 1_754_305_353_456L,
            batteryPercent = 87,
            currentMicroAmps = -412_000,
            temperatureCelsius = 31.4f,
            voltageMillivolts = 3_912,
            charging = false,
            freeBytes = 21_474_836_480L,
            watts = 1.61f,
        ).toJsonLine()
        assertTrue(json.startsWith("{\"type\":\"power\","))
        for (key in listOf("battery_pct", "current_ua", "temp_c", "voltage_mv", "watts", "free_bytes")) {
            assertTrue(json.contains("\"$key\":")) { "missing $key in $json" }
        }
        assertTrue(json.contains("\"watts\":1.61")) { json }
        assertFalse(json.contains("\n"))
    }

    @Test
    fun `frame records carry bounding boxes in full-resolution coordinates`() {
        val json = FrameWritten(
            atMillis = 1L,
            eventId = 3,
            sequence = 12,
            filename = "040826_0_e00003_20260804T112233456Z_f00012.jpg",
            mode = "moving",
            bytes = 4_112_233,
            blobs = listOf(Blob(100, 200, 180, 260, 900, 140f, 230f)),
        ).toJsonLine()
        assertTrue(json.contains("\"boxes\":[[100,200,180,260,900]]")) { json }
    }

    @Test
    fun `focus changes record both ends and the implied distance`() {
        val json = dk.biomon.insect.core.manifest.FocusChanged(1L, 5.0f, 4.0f).toJsonLine()
        assertTrue(json.contains("\"from_diopters\":5")) { json }
        assertTrue(json.contains("\"to_diopters\":4")) { json }
        // 4 dioptres is 25cm; the reader should not have to do the arithmetic.
        assertTrue(json.contains("\"to_cm\":25")) { json }
    }

    @Test
    fun `focus at infinity does not divide by zero`() {
        val json = dk.biomon.insect.core.manifest.FocusChanged(1L, 5.0f, 0.0f).toJsonLine()
        assertFalse(json.contains("to_cm")) { json }
    }

    @Test
    fun `manifest lines never break the one-record-per-line contract`() {
        val json = Degradation(1L, "thermal", "backoff\nto 1fps\ttab \"quoted\"").toJsonLine()
        assertFalse(json.contains('\n'))
        assertFalse(json.contains('\t'))
        assertTrue(json.contains("\\n"))
        assertTrue(json.contains("\\\""))
    }
}
