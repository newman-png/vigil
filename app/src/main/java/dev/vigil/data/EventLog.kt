package dev.vigil.data

import android.content.Context
import dev.vigil.core.log.LogEvent
import dev.vigil.core.log.LogJson
import java.io.File

/** Append-only event log, one JSON object per line, in the app's private storage. */
class EventLog(context: Context) {
    private val file = File(context.filesDir, "log/events.jsonl")
    private val lock = Any()

    fun append(events: List<LogEvent>) {
        if (events.isEmpty()) return
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.appendText(events.joinToString(separator = "\n", postfix = "\n") { LogJson.encodeLine(it) })
        }
    }

    fun append(event: LogEvent) = append(listOf(event))

    fun readAll(): List<LogEvent> = synchronized(lock) {
        if (!file.exists()) emptyList() else LogJson.decodeLines(file.readText())
    }

    /** The most recent [count] events, newest last. */
    fun tail(count: Int): List<LogEvent> = readAll().takeLast(count)
}
