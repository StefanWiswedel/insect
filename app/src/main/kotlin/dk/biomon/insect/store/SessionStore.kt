package dk.biomon.insect.store

import android.content.Context
import android.os.Environment
import dk.biomon.insect.AppSettings
import dk.biomon.insect.SessionInfo
import dk.biomon.insect.SessionRecorder
import dk.biomon.insect.core.manifest.Degradation
import dk.biomon.insect.core.manifest.ErrorRecord
import dk.biomon.insect.core.naming.FrameNaming
import java.io.File
import java.time.LocalDate

/**
 * Names of the things inside a session directory.
 *
 * Shared between the recorder and the exporter so the two cannot disagree about
 * where the frames are, and kept deliberately flat: the laptop pipeline is
 * pointed at `frames/` directly and reads nothing else to find the JPEGs.
 */
object SessionLayout {
    const val SESSIONS_DIR = "sessions"
    const val FRAMES_DIR = "frames"
    const val MANIFEST_FILE = "manifest.jsonl"
    const val DATABASE_FILE = "index.db"
    const val SUMMARY_FILE = "session.json"
}

/**
 * Opens the one session per deployment and hands back the thing that outlives
 * the process.
 *
 * Layout, under the storage root:
 *
 * ```
 * sessions/<sessionId>/manifest.jsonl
 * sessions/<sessionId>/index.db
 * sessions/<sessionId>/frames/<sessionId>_e00007_...Z_f00042.jpg
 * ```
 *
 * The preferred root is the app-specific directory on external storage: it is
 * where the ~30GB actually is, and it is the only one visible over MTP, which is
 * how the frames reach the analysis laptop. Internal storage is the fallback and
 * is small enough that the disk guard will be doing real work all session.
 *
 * Nothing here throws. A deployment that cannot allocate storage still gets a
 * recorder, still runs, and still writes down why it is failing -- an evening
 * with a manifest full of storage errors is data; an evening with a crashed
 * service is nothing at all.
 */
object SessionStore {

    fun open(context: Context, settings: AppSettings): SessionRecorder {
        val startedAt = System.currentTimeMillis()
        // Notes accumulate before there is anywhere to write them; they go into
        // the manifest as soon as one exists, so a fallback is never invisible.
        val notes = ArrayList<String>(2)

        val root = chooseRoot(context, notes)
        val sessionsRoot = File(root, SessionLayout.SESSIONS_DIR)
        val sessionId = allocateSessionId(sessionsRoot, notes)
        val directory = File(sessionsRoot, sessionId)

        if (!directory.exists() && !directory.mkdirs()) {
            notes += "could not create session directory ${directory.absolutePath}"
        }
        val framesDir = File(directory, SessionLayout.FRAMES_DIR)
        if (!framesDir.exists() && !framesDir.mkdirs()) {
            notes += "could not create frames directory ${framesDir.absolutePath}"
        }

        val manifest = ManifestWriter(File(directory, SessionLayout.MANIFEST_FILE))
        val session = SessionInfo(
            sessionId = sessionId,
            directory = directory,
            startedAtMillis = startedAt,
        )
        val database = SessionDatabase(File(directory, SessionLayout.DATABASE_FILE)) { message ->
            manifest.append(ErrorRecord(System.currentTimeMillis(), "database", message, true))
        }
        database.sessionOpened(session, appVersion(context))

        val recorder = FileSessionRecorder(session, manifest, database)

        for (note in notes) {
            manifest.append(ErrorRecord(startedAt, "storage", note, recovered = true))
        }

        // Pre-flight the disk guard rather than waiting for the first sample: if
        // the volume is already under the stop threshold, the session is going to
        // capture nothing, and the reason belongs at the top of the manifest
        // instead of being inferred later from an empty frames directory.
        val free = recorder.freeBytes()
        if (free < settings.guards.stopFreeBytes) {
            manifest.append(
                Degradation(
                    startedAt,
                    "disk",
                    "only ${free / (1024 * 1024)}MB free at session start, below the " +
                        "${settings.guards.stopFreeBytes / (1024 * 1024)}MB stop threshold",
                )
            )
        } else if (free < settings.guards.degradeFreeBytes) {
            manifest.append(
                Degradation(
                    startedAt,
                    "disk",
                    "only ${free / (1024 * 1024)}MB free at session start; capture starts degraded",
                )
            )
        }

        return recorder
    }

    /**
     * External app-specific storage when it is mounted and genuinely writable,
     * internal storage otherwise.
     *
     * `canWrite()` is not enough on its own -- a read-only remount reports a
     * mounted volume with a writable-looking directory -- so the check is an
     * actual write. It costs one file once per session and it is the difference
     * between discovering the problem now and discovering it nine hours later.
     */
    private fun chooseRoot(context: Context, notes: MutableList<String>): File {
        val external = try {
            context.getExternalFilesDir(null)
        } catch (t: Throwable) {
            notes += "getExternalFilesDir failed: ${t.javaClass.simpleName}: ${t.message}"
            null
        }
        if (external == null) {
            notes += "no external app-specific directory; falling back to internal storage"
            return context.filesDir
        }
        val state = try {
            Environment.getExternalStorageState(external)
        } catch (t: Throwable) {
            "unknown(${t.javaClass.simpleName})"
        }
        if (state != Environment.MEDIA_MOUNTED) {
            notes += "external storage state=$state; falling back to internal storage"
            return context.filesDir
        }
        if (!probeWritable(external)) {
            notes += "external storage ${external.absolutePath} is not writable; " +
                "falling back to internal storage"
            return context.filesDir
        }
        return external
    }

    private fun probeWritable(dir: File): Boolean = try {
        if (!dir.exists()) dir.mkdirs()
        val probe = File(dir, ".biomon_write_probe")
        probe.writeBytes(byteArrayOf(0))
        probe.delete()
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * Next free `DDMMYY_N` for today, by looking at what is already on disk.
     *
     * The date is the local calendar day, not the UTC one: the ID is a label for
     * the day the rig was deployed and the laptop pipeline's existing sessions
     * are named that way. Everything *inside* the session -- frame timestamps,
     * manifest times -- is UTC, and for any daylight deployment in this timezone
     * the two agree anyway.
     *
     * Reusing an existing index would silently interleave two deployments in one
     * directory, so the index is advanced past anything that already exists even
     * if the scan came back empty because the directory could not be listed.
     */
    private fun allocateSessionId(sessionsRoot: File, notes: MutableList<String>): String {
        if (!sessionsRoot.exists() && !sessionsRoot.mkdirs()) {
            notes += "could not create ${sessionsRoot.absolutePath}"
        }
        val today = try {
            LocalDate.now()
        } catch (t: Throwable) {
            LocalDate.ofEpochDay(System.currentTimeMillis() / 86_400_000L)
        }
        val prefix = FrameNaming.sessionId(today, 0).substringBeforeLast('_') + "_"

        var next = 0
        val existing = try {
            sessionsRoot.list()
        } catch (t: Throwable) {
            null
        }
        if (existing == null) {
            notes += "could not list ${sessionsRoot.absolutePath}; session index may restart"
        } else {
            for (name in existing) {
                if (!name.startsWith(prefix)) continue
                val index = name.substring(prefix.length).toIntOrNull() ?: continue
                if (index >= next) next = index + 1
            }
        }
        // Belt and braces: never hand back a directory that is already there.
        while (File(sessionsRoot, prefix + next).exists() && next < 10_000) next++
        return prefix + next
    }

    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (t: Throwable) {
        "unknown"
    }
}
