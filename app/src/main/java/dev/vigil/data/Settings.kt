package dev.vigil.data

import android.content.Context
import dev.vigil.core.engine.AdhanConfig
import dev.vigil.core.plan.Prayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App settings: things about this phone, not about the week (those belong in the plan). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Bumped on every change so Compose screens can observe settings. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun changed() {
        _version.value++
    }

    var setupDone: Boolean
        get() = prefs.getBoolean("setup_done", false)
        set(value) = edit { putBoolean("setup_done", value) }

    /** May tier-3 directives marked quiet "override" ring during quiet mode? */
    var allowQuietOverride: Boolean
        get() = prefs.getBoolean("allow_quiet_override", true)
        set(value) = edit { putBoolean("allow_quiet_override", value) }

    var soundEnabled: Boolean
        get() = prefs.getBoolean("sound", true)
        set(value) = edit { putBoolean("sound", value) }

    var vibrationEnabled: Boolean
        get() = prefs.getBoolean("vibration", true)
        set(value) = edit { putBoolean("vibration", value) }

    var glyphEnabled: Boolean
        get() = prefs.getBoolean("glyph", true)
        set(value) = edit { putBoolean("glyph", value) }

    /** Package names of the adhan app; empty = any app (the required phrase filters). */
    var adhanPackages: Set<String>
        get() = prefs.getStringSet("adhan_packages", emptySet())?.toSet() ?: emptySet()
        set(value) = edit { putStringSet("adhan_packages", value) }

    var adhanPhrase: String
        get() = prefs.getString("adhan_phrase", null) ?: AdhanConfig.DEFAULT.requiredPhrase
        set(value) = edit { putString("adhan_phrase", value) }

    fun adhanKeywords(prayer: Prayer): List<String> =
        prefs.getString("adhan_kw_${prayer.json}", null)?.let(::splitWords)
            ?: AdhanConfig.DEFAULT_KEYWORDS.getValue(prayer)

    fun setAdhanKeywords(prayer: Prayer, words: String) = edit {
        val list = splitWords(words)
        if (list.isEmpty()) remove("adhan_kw_${prayer.json}") else putString("adhan_kw_${prayer.json}", list.joinToString(","))
    }

    fun resetAdhan() = edit {
        remove("adhan_packages")
        remove("adhan_phrase")
        Prayer.entries.forEach { remove("adhan_kw_${it.json}") }
    }

    val adhanConfig: AdhanConfig
        get() = AdhanConfig(
            packages = adhanPackages,
            requiredPhrase = adhanPhrase,
            keywords = Prayer.entries.associateWith { adhanKeywords(it) },
        )

    /** Developer kill switch: no takeovers until this time (epoch ms). */
    var suspendedUntilMs: Long
        get() = prefs.getLong("suspended_until_ms", 0)
        set(value) = edit { putLong("suspended_until_ms", value) }

    fun isSuspended(nowMs: Long): Boolean = nowMs < suspendedUntilMs

    /** After the takeover's CALL button: no takeover comes back before this time (epoch ms). Survives restarts. */
    var stepAsideUntilMs: Long
        get() = prefs.getLong("step_aside_until_ms", 0)
        set(value) = edit { putLong("step_aside_until_ms", value) }

    /** Import time (epoch ms) of the plan whose weekly bundle was exported last; 0 = never. */
    var lastBundleExportImportedAtMs: Long
        get() = prefs.getLong("last_bundle_export_imported_at_ms", 0)
        set(value) = edit { putLong("last_bundle_export_imported_at_ms", value) }

    /** When the last weekly bundle was exported (epoch ms); 0 = never. */
    var lastBundleExportAtMs: Long
        get() = prefs.getLong("last_bundle_export_at_ms", 0)
        set(value) = edit { putLong("last_bundle_export_at_ms", value) }

    private fun splitWords(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private inline fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        changed()
    }
}
