package dk.biomon.insect.store

import android.os.StatFs
import dk.biomon.insect.FrameWriteResult
import dk.biomon.insect.SessionInfo
import dk.biomon.insect.SessionRecorder
import dk.biomon.insect.SessionStats
import dk.biomon.insect.core.blob.Blob
import dk.biomon.insect.core.event.EventEndReason
import dk.biomon.insect.core.manifest.ErrorRecord
import dk.biomon.insect.core.manifest.EventEnded
import dk.biomon.insect.core.manifest.EventStarted
import dk.biomon.insect.core.manifest.FrameWritten
import dk.biomon.insect.core.manifest.ManifestRecord
import dk.biomon.insect.core.manifest.SessionEnd
import dk.biomon.insect.core.naming.FrameNaming
import dk.biomon.insect.core.report.SessionSummary
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything that outlives the process: JPEGs on disk, the manifest beside them,
 * the index behind them, and a human-readable summary on top.
 *
 * The ordering inside [writeFrame] is deliberate. The frame is written first,
 * then the manifest record, then the database row, because the frame is the
 * artefact and the other two merely describe it. An abrupt end between the steps
 * therefore costs description, never data -- and the filename already carries
 * session, event, timestamp and sequence, so a described-by-nothing frame is
 * still a usable frame.
 *
 * No method throws. A storage failure is recorded and reported through the
 * return value; letting it escape would take down the service and lose the rest
 * of the day.
 */
