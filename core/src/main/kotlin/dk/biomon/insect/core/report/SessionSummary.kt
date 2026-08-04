package dk.biomon.insect.core.report

import dk.biomon.insect.core.manifest.Degradation
import dk.biomon.insect.core.manifest.ErrorRecord
import dk.biomon.insect.core.manifest.EventEnded
import dk.biomon.insect.core.manifest.EventStarted
import dk.biomon.insect.core.manifest.FocusChanged
import dk.biomon.insect.core.manifest.FrameWritten
import dk.biomon.insect.core.manifest.ManifestRecord
import dk.biomon.insect.core.manifest.PowerSample
import dk.biomon.insect.core.manifest.SessionEnd
import dk.biomon.insect.core.manifest.SessionStart
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A human-readable session summary, accumulated as the session runs.
 *
 * Two rules shape it. It must be **pasteable into a chat and understandable
 * cold**, by someone who was not there and does not have the manifest in front
 * of them. And it must be **written incrementally**, because sessions end
 * abruptly by default -- a summary composed at session end is a summary that
 * does not exist for exactly the deployments you most want to explain.
 *
 * So this holds running aggregates rather than a record log, and [render] can be
 * called at any moment to produce the file as it stands. The cost is that the
 * file is rewritten rather than appended; at a few kilobytes that is cheaper
 * than the JPEG being written beside it.
 *
 * Not thread-safe; the recorder serialises access.
 */
class SessionSummary {

    private var sessionId: String = "unknown"
    private var startMillis: Long = 0
    private var device: String = "unknown"
    private var androidRelease: String = ""
    private var appVersion: String = ""
    private var focusDiopters: Float = 0f
    private var captureWidth = 0
    private var captureHeight = 0
    private var analysisWidth = 0
    private var analysisHeight = 0
    private var jpegQuality = 0

    private var events = 0L
    private var frames = 0L
    private var bytes = 0L
    private var lastActivityMillis = 0L

    private var batteryStart = -1
    private var batteryEnd = -1
    private var batteryMin = Int.MAX_VALUE
    private var tempMin = Float.MAX_VALUE
    private var tempMax = -Float.MAX_VALUE
    private var tempSum = 0.0
    private var tempCount = 0
    private var powerSamples = 0

    private val degradations = ArrayList<Pair<Long, String>>()
    private val errors = ArrayList<Triple<Long, String, Boolean>>()
    private val focusChanges = ArrayList<Triple<Long, Float, Float>>()
    private val eventRows = LinkedHashMap<Long, EventRow>()

    private var endMillis: Long = 0
    private var endReason: String? = null

    private class EventRow(val startMillis: Long) {
        var durationMillis: Long = 0
        var frames: Int = 0
        var peakBlobArea: Int = 0
        var ended = false
    }

    /** Feed every manifest record through here as it is written. */
    fun observe(record: ManifestRecord) {
        lastActivityMillis = maxOf(lastActivityMillis, record.atMillis)
        when (record) {
            is SessionStart -> {
                sessionId = record.sessionId
                startMillis = record.atMillis
                device = record.device
                androidRelease = record.androidRelease
                appVersion = record.appVersion
                focusDiopters = record.focusDistanceDiopters
                captureWidth = record.captureWidth
                captureHeight = record.captureHeight
                analysisWidth = record.analysisWidth
                analysisHeight = record.analysisHeight
                jpegQuality = record.jpegQuality
            }

            is PowerSample -> {
                powerSamples++
                if (record.batteryPercent >= 0) {
                    if (batteryStart < 0) batteryStart = record.batteryPercent
                    batteryEnd = record.batteryPercent
                    if (record.batteryPercent < batteryMin) batteryMin = record.batteryPercent
                }
                val t = record.temperatureCelsius
                if (t.isFinite() && t > -100f) {
                    if (t < tempMin) tempMin = t
                    if (t > tempMax) tempMax = t
                    tempSum += t
                    tempCount++
                }
            }

            is EventStarted -> {
                events++
                eventRows[record.eventId] = EventRow(record.atMillis)
            }

            is EventEnded -> {
                val row = eventRows.getOrPut(record.eventId) { EventRow(record.atMillis) }
                row.durationMillis = record.durationMillis
                row.frames = record.frameCount
                row.ended = true
            }

            is FrameWritten -> {
                frames++
                bytes += record.bytes
                val row = eventRows.getOrPut(record.eventId) { EventRow(record.atMillis) }
                val peak = record.blobs.maxOfOrNull { it.areaPx } ?: 0
                if (peak > row.peakBlobArea) row.peakBlobArea = peak
            }

            is Degradation -> degradations += record.atMillis to "${record.kind}: ${record.detail}"

            is ErrorRecord -> errors += Triple(
                record.atMillis,
                "${record.component}: ${record.message}",
                record.recovered,
            )

            is FocusChanged -> focusChanges += Triple(
                record.atMillis, record.fromDiopters, record.toDiopters
            )

            is SessionEnd -> {
                endMillis = record.atMillis
                endReason = record.reason
                events = maxOf(events, record.events)
                frames = maxOf(frames, record.frames)
                bytes = maxOf(bytes, record.bytesWritten)
            }

            else -> Unit
        }
    }

