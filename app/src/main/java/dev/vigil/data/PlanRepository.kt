package dev.vigil.data

import android.content.Context
import dev.vigil.core.plan.Issue
import dev.vigil.core.plan.ParseResult
import dev.vigil.core.plan.Plan
import dev.vigil.core.plan.PlanParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** The last good plan, kept in private storage. A rejected import never replaces it. */
class PlanRepository(context: Context) {
    data class Snapshot(
        val plan: Plan?,
        val warnings: List<Issue>,
        val importedAtMs: Long?,
        val fileName: String?,
        /** Set when the stored plan could not be read back (e.g. after an app update tightened a rule). */
        val loadError: String?,
    )

    sealed interface ImportOutcome {
        data class Accepted(val plan: Plan, val warnings: List<Issue>) : ImportOutcome
        data class Rejected(val errors: List<Issue>, val warnings: List<Issue>) : ImportOutcome
    }

    private val dir = File(context.filesDir, "plan")
    private val currentFile = File(dir, "current.json")
    private val previousFile = File(dir, "previous.json")
    private val prefs = context.getSharedPreferences("plan", Context.MODE_PRIVATE)
    private val lock = Any()

    private val _snapshot = MutableStateFlow(load())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    val plan: Plan? get() = _snapshot.value.plan

    /** The running plan's JSON exactly as it was imported (for the weekly bundle), or null if there is none. */
    fun planText(): String? = synchronized(lock) {
        // After a failed reload the previous plan runs (see load()), so that is the one to report.
        val snap = _snapshot.value
        val file = if (snap.plan != null && snap.loadError != null && snap.importedAtMs == null) previousFile else currentFile
        if (snap.plan == null || !file.exists()) null else runCatching { file.readText() }.getOrNull()
    }

    /** Validates [text]; only a valid plan is saved and activated. */
    fun import(text: String, fileName: String?, nowMs: Long): ImportOutcome = synchronized(lock) {
        when (val result = PlanParser.parse(text)) {
            is ParseResult.Failure -> ImportOutcome.Rejected(result.errors, result.warnings)
            is ParseResult.Success -> {
                dir.mkdirs()
                if (currentFile.exists()) currentFile.copyTo(previousFile, overwrite = true)
                val tmp = File(dir, "current.json.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(currentFile)) {
                    currentFile.writeText(text)
                    tmp.delete()
                }
                prefs.edit().putLong(KEY_IMPORTED_AT, nowMs).putString(KEY_FILE_NAME, fileName).commit()
                _snapshot.value = Snapshot(result.plan, result.warnings, nowMs, fileName, null)
                ImportOutcome.Accepted(result.plan, result.warnings)
            }
        }
    }

    private fun load(): Snapshot {
        val importedAt = prefs.getLong(KEY_IMPORTED_AT, -1).takeIf { it >= 0 }
        val fileName = prefs.getString(KEY_FILE_NAME, null)
        if (!currentFile.exists()) return Snapshot(null, emptyList(), null, null, null)
        val current = PlanParser.parse(currentFile.readText())
        if (current is ParseResult.Success) return Snapshot(current.plan, current.warnings, importedAt, fileName, null)
        // An app update can tighten a rule; then the previous good plan (with its own fallback) beats none at all.
        val reason = (current as ParseResult.Failure).errors.firstOrNull()
        val previous = previousFile.takeIf { it.exists() }?.let { PlanParser.parse(it.readText()) }
        if (previous is ParseResult.Success) {
            return Snapshot(
                previous.plan, previous.warnings, null, null,
                "The latest plan can no longer be read ($reason), so the previous one is running. Import a new plan.",
            )
        }
        return Snapshot(null, emptyList(), importedAt, fileName, "The saved plan can no longer be read: $reason. Import a new plan.")
    }

    private companion object {
        const val KEY_IMPORTED_AT = "imported_at_ms"
        const val KEY_FILE_NAME = "file_name"
    }
}
