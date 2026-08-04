package dk.biomon.insect.store

import dk.biomon.insect.core.manifest.ErrorRecord
import dk.biomon.insect.core.manifest.ManifestRecord
import java.io.File
import java.io.FileOutputStream

/**
 * The append-only JSON Lines manifest.
 *
 * Every record is written, flushed and `fsync`ed before [append] returns. That is
 * not caution, it is the whole design: the session ends abruptly by default -- a
 * dead battery, a pulled cable, an OS kill -- and a record still sitting in a
 * buffer at that moment never existed. A power cut costs at most the line being
 * written (DESIGN.md 4).
 *
 * The cost is one fsync per record. At 4fps of triggered capture that is a few
 * per second against a file measured in kilobytes, which is nothing next to the
 * 4MB JPEG going down beside it.
 *
 * Nothing here throws. Losing the manifest is bad; losing the rest of the day
 * because the manifest could not be written is worse. Failures are counted, and
 * if the file ever comes back -- a transiently full disk, a volume that
 * remounted -- the first successful write says how many records went missing, so
 * a gap in the manifest is never mistaken for a quiet period in the field.
 */
class ManifestWriter(private val file: File) {

    private val lock = Any()
    private var stream: FileOutputStream? = null

    /** Records lost since the last successful write; reported once on recovery. */
    private var lostSinceRecovery = 0L
    private var lastFailure: String? = null

    /** Total records that could not be written, for the closing diagnostics. */
    var lostRecords: Long = 0L
        private set

    /**
     * Append one record. Returns false if it could not be written, which the
     * caller is free to ignore -- the failure is already accounted for here.
     */
    fun append(record: ManifestRecord): Boolean {
        val line = try {
            record.toJsonLine()
        } catch (t: Throwable) {
            // A record that cannot serialise is a bug, but not one worth taking
            // the deployment down for.
            synchronized(lock) { lostRecords++ }
            return false
        }
        synchronized(lock) {
            reportRecoveryLocked(record.atMillis)
            val ok = writeLocked(line)
            if (!ok) {
                lostRecords++
                lostSinceRecovery++
            }
            return ok
        }
    }

    /**
     * If earlier writes failed and this one is about to succeed, say so first, so
     * the gap is documented in the manifest rather than only in the absence of
     * lines. Non-negotiable #3: nothing degrades silently.
     */
    private fun reportRecoveryLocked(atMillis: Long) {
        if (lostSinceRecovery == 0L) return
        val notice = ErrorRecord(
            atMillis = atMillis,
            component = "manifest",
            message = "$lostSinceRecovery record(s) lost: ${lastFailure ?: "unknown IO failure"}",
            recovered = true,
        )
        val line = try {
            notice.toJsonLine()
        } catch (t: Throwable) {
            return
        }
        if (writeLocked(line)) {
            lostSinceRecovery = 0
            lastFailure = null
        }
    }

    private fun writeLocked(line: String): Boolean = try {
        val out = stream ?: openLocked()
        out.write(line.toByteArray(Charsets.UTF_8))
        out.write(NEWLINE)
        out.flush()
        out.fd.sync()
        true
    } catch (t: Throwable) {
        lastFailure = "${t.javaClass.simpleName}: ${t.message}"
        // Drop the handle so the next append reopens: the failure may have been
        // a volume that has since come back, and a dead descriptor never will.
        closeStreamLocked()
        false
    }

    private fun openLocked(): FileOutputStream {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val opened = FileOutputStream(file, true)
        stream = opened
        return opened
    }

    private fun closeStreamLocked() {
        try {
            stream?.close()
        } catch (t: Throwable) {
            // Nothing useful to do with a failure to close a failed stream.
        }
        stream = null
    }

    /**
     * Release the handle. Not required for durability -- every line is already on
     * disk -- so it is safe never to be called, which is the normal case.
     */
    fun close() {
        synchronized(lock) { closeStreamLocked() }
    }

    private companion object {
        val NEWLINE = byteArrayOf('\n'.code.toByte())
    }
}
