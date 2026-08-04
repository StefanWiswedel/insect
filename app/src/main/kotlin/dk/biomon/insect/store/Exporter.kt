package dk.biomon.insect.store

import android.content.Context
import dk.biomon.insect.core.naming.FrameNaming
import java.io.File

/** What an export would move, so the size is known before anything is copied. */
data class ExportEstimate(
    val sessionId: String,
    val frames: Int,
    val bytes: Long,
    val hasManifest: Boolean,
    val partialFrames: Int,
)

/**
 * Produces a directory the existing laptop pipeline consumes without
 * modification.
 *
 * There is deliberately almost nothing here. The session directory is *already*
 * the export format: frames in `frames/`, named so they group into events and
 * order in time without reading anything else, with the manifest beside them.
 * Export is a copy and a summary, not a transformation -- the phone never crops
 * and never re-encodes, so anything this class did to the pixels would be damage.
 */
object Exporter {

    /** Machine-readable index of what an export contains. */
    private const val EXPORT_MANIFEST_FILE = "export.json"

    fun sessions(context: Context): List<File> {
        // Both roots: sessions written before All Files Access was granted are
        // still in app-specific storage, and they are still data.
        val roots = listOfNotNull(
            SessionStore.plannedRoot(context),
            context.getExternalFilesDir(null),
            context.filesDir,
        ).distinct()
        return roots
            .map { File(it, SessionLayout.SESSIONS_DIR) }
            .filter { it.isDirectory }
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .filter { it.isDirectory }
            .sortedByDescending { it.name }
    }

    fun estimate(sessionDir: File): ExportEstimate {
        val frames = File(sessionDir, SessionLayout.FRAMES_DIR).listFiles().orEmpty()
        val complete = frames.filter { it.name.endsWith(FrameNaming.EXTENSION) }
        return ExportEstimate(
            sessionId = sessionDir.name,
            frames = complete.size,
            bytes = complete.sumOf { it.length() },
            hasManifest = File(sessionDir, SessionLayout.MANIFEST_FILE).isFile,
            // A `.part` file is a frame that was being written when the session
            // died. Worth surfacing: it dates the moment the power went.
            partialFrames = frames.count { it.name.endsWith(".part") },
        )
    }

    /**
     * Copy a session to [destination]. Returns the number of frames copied, or
     * null if the destination could not be prepared.
     *
     * Existing files are skipped rather than overwritten, so an interrupted
     * export resumes instead of starting again -- the frames are immutable, so
     * a name that already exists is already the right bytes.
     */
    fun export(sessionDir: File, destination: File): Int? {
        val outFrames = File(destination, SessionLayout.FRAMES_DIR)
        if (!outFrames.exists() && !outFrames.mkdirs()) return null

        File(sessionDir, SessionLayout.MANIFEST_FILE).takeIf { it.isFile }?.let {
            it.copyTo(File(destination, SessionLayout.MANIFEST_FILE), overwrite = true)
        }
        File(sessionDir, SessionLayout.DATABASE_FILE).takeIf { it.isFile }?.let {
            it.copyTo(File(destination, SessionLayout.DATABASE_FILE), overwrite = true)
        }
        File(sessionDir, SessionLayout.SUMMARY_FILE).takeIf { it.isFile }?.let {
            it.copyTo(File(destination, SessionLayout.SUMMARY_FILE), overwrite = true)
        }

        var copied = 0
        for (frame in File(sessionDir, SessionLayout.FRAMES_DIR).listFiles().orEmpty()) {
            if (!frame.name.endsWith(FrameNaming.EXTENSION)) continue
            val target = File(outFrames, frame.name)
            if (target.exists() && target.length() == frame.length()) continue
            try {
                frame.copyTo(target, overwrite = true)
                copied++
            } catch (t: Throwable) {
                // One unreadable frame does not invalidate the rest of the day.
            }
        }

        val estimate = estimate(sessionDir)
        File(destination, EXPORT_MANIFEST_FILE).writeText(
            """
            {"session_id":"${estimate.sessionId}","frames":${estimate.frames},
            "bytes":${estimate.bytes},"manifest":${estimate.hasManifest},
            "partial_frames":${estimate.partialFrames}}
            """.trimIndent().replace("\n", "") + "\n"
        )
        return copied
    }
}
