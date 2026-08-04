package dk.biomon.insect.store

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import dk.biomon.insect.SessionInfo
import dk.biomon.insect.core.blob.Blob
import java.io.File

/**
 * The session index: sessions, events and frames, in SQLite.
 *
 * **A convenience, not a dependency.** Filenames already carry session, event,
 * timestamp and sequence, so a lost or corrupt database costs query speed on the
 * laptop and nothing else (DESIGN.md 4). Everything here is therefore written to
 * fail quietly and permanently: the first error disables the database for the
 * rest of the session, reports itself once through [onFailure] so the manifest
 * records it, and never touches the capture path again.
 *
 * WAL is enabled explicitly and every statement runs in autocommit, so a
 * transaction is never held open across an event -- an abrupt end can cost the
 * last few commits and nothing earlier. `synchronous=NORMAL` is deliberate: in
 * WAL that is durable against the process dying, which is the failure this app
 * actually expects, and the manifest beside it is the fsynced record of record.
 */
class SessionDatabase(
    private val file: File,
    /** Called at most once per distinct failure, so the manifest records it. */
    private val onFailure: (String) -> Unit,
) {
    private val lock = Any()
    private var db: SQLiteDatabase? = null
    private var insertFrame: SQLiteStatement? = null
    private var reportedFailure = false

    /** False once the database has given up; capture is unaffected either way. */
    var available: Boolean = false
        private set

    init {
        synchronized(lock) { openLocked() }
    }

    private fun openLocked() {
        try {
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val opened = SQLiteDatabase.openOrCreateDatabase(file, null)
            if (!opened.enableWriteAheadLogging()) {
                // Older behaviour, or a database already in another journal mode.
                opened.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
            }
            opened.execSQL("PRAGMA synchronous=NORMAL")
            for (statement in SCHEMA) opened.execSQL(statement)
            db = opened
            available = true
        } catch (t: Throwable) {
            fail("open", t)
        }
    }

    fun sessionOpened(session: SessionInfo, appVersion: String) {
        exec(
            "INSERT OR REPLACE INTO sessions(session_id,started_at,directory,app_version) " +
                "VALUES(?,?,?,?)"
        ) { stmt ->
            stmt.bindString(1, session.sessionId)
            stmt.bindLong(2, session.startedAtMillis)
            stmt.bindString(3, session.directory.absolutePath)
            stmt.bindString(4, appVersion)
        }
    }

    fun sessionClosed(
        sessionId: String,
        atMillis: Long,
        reason: String,
        events: Long,
        frames: Long,
        bytes: Long,
    ) {
        exec(
            "UPDATE sessions SET ended_at=?,end_reason=?,events=?,frames=?,bytes=? " +
                "WHERE session_id=?"
        ) { stmt ->
            stmt.bindLong(1, atMillis)
            stmt.bindString(2, reason)
            stmt.bindLong(3, events)
            stmt.bindLong(4, frames)
            stmt.bindLong(5, bytes)
            stmt.bindString(6, sessionId)
        }
    }

    fun eventStarted(sessionId: String, eventId: Long, atMillis: Long) {
        exec(
            "INSERT OR REPLACE INTO events(session_id,event_id,started_at) VALUES(?,?,?)"
        ) { stmt ->
            stmt.bindString(1, sessionId)
            stmt.bindLong(2, eventId)
            stmt.bindLong(3, atMillis)
        }
    }

    fun eventEnded(
        sessionId: String,
        eventId: Long,
        atMillis: Long,
        frames: Int,
        reason: String,
        durationMillis: Long,
    ) {
        exec(
            "UPDATE events SET ended_at=?,frames=?,end_reason=?,duration_ms=? " +
                "WHERE session_id=? AND event_id=?"
        ) { stmt ->
            stmt.bindLong(1, atMillis)
            stmt.bindLong(2, frames.toLong())
            stmt.bindString(3, reason)
            stmt.bindLong(4, durationMillis)
            stmt.bindString(5, sessionId)
            stmt.bindLong(6, eventId)
        }
    }

    /**
     * One frame row. The hot path, so the statement is compiled once and kept;
     * the boxes travel as JSON in full-resolution coordinates, exactly as they do
     * in the manifest, because the phone never crops and the laptop re-derives
     * crops from these later.
     */
    fun frameWritten(
        sessionId: String,
        eventId: Long,
        sequence: Int,
        filename: String,
        atMillis: Long,
        mode: String,
        bytes: Long,
        blobs: List<Blob>,
    ) {
        synchronized(lock) {
            val database = db ?: return
            try {
                val stmt = insertFrame ?: database.compileStatement(INSERT_FRAME).also {
                    insertFrame = it
                }
                stmt.clearBindings()
                stmt.bindString(1, sessionId)
                stmt.bindLong(2, eventId)
                stmt.bindLong(3, sequence.toLong())
                stmt.bindString(4, filename)
                stmt.bindLong(5, atMillis)
                stmt.bindString(6, mode)
                stmt.bindLong(7, bytes)
                stmt.bindLong(8, blobs.size.toLong())
                stmt.bindString(9, boxesJson(blobs))
                stmt.executeInsert()
            } catch (t: Throwable) {
                fail("frame", t)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            try {
                insertFrame?.close()
            } catch (t: Throwable) {
                // Nothing to salvage.
            }
            insertFrame = null
            try {
                db?.close()
            } catch (t: Throwable) {
                // Nothing to salvage.
            }
            db = null
            available = false
        }
    }

    private inline fun exec(sql: String, bind: (SQLiteStatement) -> Unit) {
        synchronized(lock) {
            val database = db ?: return
            var stmt: SQLiteStatement? = null
            try {
                stmt = database.compileStatement(sql)
                bind(stmt)
                stmt.executeUpdateDelete()
            } catch (t: Throwable) {
                fail("write", t)
            } finally {
                try {
                    stmt?.close()
                } catch (t: Throwable) {
                    // Nothing to salvage.
                }
            }
        }
    }

    /**
     * Give up for the rest of the session. Retrying a broken SQLite handle on
     * every frame would turn a storage problem into a capture problem, which is
     * exactly the dependency this database is not allowed to become.
     */
    private fun fail(stage: String, t: Throwable) {
        available = false
        try {
            insertFrame?.close()
        } catch (ignored: Throwable) {
        }
        insertFrame = null
        try {
            db?.close()
        } catch (ignored: Throwable) {
        }
        db = null
        if (!reportedFailure) {
            reportedFailure = true
            val message = "index.db disabled at $stage: ${t.javaClass.simpleName}: ${t.message}; " +
                "frames and manifest are unaffected"
            try {
                onFailure(message)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun boxesJson(blobs: List<Blob>): String = buildString {
        append('[')
        for ((i, b) in blobs.withIndex()) {
            if (i > 0) append(',')
            append('[').append(b.left).append(',').append(b.top).append(',')
                .append(b.right).append(',').append(b.bottom).append(',')
                .append(b.areaPx).append(']')
        }
        append(']')
    }

    private companion object {
        const val INSERT_FRAME =
            "INSERT INTO frames(session_id,event_id,sequence,filename,timestamp,mode,bytes," +
                "blob_count,boxes) VALUES(?,?,?,?,?,?,?,?,?)"

        val SCHEMA = listOf(
            """
            CREATE TABLE IF NOT EXISTS sessions(
                session_id TEXT PRIMARY KEY,
                started_at INTEGER NOT NULL,
                directory TEXT NOT NULL,
                app_version TEXT,
                ended_at INTEGER,
                end_reason TEXT,
                events INTEGER,
                frames INTEGER,
                bytes INTEGER
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS events(
                session_id TEXT NOT NULL,
                event_id INTEGER NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                frames INTEGER,
                end_reason TEXT,
                duration_ms INTEGER,
                PRIMARY KEY(session_id, event_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS frames(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                event_id INTEGER NOT NULL,
                sequence INTEGER NOT NULL,
                filename TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                mode TEXT NOT NULL,
                bytes INTEGER NOT NULL,
                blob_count INTEGER NOT NULL,
                boxes TEXT NOT NULL
            )
            """.trimIndent(),
            // The two queries the laptop actually runs: frames of one event in
            // order, and everything in a time window.
            "CREATE INDEX IF NOT EXISTS idx_frames_event_seq ON frames(event_id, sequence)",
            "CREATE INDEX IF NOT EXISTS idx_frames_timestamp ON frames(timestamp)",
        )
    }
}
