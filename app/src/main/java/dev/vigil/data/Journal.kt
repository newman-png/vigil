package dev.vigil.data

import android.content.Context
import android.util.Log
import dev.vigil.core.journal.Entry
import dev.vigil.core.journal.JournalJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The v2 journal (design 2.3): append-only JSON Lines in the app's private storage, written by the engine's steps.
 * The report and the CSV export are built from it.
 */
class Journal(context: Context) {
    private val file = File(context.filesDir, "journal/journal.jsonl")
    private val lock = Any()

    /**
     * Appends [entries]. A failed write (storage full) is logged and dropped rather than thrown: the engine step it
     * belongs to has already been saved, and losing a journal line is better than crashing a takeover.
     */
    fun append(entries: List<Entry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).use { out ->
                    out.write(JournalJson.encodeLines(entries).toByteArray(Charsets.UTF_8))
                    // The process may be killed right after a step; the lines should be on disk by then.
                    out.fd.sync()
                }
            } catch (e: IOException) {
                Log.w(TAG, "Could not append to the journal: ${e.message}")
            }
        }
    }

    /** Every entry. Only reading the file holds the lock, so a long journal never blocks the engine while it decodes. */
    fun readAll(): List<Entry> {
        val text = synchronized(lock) {
            try {
                if (file.exists()) file.readText() else ""
            } catch (e: IOException) {
                Log.w(TAG, "Could not read the journal: ${e.message}")
                ""
            }
        }
        return JournalJson.decodeLines(text)
    }

    /** The most recent [count] entries, newest last. Reads only the end of the file. */
    fun tail(count: Int): List<Entry> {
        val text = synchronized(lock) {
            try {
                if (!file.exists()) return emptyList()
                RandomAccessFile(file, "r").use { f ->
                    val start = (f.length() - TAIL_BYTES).coerceAtLeast(0)
                    val bytes = ByteArray((f.length() - start).toInt())
                    f.seek(start)
                    f.readFully(bytes)
                    // Starting mid-file, the first line is usually cut: drop it.
                    bytes.decodeToString().let { if (start > 0) it.substringAfter('\n', "") else it }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Could not read the journal: ${e.message}")
                ""
            }
        }
        return JournalJson.decodeLines(text).takeLast(count)
    }

    private companion object {
        const val TAG = "VigilJournal"

        /** Enough for well over a hundred entries (one is usually under 300 bytes). */
        const val TAIL_BYTES = 64 * 1024L
    }
}
