package dk.biomon.insect.store

import android.content.Context
import android.os.Build
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
    /** Human-readable, written as the session runs, pasteable into a chat. */
    const val SUMMARY_FILE = "SUMMARY.md"
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
 * The preferred root is `/storage/emulated/0/Biomon`, in shared storage. Two
 * reasons, both learned the hard way rather than chosen for elegance: an
 * app-specific directory is **deleted when the app is uninstalled**, so a
 * reinstall between deployments would take a day's frames with it; and it is
 * awkward to reach over USB, where `Android/data` is hidden or blocked by most
 * hosts, while a top-level `Biomon/` folder appears immediately under the
 * phone's MTP root.
 *
 * The cost is that shared storage needs All Files Access, which is granted by
 * hand in system settings. If it has not been granted the session still runs,
 * falling back to app-specific storage and recording the fact -- the frames are
 * worth more than the tidiness of where they land, and the manifest says which
 * happened.
 *
 * Nothing here throws. A deployment that cannot allocate storage still gets a
 * recorder, still runs, and still writes down why it is failing -- an evening
 * with a manifest full of storage errors is data; an evening with a crashed
 * service is nothing at all.
 */
object SessionStore {

    /**
     * Session root, under DCIM.
     *
     * DCIM rather than a top-level folder because DCIM is what the media scanner
     * and MTP both expect to contain images: a folder here shows up in the
     * gallery and over USB as soon as its files are scanned, which is how the
     * frames come off the device. It also survives uninstalling the app, unlike
     * app-specific storage.
     */
    const val SHARED_ROOT_NAME = "DCIM/Biomon"

    /**
     * Where sessions will be written, for the UI to show before a session starts.
     * Same decision procedure as [open], without creating anything.
     */
    fun plannedRoot(context: Context): File = sessionsRoot(chooseRoot(context, ArrayList()))

    /**
     * Sessions sit directly under the shared root -- `DCIM/Biomon/<id>/` -- but
     * keep the `sessions/` level under app-specific storage, where the root is
     * shared with whatever else the app might put there.
     */
    private fun sessionsRoot(root: File): File =
        if (root.absolutePath.endsWith(SHARED_ROOT_NAME)) root
        else File(root, SessionLayout.SESSIONS_DIR)

    /** True when sessions will land in shared storage rather than the fallback. */
    fun hasSharedStorage(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun open(context: Context, settings: AppSettings): SessionRecorder {
        val startedAt = System.currentTimeMillis()
        // Notes accumulate before there is anywhere to write them; they go into
        // the manifest as soon as one exists, so a fallback is never invisible.
        val notes = ArrayList<String>(2)

        val root = chooseRoot(context, notes)
        val sessionsRoot = sessionsRoot(root)
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

        val scanner = if (hasSharedStorage()) MediaScanner(context) else null
        val recorder = FileSessionRecorder(session, manifest, database, scanner)

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
    /**
     * Shared storage first, app-specific storage as the fallback.
     *
     * `canWrite()` is not enough on its own -- a read-only remount reports a
     * mounted volume with a writable-looking directory -- so the check is an
     * actual write. It costs one file once per session and it is the difference
     * between discovering the problem now and discovering it nine hours later.
     */
    private fun chooseRoot(context: Context, notes: MutableList<String>): File {
        if (hasSharedStorage()) {
            val shared = File(Environment.getExternalStorageDirectory(), SHARED_ROOT_NAME)
            if (probeWritable(shared)) return shared
            notes += "All Files Access is granted but ${shared.absolutePath} is not " +
                "writable; falling back to app-specific storage"
        } else {
            notes += "All Files Access not granted: sessions are going to " +
                "app-specific storage, which is DELETED IF THE APP IS UNINSTALLED " +
                "and will not appear over MTP. Grant it and restart the session to " +
                "write to /" + SHARED_ROOT_NAME + " instead."
        }

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