class FileSessionRecorder(
    override val session: SessionInfo,
    private val manifest: ManifestWriter,
    private val database: SessionDatabase,
    private val scanner: MediaScanner?,
) : SessionRecorder {

    private val framesDir = File(session.directory, SessionLayout.FRAMES_DIR)
    private val summaryFile = File(session.directory, SessionLayout.SUMMARY_FILE)
    private val _stats = MutableStateFlow(SessionStats())
    override val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private val closed = AtomicBoolean(false)
    private val eventStartTimes = HashMap<Long, Long>()

    private val summaryLock = Any()
    private val summary = SessionSummary()
    private var lastSummaryWriteMillis = 0L

    override fun record(record: ManifestRecord) {
        manifest.append(record)
        observe(record, force = true)
    }

    override fun writeFrame(
        eventId: Long,
        sequence: Int,
        mode: String,
        wallClockMillis: Long,
        jpeg: ByteArray,
        blobs: List<Blob>,
    ): FrameWriteResult {
        val filename = FrameNaming.frameName(session.sessionId, eventId, wallClockMillis, sequence)
        val target = File(framesDir, filename)
        // Write to a sidecar name and rename into place. A session that dies
        // mid-write then leaves a `.part` file that is obviously not a frame,
        // rather than a truncated .jpg that decodes to half an image and quietly
        // corrupts a background model on the laptop.
        val partial = File(framesDir, "$filename.part")

        val error = try {
            if (!framesDir.exists()) framesDir.mkdirs()
            FileOutputStream(partial).use { out ->
                out.write(jpeg)
                out.flush()
                out.fd.sync()
            }
            if (!partial.renameTo(target)) {
                partial.delete()
                "rename failed for $filename"
            } else {
                null
            }
        } catch (t: Throwable) {
            try {
                partial.delete()
            } catch (ignored: Throwable) {
                // The disk is already failing; nothing useful follows.
            }
            "${t.javaClass.simpleName}: ${t.message}"
        }

        if (error != null) {
            record(
                ErrorRecord(wallClockMillis, "frame_write", "$filename: $error", recovered = true)
            )
            return FrameWriteResult(filename, 0, error)
        }

        val bytes = jpeg.size.toLong()
        val written = FrameWritten(
            atMillis = wallClockMillis,
            eventId = eventId,
            sequence = sequence,
            filename = filename,
            mode = mode,
            bytes = bytes,
            blobs = blobs,
        )
        manifest.append(written)
        // Throttled: a summary rewrite per frame would be a file write per frame
        // for a file nobody reads until the session is over.
        observe(written, force = false)
        database.frameWritten(
            sessionId = session.sessionId,
            eventId = eventId,
            sequence = sequence,
            filename = filename,
            atMillis = wallClockMillis,
            mode = mode,
            bytes = bytes,
            blobs = blobs,
        )
        scanner?.add(target, wallClockMillis)
        // update() rather than a read-modify-write: frames arrive on the capture
        // thread while events are opened on the analysis thread, and a lost
        // update here would quietly under-report the session.
        _stats.update { it.copy(frames = it.frames + 1, bytesWritten = it.bytesWritten + bytes) }
        return FrameWriteResult(filename, bytes)
    }

    override fun eventStarted(eventId: Long, atMillis: Long) {
        synchronized(eventStartTimes) { eventStartTimes[eventId] = atMillis }
        val started = EventStarted(atMillis, eventId)
        manifest.append(started)
        observe(started, force = true)
        database.eventStarted(session.sessionId, eventId, atMillis)
        _stats.update { it.copy(events = it.events + 1) }
    }

    override fun eventEnded(
        eventId: Long,
        atMillis: Long,
        frames: Int,
        reason: EventEndReason,
        durationMillis: Long,
    ) {
        val started = synchronized(eventStartTimes) { eventStartTimes.remove(eventId) }
        // Trust the caller's duration when it gave one, but a state machine that
        // lost track is not a reason to write a zero into the record.
        val duration = if (durationMillis > 0) {
            durationMillis
        } else {
            started?.let { atMillis - it } ?: 0L
        }
        val label = reason.name.lowercase()
        val ended = EventEnded(atMillis, eventId, frames, label, duration)
        manifest.append(ended)
        observe(ended, force = true)
        database.eventEnded(session.sessionId, eventId, atMillis, frames, label, duration)
    }

    override fun freeBytes(): Long = try {
        StatFs(session.directory.absolutePath).availableBytes
    } catch (t: Throwable) {
        // An unreadable volume is not a full one, but treating it as full is the
        // safe direction: the disk guard will stop capture and say why.
        0L
    }

    override fun close(reason: String, atMillis: Long) {
        if (!closed.compareAndSet(false, true)) return
        val snapshot = _stats.value
        val end = SessionEnd(
            atMillis = atMillis,
            reason = reason,
            events = snapshot.events,
            frames = snapshot.frames,
            bytesWritten = snapshot.bytesWritten,
            durationMillis = atMillis - session.startedAtMillis,
        )
        manifest.append(end)
        observe(end, force = true)
        if (manifest.lostRecords > 0) {
            record(
                ErrorRecord(
                    atMillis,
                    "manifest",
                    "${manifest.lostRecords} record(s) were lost during this session",
                    recovered = false,
                )
            )
        }
        database.sessionClosed(
            sessionId = session.sessionId,
            atMillis = atMillis,
            reason = reason,
            events = snapshot.events,
            frames = snapshot.frames,
            bytes = snapshot.bytesWritten,
        )
        database.close()
        manifest.close()
        scanner?.flush(atMillis)
    }

    /**
     * Fold a record into the summary and, when it is worth it, rewrite the file.
     *
     * Written as the session runs rather than at the end, because sessions end
     * abruptly by default and a summary composed at shutdown is a summary that
     * never exists for the deployments most in need of explaining.
     */
    private fun observe(record: ManifestRecord, force: Boolean) {
        val text = synchronized(summaryLock) {
            summary.observe(record)
            val due = force ||
                record.atMillis - lastSummaryWriteMillis >= SUMMARY_INTERVAL_MILLIS
            if (!due) return
            lastSummaryWriteMillis = record.atMillis
            summary.render(record.atMillis)
        }
        writeSummary(text, record.atMillis)
    }

    /** Rewrite via a sidecar and rename, so a reader never sees half a file. */
    private fun writeSummary(text: String, atMillis: Long) {
        val partial = File(session.directory, "${SessionLayout.SUMMARY_FILE}.part")
        try {
            FileOutputStream(partial).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (partial.renameTo(summaryFile)) {
                scanner?.add(summaryFile, atMillis)
            } else {
                partial.delete()
            }
        } catch (t: Throwable) {
            // The summary is a convenience over the manifest, which already has
            // everything. Never let it take the session down.
            try {
                partial.delete()
            } catch (ignored: Throwable) {
            }
        }
    }

    private companion object {
        const val SUMMARY_INTERVAL_MILLIS = 10_000L
    }
}
