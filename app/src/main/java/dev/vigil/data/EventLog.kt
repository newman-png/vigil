package dev.vigil.data

import android.content.Context
import android.util.Log
import dev.vigil.core.log.LogEvent
import dev.vigil.core.log.LogJson
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Append-only event log, one JSON object per line, in the app's private storage. */
class EventLog(context: Context) {
    private val file = File(context.filesDir, "log/events.jsonl")
    private val lock = Any()

    /**
     * Appends [events]. A failed write (storage full) is logged and dropped rather than thrown: the engine step
     * it belongs to has already been saved, and losing a log line is better than crashing a takeover.
     */
    fun append(events: List<LogEvent>) {
        if (events.isEmpty()) return
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                file.appendText(events.joinToString(separator = "\n", postfix = "\n") { LogJson.encodeLine(it) })
            } catch (e: IOException) {
                Log.w(TAG, "Could not append to the log: ${e.message}")
            }
        }
    }

    fun append(event: LogEvent) = append(listOf(event))

    /** Every event. Only reading the file holds the lock, so a long log never blocks the engine while it decodes. */
    fun readAll(): List<LogEvent> {
        val text = synchronized(lock) { if (file.exists()) file.readText() else "" }
        return LogJson.decodeLines(text)
    }

    /** The most recent [count] events, newest last. Reads only the end of the file. */
    fun tail(count: Int): List<LogEvent> {
        val text = synchronized(lock) {
            if (!file.exists()) return emptyList()
            RandomAccessFile(file, "r").use { f ->
                val start = (f.length() - TAIL_BYTES).coerceAtLeast(0)
                val bytes = ByteArray((f.length() - start).toInt())
                f.seek(start)
                f.readFully(bytes)
                // Starting mid-file, the first line is usually cut: drop it.
                bytes.decodeToString().let { if (start > 0) it.substringAfter('\n', "") else it }
            }
        }
        return LogJson.decodeLines(text).takeLast(count)
    }

    private companion object {
        const val TAG = "VigilLog"

        /** Enough for well over a dozen events (one is usually under 400 bytes). */
        const val TAIL_BYTES = 32 * 1024L
    }
}
