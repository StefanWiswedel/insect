package dk.biomon.insect.core.report

import dk.biomon.insect.core.manifest.Degradation
import dk.biomon.insect.core.manifest.ErrorRecord
import dk.biomon.insect.core.manifest.EventEnded
import dk.biomon.insect.core.manifest.EventStarted
import dk.biomon.insect.core.manifest.FocusChanged
import dk.biomon.insect.core.manifest.FrameWritten
import dk.biomon.insect.core.TriggerConfig
import dk.biomon.insect.core.geometry.DetectionGeometry
import dk.biomon.insect.core.illumination.IlluminationAssessment
import dk.biomon.insect.core.illumination.IlluminationClassifier
import dk.biomon.insect.core.manifest.IlluminationEvent
import dk.biomon.insect.core.manifest.ManifestRecord
import dk.biomon.insect.core.manifest.PowerSample
import dk.biomon.insect.core.manifest.SessionEnd
import dk.biomon.insect.core.manifest.SessionStart
import dk.biomon.insect.core.manifest.WarmupEnded
import dk.biomon.insect.core.manifest.WarmupStarted
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
class SessionSummary(
    /**
     * The trigger configuration the session ran with. Needed to re-run the
     * illumination rule over recorded blobs and to state the detection geometry;
     * defaults are only right for a summary rebuilt outside a session.
     */
    private val trigger: TriggerConfig = TriggerConfig(),
) {
    private val classifier = IlluminationClassifier(trigger)

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
    private var illuminationEvents = 0
    private var illuminationFirstMillis = 0L
    private var illuminationLastMillis = 0L
    private var illuminationPeakFraction = 0f
    private var freeBytes = -1L
    private var thermalStatus: String? = null
    private var warmupStartMillis = 0L
    private var warmupEndMillis = 0L
    private var warmupSeconds = 0

    private val degradations = ArrayList<Pair<Long, String>>()
    private val errors = ArrayList<Triple<Long, String, Boolean>>()
    private val focusChanges = ArrayList<Triple<Long, Float, Float>>()
    private val eventRows = LinkedHashMap<Long, EventRow>()

    private var endMillis: Long = 0
    private var endReason: String? = null

    private class EventRow(val startMillis: Long) {
        var durationMillis: Long = 0
        var frames: Int = 0
        /**
         * Frames seen on the wire, independent of [frames], which only arrives
         * with the closing record. Without this an event that was cut off
         * mid-flight reports zero frames when its JPEGs are sitting on the card.
         */
        var framesObserved: Int = 0
        var lastFrameMillis: Long = 0
        var peakBlobArea: Int = 0
        /**
         * The blob list from the frame with the largest blob, kept so the
         * illumination rule can be re-run over it at render time. Bounded by one
         * frame per event, so this costs nothing worth counting.
         */
        var peakBlobs: List<dk.biomon.insect.core.blob.Blob> = emptyList()
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
                if (record.freeBytes > 0) freeBytes = record.freeBytes
                record.thermalStatus?.let { thermalStatus = it }
                val t = record.temperatureCelsius
                if (t.isFinite() && t > -100f) {
                    if (t < tempMin) tempMin = t
                    if (t > tempMax) tempMax = t
                    tempSum += t
                    tempCount++
                }
            }

            is WarmupStarted -> {
                warmupStartMillis = record.atMillis
                warmupSeconds = record.seconds
            }

            is WarmupEnded -> warmupEndMillis = record.atMillis

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
                row.framesObserved++
                row.lastFrameMillis = record.atMillis
                val peak = record.blobs.maxOfOrNull { it.areaPx } ?: 0
                if (peak > row.peakBlobArea) {
                    row.peakBlobArea = peak
                    row.peakBlobs = record.blobs
                }
            }

            is IlluminationEvent -> {
                illuminationEvents++
                illuminationFirstMillis =
                    if (illuminationFirstMillis == 0L) record.atMillis else illuminationFirstMillis
                illuminationLastMillis = record.atMillis
                val fraction =
                    if (record.workPixels == 0) 0f
                    else record.areaPx.toFloat() / record.workPixels
                if (fraction > illuminationPeakFraction) illuminationPeakFraction = fraction
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

        appendDetectionGeometry(sb)

        sb.append("\n## Warm-up\n\n")
        if (warmupStartMillis <= 0) {
            sb.append("Not recorded.\n")
        } else {
            row(sb, "Started", isoOrDash(warmupStartMillis))
            row(
                sb, "Ended",
                if (warmupEndMillis > 0) isoOrDash(warmupEndMillis) else "still warming up",
            )
            row(sb, "Configured", "${warmupSeconds}s")
            sb.append(
                "\nThe trigger is held off while the background model converges, " +
                    "so no events can occur in this window. A gap here is expected, " +
                    "not a dead sensor.\n"
            )
        }

        sb.append("\n## Totals\n\n")
        row(sb, "Events", events.toString())
        row(sb, "Frames", frames.toString())
        row(sb, "Bytes written", humanBytes(bytes))
        row(
            sb, "Mean frame size",
            if (frames > 0) humanBytes(bytes / frames) else "-",
        )
        row(sb, "Power samples", powerSamples.toString())
        row(sb, "Illumination events", illuminationEvents.toString())
        appendCapacity(sb)

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
        row(sb, "Thermal status", thermalStatus ?: "-")
        if (tempCount > 1 && tempMin == tempMax) {
            sb.append(
                "\n**Battery temperature did not move across ").append(tempCount)
                .append(" samples.** The platform only refreshes it when it ")
                .append("broadcasts a battery change, so on steady charge it can ")
                .append("latch. Trust the thermal status above instead.\n")
        }

        sb.append("\n## Illumination events\n\n")
        if (illuminationEvents == 0) {
            sb.append("None. Nothing changed the whole scene's brightness at once.\n")
        } else {
            row(sb, "Count", illuminationEvents.toString())
            row(sb, "First", isoOrDash(illuminationFirstMillis))
            row(sb, "Last", isoOrDash(illuminationLastMillis))
            row(
                sb, "Largest",
                "%.1f%% of frame".format(java.util.Locale.US, illuminationPeakFraction * 100),
            )
            sb.append(
                "\nA blob covering more of the frame than an insect ever could is " +
                    "the light moving, not a subject. Capture is suppressed for " +
                    "those frames and the background model is re-baselined, " +
                    "because a global brightness shift makes it stale everywhere " +
                    "at once. Outdoors these are mostly cloud shadow crossing the " +
                    "board: a high count means a broken sky, and explains a thin " +
                    "detection record without a broken rig.\n"
            )
        }

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
            var inferred = 0
            var emptyEvents = 0
            for ((id, row) in eventRows) {
                val frames = if (row.ended) maxOf(row.frames, row.framesObserved)
                else row.framesObserved
                if (frames == 0) emptyEvents++
                // An event with no closing record was cut off: the session died
                // before it could be written. Close it here from what is known
                // rather than leaving an "open" row that will never be resolved
                // -- nothing may depend on a clean shutdown having run.
                val duration = if (row.ended) {
                    humanDuration(row.durationMillis)
                } else {
                    inferred++
                    val until = if (row.lastFrameMillis > 0) row.lastFrameMillis else end
                    humanDuration((until - row.startMillis).coerceAtLeast(0)) + " (inferred)"
                }
                sb.append("| ").append(id)
                    .append(" | ").append(isoOrDash(row.startMillis))
                    .append(" | ").append(duration)
                    .append(" | ").append(frames)
                    .append(" | ").append(if (row.peakBlobArea > 0) row.peakBlobArea else 0)
                    .append(" |\n")
            }
            if (inferred > 0) {
                sb.append('\n').append("**").append(inferred)
                    .append(if (inferred == 1) " event has" else " events have")
                    .append(" an inferred duration.** No closing record reached the ")
                    .append("manifest, so the session ended mid-event -- killed, ")
                    .append("power lost, or still running as this was written. The ")
                    .append("duration runs to the last frame written, or to the end ")
                    .append("of the session if no frame was.\n")
            }
            if (emptyEvents > 0) {
                sb.append('\n').append("**").append(emptyEvents)
                    .append(if (emptyEvents == 1) " event wrote no frames.**" else " events wrote no frames.**")
                    .append(" The trigger fired and a capture was requested, but no ")
                    .append("JPEG reached the card before the event closed. Listed ")
                    .append("rather than dropped: a trigger that never yields a frame ")
                    .append("is worth seeing.\n")
            }
        }
        appendEventDiagnostics(sb)
        return sb.toString()
    }

    /**
     * What the subject actually measures in the pixels the trigger works on.
     *
     * Present on every run because its absence is what allowed the rig to be
     * blind to its own target without anything saying so: at 4x downsample a fly
     * at 31cm was 2.4-4.7 working pixels against a floor of 4, so essentially
     * nothing that could trigger was an insect.
     */
    private fun appendDetectionGeometry(sb: StringBuilder) {
        sb.append("\n## Detection geometry\n\n")
        val a = DetectionGeometry.assess(
            focusDiopters = focusDiopters,
            captureWidth = captureWidth,
            captureHeight = captureHeight,
            analysisWidth = analysisWidth,
            analysisHeight = analysisHeight,
            downsample = trigger.downsample,
            minBlobAreaPx = trigger.minBlobAreaPx,
        )
        if (a == null) {
            sb.append("Not computable: focus at infinity, or capture geometry not recorded.\n")
            return
        }
        row(sb, "Working distance", String.format(java.util.Locale.US, "%.0f cm", a.distanceCm))
        row(sb, "Working frame", "${a.workingWidth}x${a.workingHeight} (downsample ${trigger.downsample}x)")
        row(
            sb, "Expected insect, full resolution",
            String.format(java.util.Locale.US, "%,.0f-%,.0f px", a.fullResMinPx, a.fullResMaxPx),
        )
        row(
            sb, "Expected insect, working pixels",
            String.format(java.util.Locale.US, "%.1f-%.1f px", a.workingMinPx, a.workingMaxPx),
        )
        row(sb, "Minimum blob area", "${a.minBlobAreaPx} px")
        row(
            sb, "Ratio (target / floor)",
            String.format(java.util.Locale.US, "%.1fx-%.1fx", a.ratioMin, a.ratioMax),
        )
        sb.append('\n')
        when {
            a.blind -> sb.append(
                "**The rig cannot see its own target.** The smallest expected " +
                    "insect is below the minimum blob area, so it can never form " +
                    "a blob large enough to trigger. Anything this session did " +
                    "detect was something else. Reduce the downsample factor or " +
                    "the minimum blob area before deploying.\n"
            )
            a.marginal -> sb.append(
                "**Marginal.** The smallest expected insect is under " +
                    String.format(java.util.Locale.US, "%.1fx", DetectionGeometry.SAFE_RATIO) +
                    " the minimum blob area, so a partly occluded one will be " +
                    "missed. Workable, but not comfortable.\n"
            )
            else -> sb.append(
                "The smallest expected insect clears the minimum blob area with " +
                    "margin, so a partly occluded or edge-on subject still forms " +
                    "a usable blob.\n"
            )
        }
        sb.append(
            "\nSizes are scaled from a measured reference (a fly is " +
                "1,500-3,000 px at 31 cm on a 12.19MP sensor) by the inverse " +
                "square of working distance and by sensor resolution.\n"
        )
    }

    /**
     * Per-event diagnostics for the illumination rule.
     *
     * Here rather than in a script because a rule you have to move files around
     * to audit is a rule nobody audits. Every session diagnoses itself, so the
     * thresholds can be tuned from any run.
     *
     * The verdict is produced by the same [IlluminationClassifier] the device
     * runs, re-applied to the blobs recorded with each event's peak frame. The
     * signals are ratios, so full-resolution boxes give the same answer as the
     * working-space blobs the live trigger saw.
     */
    private fun appendEventDiagnostics(sb: StringBuilder) {
        sb.append("\n## Event diagnostics\n\n")
        val scored = eventRows.entries.filter { it.value.peakBlobs.isNotEmpty() }
        if (scored.isEmpty()) {
            sb.append(
                "No event recorded a blob to score. Nothing to tune the " +
                    "illumination rule against from this session.\n"
            )
            return
        }
        sb.append(
            "| Event | Peak area (px) | % frame | Edges | Opposite | Fill | Blobs | Spread | Verdict |\n"
        )
        sb.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n")
        var disagreements = 0
        for ((id, r) in scored) {
            val a: IlluminationAssessment =
                classifier.classify(r.peakBlobs, captureWidth, captureHeight)
            if (a.verdict.isIllumination) disagreements++
            val g = a.signals
            sb.append("| ").append(id)
                .append(" | ").append(String.format(java.util.Locale.US, "%,d", g.areaPx))
                .append(" | ").append(String.format(java.util.Locale.US, "%.2f%%", g.areaFraction * 100))
                .append(" | ").append(g.edgesTouched).append(if (a.edgeSignal) "*" else "")
                .append(" | ").append(if (g.oppositeEdges) "yes" else "no")
                .append(" | ").append(String.format(java.util.Locale.US, "%.2f", g.fillRatio))
                .append(if (a.fillSignal) "*" else "")
                .append(" | ").append(g.blobCount)
                .append(" | ").append(String.format(java.util.Locale.US, "%.0f%%", g.spreadFraction * 100))
                .append(if (a.countSignal) "*" else "")
                .append(" | ").append(a.describe())
                .append(" |\n")
        }
        sb.append(
            "\nA `*` marks a signal that fired. Gates: illumination on size alone " +
                "at " + String.format(java.util.Locale.US, "%.1f%%", trigger.illuminationAreaFraction * 100) +
                " of frame; examined above " +
                String.format(java.util.Locale.US, "%.1f%%", trigger.illuminationSuspectFraction * 100) +
                ", where " + trigger.illuminationSignalsRequired +
                " of 3 signals are needed. Signals are edge contact (two opposite " +
                "edges or three of four), fill ratio below " +
                String.format(java.util.Locale.US, "%.2f", trigger.illuminationFillRatioMax) +
                ", and " + trigger.illuminationBlobCountMin +
                "+ blobs spread over " +
                String.format(java.util.Locale.US, "%.0f%%", trigger.illuminationSpreadFractionMin * 100) +
                " of the frame diagonal.\n"
        )
        if (disagreements > 0) {
            sb.append('\n').append("**").append(disagreements)
                .append(if (disagreements == 1) " event was" else " events were")
                .append(" captured that the rule would judge differently now.** ")
                .append("They were recorded as detections at the time, so either ")
                .append("the thresholds have changed since, or these are the ")
                .append("artefacts the rule was tightened to catch. Either way ")
                .append("they are the rows to look at.\n")
        }
    }

    /**
     * Remaining capacity, projected from this session's own mean frame size.
     *
     * A running total of what has been used answers the wrong question in the
     * field. What matters at 9am is whether the card will last until 6pm, and
     * the only honest input for that is how big the frames are actually coming
     * out on today's scene -- a dark indoor test averaged 930kB, while daylight
     * on a white board runs several times that.
     */
    private fun appendCapacity(sb: StringBuilder) {
        if (freeBytes <= 0) return
        row(sb, "Free space", humanBytes(freeBytes))
        if (frames <= 0 || bytes <= 0) {
            sb.append("- **Remaining capacity**: no frames yet to estimate from\n")
            return
        }
        val meanFrame = bytes / frames
        if (meanFrame <= 0) return
        val remainingFrames = freeBytes / meanFrame
        sb.append("- **Remaining capacity**: about ").append(remainingFrames)
            .append(" more frames at ").append(humanBytes(meanFrame))
            .append(" each\n")
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
