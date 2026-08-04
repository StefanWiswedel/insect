package dk.biomon.insect.core.naming

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Self-describing frame filenames.
 *
 * Assume the session ends abruptly -- a dead battery, a pulled cable, an OS kill
 * -- and that no clean shutdown ever happened. Everything already written must
 * still be interpretable. So the filename alone carries session, event,
 * timestamp and sequence: frames can be grouped into events and ordered in time
 * with nothing but `ls`. The database is a convenience, not a dependency
 * (DESIGN.md 4).
 *
 * Format: `<sessionId>_e<event:5>_<yyyyMMdd'T'HHmmssSSS>Z_f<sequence:5>.jpg`
 * e.g. `040826_0_e00007_20260804T112233456Z_f00042.jpg`
 *
 * The timestamp is UTC, marked with a trailing `Z`, because a session that runs
 * across a DST boundary or is analysed in another timezone must still sort
 * correctly. The session ID keeps the laptop pipeline's existing `DDMMYY_N`
 * convention so exported directories drop straight in.
 */
object FrameNaming {
    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC)
    private val SESSION_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("ddMMyy").withZone(ZoneOffset.UTC)

    const val EXTENSION = ".jpg"

    /** Session ID in the laptop pipeline's convention, e.g. `040826_0`. */
    fun sessionId(date: LocalDate, indexOnDay: Int): String =
        SESSION_DATE.format(date.atStartOfDay(ZoneOffset.UTC)) + "_" + indexOnDay

    fun frameName(
        sessionId: String,
        eventId: Long,
        wallClockMillis: Long,
        sequence: Int,
    ): String = buildString {
        append(sessionId)
        append("_e").append(pad(eventId, 5))
        append('_').append(TIMESTAMP.format(Instant.ofEpochMilli(wallClockMillis))).append('Z')
        append("_f").append(pad(sequence.toLong(), 5))
        append(EXTENSION)
    }

    /** Parse a frame filename back. Returns null if it is not one of ours. */
    fun parse(name: String): FrameName? {
        if (!name.endsWith(EXTENSION)) return null
        val stem = name.substring(0, name.length - EXTENSION.length)
        val parts = stem.split('_')
        if (parts.size < 4) return null
        val seqPart = parts[parts.size - 1]
        val tsPart = parts[parts.size - 2]
        val eventPart = parts[parts.size - 3]
        if (!seqPart.startsWith("f") || !eventPart.startsWith("e")) return null
        if (!tsPart.endsWith("Z")) return null
        val sequence = seqPart.drop(1).toIntOrNull() ?: return null
        val eventId = eventPart.drop(1).toLongOrNull() ?: return null
        val millis = runCatching {
            java.time.LocalDateTime
                .parse(tsPart.dropLast(1), TIMESTAMP)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }.getOrNull() ?: return null
        val sessionId = parts.subList(0, parts.size - 3).joinToString("_")
        if (sessionId.isEmpty()) return null
        return FrameName(sessionId, eventId, millis, sequence)
    }

    private fun pad(value: Long, width: Int): String {
        val s = value.toString()
        return if (s.length >= width) s else "0".repeat(width - s.length) + s
    }
}

data class FrameName(
    val sessionId: String,
    val eventId: Long,
    val wallClockMillis: Long,
    val sequence: Int,
)
