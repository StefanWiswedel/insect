package dk.biomon.insect

import dk.biomon.insect.core.CaptureConfig
import dk.biomon.insect.core.GuardConfig
import dk.biomon.insect.core.TriggerConfig
import dk.biomon.insect.core.blob.Blob
import dk.biomon.insect.core.event.EventEndReason
import dk.biomon.insect.core.manifest.ManifestRecord
import dk.biomon.insect.core.policy.GuardState
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * The seams between the app's halves, written down before the halves were, so
 * that capture, persistence and UI cannot drift into incompatible shapes.
 *
 * * Capture (camera, service, trigger wiring) *calls* [SessionRecorder] and
 *   *publishes* [CaptureUiState].
 * * Persistence *implements* [SessionRecorder].
 * * UI *reads* [CaptureUiState] and [SettingsRepository]; it calls nothing else.
 *
 * See `docs/analysis-stream-contract.md` for the capture/trigger interface,
 * which is the one with real lifetime rules.
 */

/** Identity and location of one deployment. One session per deployment. */
data class SessionInfo(
    val sessionId: String,
    /** Root directory: manifest, database and frames all live under here. */
    val directory: File,
    val startedAtMillis: Long,
)

/** Running totals, for the notification, the UI and the closing record. */
data class SessionStats(
    val events: Long = 0,
    val frames: Long = 0,
    val bytesWritten: Long = 0,
)

/** Outcome of writing one JPEG. */
data class FrameWriteResult(
    val filename: String,
    val bytes: Long,
    /** Null on success; a message when the write failed and was recorded. */
    val error: String? = null,
)

/**
 * Everything that outlives the process.
 *
 * Implementations must assume the session ends abruptly at any moment: records
 * are flushed as they occur and never buffered to session end, and no reader
 * downstream may depend on [close] having run. The absence of a closing record
 * is itself data — it means the phone died or was killed.
 *
 * All methods are safe to call from the capture threads; implementations move
 * work off them as needed. None of them may throw: a storage failure is recorded
 * and reported, never propagated into the capture path, because a crashed
 * service loses the rest of the day.
 */
interface SessionRecorder {
    val session: SessionInfo
    val stats: StateFlow<SessionStats>

    /** Append one manifest line. Flushed before returning. */
    fun record(record: ManifestRecord)

    /**
     * Write one full-resolution frame and its manifest record.
     *
     * @param blobs bounding boxes **in full-resolution coordinates** — convert
     *   with [Blob.scaleTo] before calling. The phone never crops; these travel
     *   as metadata so cropping stays a lossless laptop-side operation.
     */
    fun writeFrame(
        eventId: Long,
        sequence: Int,
        mode: String,
        wallClockMillis: Long,
        jpeg: ByteArray,
        blobs: List<Blob>,
    ): FrameWriteResult

    fun eventStarted(eventId: Long, atMillis: Long)

    fun eventEnded(
        eventId: Long,
        atMillis: Long,
        frames: Int,
        reason: EventEndReason,
        durationMillis: Long,
    )

    /** Free space on the volume holding [SessionInfo.directory]. */
    fun freeBytes(): Long

    /** Close the manifest with an explicit reason. Idempotent. */
    fun close(reason: String, atMillis: Long)
}

/**
 * A grayscale preview frame, derived from the analysis stream.
 *
 * The preview is **not** a camera surface. It is a copy of the luma the trigger
 * is already looking at, which means the Camera2 session never has to be
 * reconfigured when the screen turns on or off -- see CameraController. It also
 * means the mask overlay lines up with what is underneath it by construction,
 * because both come from the same frame in the same coordinates.
 *
 * Produced only while a UI is actually attached; during a deployment nothing
 * allocates these at all.
 */
class PreviewFrame(
    val width: Int,
    val height: Int,
    /** Row-major 8-bit luma, unsigned. Owned by the consumer; never recycled. */
    val luma: ByteArray,
)

/** A copy of the trigger mask for the preview overlay. Never the live buffer. */
class MaskSnapshot(
    val width: Int,
    val height: Int,
    /** One byte per working pixel: 0 background, 1 foreground. */
    val mask: ByteArray,
    /** Blob boxes in working coordinates. */
    val blobs: List<Blob>,
)

/** What the one screen shows. Published by capture, read by the UI. */
data class CaptureUiState(
    val running: Boolean = false,
    val sessionId: String? = null,
    val stats: SessionStats = SessionStats(),
    val guard: GuardState? = null,
    val focusDistanceDiopters: Float = 0f,
    val analysisFps: Int = 0,
    /** Null when idle; otherwise "moving" or "stationary". */
    val captureMode: String? = null,
    val activeEventId: Long? = null,
    /** Absolute path of the running session's directory, once one exists. */
    val storagePath: String? = null,
    /** True when the session is writing to app-specific fallback storage. */
    val storageFallback: Boolean = false,
    val mask: MaskSnapshot? = null,
    val preview: PreviewFrame? = null,
    /** Most recent recorded error, so a degraded session is visible at a glance. */
    val lastError: String? = null,
    val warmingUp: Boolean = false,
)

/** User-adjustable settings. Defaults come from `:core`. */
data class AppSettings(
    val trigger: TriggerConfig = TriggerConfig(),
    val capture: CaptureConfig = CaptureConfig(),
    val guards: GuardConfig = GuardConfig(),
    /**
     * Locked focus distance in dioptres (1/metres).
     *
     * 3.23 D is 31cm, the measured phone-to-board distance. Aimed from the main
     * screen per deployment, applied live, and every change is recorded.
     */
    val focusDistanceDiopters: Float = 3.23f,
)

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}