    fun render(nowMillis: Long = 0): String {
        val end = if (endMillis > 0) endMillis else maxOf(lastActivityMillis, nowMillis)
        val duration = if (startMillis > 0 && end > startMillis) end - startMillis else 0L
        val sb = StringBuilder(2048)

        sb.append("# Biomon insect session ").append(sessionId).append('\n')
        if (endReason == null) {
            sb.append('\n')
                .append("**Session still open, or ended without warning.** ")
                .append("This file is written as the session runs, so everything ")
                .append("below is accurate as of the last line written.\n")
        }

        sb.append("\n## Session\n\n")
        row(sb, "Session ID", sessionId)
        row(sb, "Started", isoOrDash(startMillis))
        row(sb, if (endReason == null) "Last activity" else "Ended", isoOrDash(end))
        row(sb, "Duration", humanDuration(duration))
        row(sb, "Termination", endReason ?: "none recorded (session open, killed, or power lost)")
        row(sb, "Device", "$device, Android $androidRelease")
        row(sb, "App version", appVersion)

        sb.append("\n## Capture settings\n\n")
        row(sb, "Focus", focusLabel(focusDiopters))
        row(
            sb, "Capture",
            if (captureWidth > 0) "${captureWidth}x$captureHeight JPEG q$jpegQuality" else "-",
        )
        row(
            sb, "Analysis",
            if (analysisWidth > 0) "${analysisWidth}x$analysisHeight" else "-",
        )

        sb.append("\n## Totals\n\n")
        row(sb, "Events", events.toString())
        row(sb, "Frames", frames.toString())
        row(sb, "Bytes written", humanBytes(bytes))
        row(
            sb, "Mean frame size",
            if (frames > 0) humanBytes(bytes / frames) else "-",
        )
        row(sb, "Power samples", powerSamples.toString())

        sb.append("\n## Battery and temperature\n\n")
        row(sb, "Battery start", pct(batteryStart))
        row(sb, "Battery end", pct(batteryEnd))
        row(sb, "Battery minimum", pct(if (batteryMin == Int.MAX_VALUE) -1 else batteryMin))
        row(sb, "Temperature min", temp(if (tempMin == Float.MAX_VALUE) Float.NaN else tempMin))
        row(
            sb, "Temperature mean",
            temp(if (tempCount > 0) (tempSum / tempCount).toFloat() else Float.NaN),
        )
        row(sb, "Temperature max", temp(if (tempMax == -Float.MAX_VALUE) Float.NaN else tempMax))

        sb.append("\n## Degradations\n\n")
        if (degradations.isEmpty()) {
            sb.append("None. The session ran at full rate throughout.\n")
        } else {
            for ((at, text) in degradations) {
                sb.append("- `").append(isoOrDash(at)).append("` ").append(text).append('\n')
            }
        }

        sb.append("\n## Camera and storage errors\n\n")
        if (errors.isEmpty()) {
            sb.append("None.\n")
        } else {
            for ((at, text, recovered) in errors) {
                sb.append("- `").append(isoOrDash(at)).append("` ")
                    .append(if (recovered) "(recovered) " else "(fatal) ")
                    .append(text).append('\n')
            }
        }

        sb.append("\n## Focus changes\n\n")
        if (focusChanges.isEmpty()) {
            sb.append("None; focus was set at session start and left alone.\n")
        } else {
            for ((at, from, to) in focusChanges) {
                sb.append("- `").append(isoOrDash(at)).append("` ")
                    .append(focusLabel(from)).append(" -> ").append(focusLabel(to)).append('\n')
            }
        }

        sb.append("\n## Events\n\n")
        if (eventRows.isEmpty()) {
            sb.append("No events. Nothing triggered the motion detector.\n")
        } else {
            sb.append("| Event | Start | Duration | Frames | Peak blob area (px) |\n")
            sb.append("| --- | --- | --- | --- | --- |\n")
            for ((id, row) in eventRows) {
                sb.append("| ").append(id)
                    .append(" | ").append(isoOrDash(row.startMillis))
                    .append(" | ").append(
                        if (row.ended) humanDuration(row.durationMillis) else "open"
                    )
                    .append(" | ").append(row.frames)
                    .append(" | ").append(if (row.peakBlobArea > 0) row.peakBlobArea else 0)
                    .append(" |\n")
            }
        }
        return sb.toString()
    }

    private fun row(sb: StringBuilder, label: String, value: String) {
        sb.append("- **").append(label).append("**: ").append(value).append('\n')
    }

    private fun pct(value: Int): String = if (value < 0) "-" else "$value%"

    private fun temp(value: Float): String =
        if (value.isNaN()) "-" else String.format(java.util.Locale.US, "%.1f C", value)

    private fun focusLabel(diopters: Float): String = when {
        diopters <= 0.01f -> "infinity"
        else -> String.format(java.util.Locale.US, "%.2f D (%.0f cm)", diopters, 100f / diopters)
    }

    private fun humanBytes(value: Long): String = when {
        value >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.2f GB", value / 1e9)
        value >= 1_000_000L -> String.format(java.util.Locale.US, "%.2f MB", value / 1e6)
        value >= 1_000L -> String.format(java.util.Locale.US, "%.1f kB", value / 1e3)
        else -> "$value B"
    }

    private fun humanDuration(millis: Long): String {
        if (millis <= 0) return "-"
        val totalSeconds = millis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> String.format(java.util.Locale.US, "%dh %02dm %02ds", h, m, s)
            m > 0 -> String.format(java.util.Locale.US, "%dm %02ds", m, s)
            else -> String.format(java.util.Locale.US, "%ds", s)
        }
    }

    private fun isoOrDash(millis: Long): String =
        if (millis <= 0) "-" else ISO.format(Instant.ofEpochMilli(millis))

    private companion object {
        val ISO: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    }
}
