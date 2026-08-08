package dk.biomon.insect.store

import android.os.Environment
import dk.biomon.insect.FrameWriteResult
import dk.biomon.insect.SessionInfo
import dk.biomon.insect.SessionRecorder
import dk.biomon.insect.SessionStats
import dk.biomon.insect.core.blob.Blob
import dk.biomon.insect.core.event.EventEndReason
import dk.biomon.insect.core.manifest.ManifestRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * A recorder that records nothing, for framing the rig before a session starts.
 *
 * Preview mode exists because the only previous way to see what the camera sees
 * was to start a real session, which put setup frames -- hands in shot, the rig
 * being nudged, focus being hunted -- into the data. Those are exactly the
 * frames a later analysis cannot tell from a genuine visit.
 *
 * So this writes no manifest, creates no session directory, allocates no session
 * id, and reports zero for every total. Its [session] directory is a path that
 * is never created; nothing here touches the filesystem. Capture is suppressed
 * upstream as well ([dk.biomon.insect.pipeline.AnalysisPipeline.captureAllowed]
 * is false in preview), so no still is ever requested and this object's
 * [writeFrame] should never be reached -- it is written to be harmless if it is.
 */
class PreviewRecorder : SessionRecorder {

    override val session: SessionInfo = SessionInfo(
        sessionId = PREVIEW_SESSION_ID,
        // Never created. Only used to answer freeBytes() against the volume the
        // real session would land on.
        directory = File(Environment.getExternalStorageDirectory(), SessionStore.SHARED_ROOT_NAME),
        startedAtMillis = System.currentTimeMillis(),
    )

    private val _stats = MutableStateFlow(SessionStats())
    override val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    override fun record(record: ManifestRecord) = Unit

    override fun writeFrame(
        eventId: Long,
        sequence: Int,
        mode: String,
        wallClockMillis: Long,
        jpeg: ByteArray,
        blobs: List<Blob>,
    ): FrameWriteResult = FrameWriteResult(
        filename = "",
        bytes = 0,
        error = "preview mode writes no frames",
    )

    override fun eventStarted(eventId: Long, atMillis: Long) = Unit

    override fun eventEnded(
        eventId: Long,
        atMillis: Long,
        frames: Int,
        reason: EventEndReason,
        durationMillis: Long,
    ) = Unit

    /** Real, so the storage readout on screen is truthful while framing. */
    override fun freeBytes(): Long = try {
        val dir = session.directory
        val target = if (dir.exists()) dir else Environment.getExternalStorageDirectory()
        target.usableSpace
    } catch (t: Throwable) {
        -1L
    }

    override fun close(reason: String, atMillis: Long) = Unit

    companion object {
        /**
         * Deliberately not a valid session id. Nothing downstream should ever
         * see it, and if something does, it is obvious in a log that this was
         * not a real session.
         */
        const val PREVIEW_SESSION_ID = "preview"
    }
}
