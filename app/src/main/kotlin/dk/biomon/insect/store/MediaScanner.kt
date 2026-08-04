package dk.biomon.insect.store

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File

/**
 * Tells the media scanner about files as they are written.
 *
 * Writing a file into DCIM does not make it visible: MTP and the gallery both
 * read the media database, not the filesystem, so an unscanned session folder is
 * invisible over USB until something triggers a rescan -- historically a reboot.
 * That would be a miserable way to end a day in the field.
 *
 * Scans are **batched**. A scan is a binder round trip and a media-provider
 * insert; doing one per JPEG at 4fps would put real load on the capture path for
 * no benefit, since nobody is looking at the gallery mid-deployment. Batching to
 * a few seconds keeps the folder current without the churn.
 *
 * Nothing here throws. A phone that will not index its own storage is an
 * inconvenience at collection time; it is not a reason to stop capturing.
 */
class MediaScanner(
    private val context: Context,
    private val batchSize: Int = 32,
    private val flushIntervalMillis: Long = 5_000,
) {
    private val lock = Any()
    private val pending = ArrayList<String>(64)
    private var lastFlushMillis = 0L

    /** Queue a file. Flushed when the batch fills or the interval elapses. */
    fun add(file: File, nowMillis: Long = System.currentTimeMillis()) {
        val ready: Array<String>?
        synchronized(lock) {
            pending += file.absolutePath
            if (lastFlushMillis == 0L) lastFlushMillis = nowMillis
            val due = pending.size >= batchSize ||
                nowMillis - lastFlushMillis >= flushIntervalMillis
            ready = if (due) drainLocked(nowMillis) else null
        }
        ready?.let(::scan)
    }

    /** Force everything queued to be scanned now, e.g. at session end. */
    fun flush(nowMillis: Long = System.currentTimeMillis()) {
        val ready = synchronized(lock) { drainLocked(nowMillis) }
        scan(ready)
    }

    private fun drainLocked(nowMillis: Long): Array<String> {
        val out = pending.toTypedArray()
        pending.clear()
        lastFlushMillis = nowMillis
        return out
    }

    private fun scan(paths: Array<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(context, paths, null, null)
        } catch (t: Throwable) {
            // Indexing is a convenience. Losing it costs a manual rescan at the
            // laptop, not any data.
        }
    }
}
