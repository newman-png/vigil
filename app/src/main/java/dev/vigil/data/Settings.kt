package dev.vigil.data

import android.content.Context
import dev.vigil.core.engine.AdhanConfig
import dev.vigil.core.protocol.Prayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App settings: things about this phone (adhan app, takeover effects, bookkeeping). Everything about the day lives in
 * protocols.json, MY TIMES and the engine state instead.
 */
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

    /** Takeover sound (full intensity). */
    var soundEnabled: Boolean
        get() = prefs.getBoolean("sound", true)
        set(value) = edit { putBoolean("sound", value) }

    /** Takeover vibration (full intensity). */
    var vibrationEnabled: Boolean
        get() = prefs.getBoolean("vibration", true)
        set(value) = edit { putBoolean("vibration", value) }

    /** Nothing Glyph lights (full intensity). */
    var glyphEnabled: Boolean
        get() = prefs.getBoolean("glyph", true)
        set(value) = edit { putBoolean("glyph", value) }

    /** Package names of the adhan app; empty = any app (the required phrase filters). Also the APPS button's adhan. */
    var adhanPackages: Set<String>
        get() = prefs.getStringSet("adhan_packages", emptySet())?.toSet() ?: emptySet()
        set(value) = edit { putStringSet("adhan_packages", value) }

    /** The app whose notification last announced a prayer: the APPS button's adhan app when [adhanPackages] is empty. */
    var lastAdhanPackage: String?
        get() = prefs.getString("last_adhan_package", null)
        set(value) = edit { if (value == null) remove("last_adhan_package") else putString("last_adhan_package", value) }

    /** The adhan app(s) for the APPS button: the chosen ones, else the one that last announced a prayer. */
    val adhanApps: Set<String>
        get() = adhanPackages.ifEmpty { setOfNotNull(lastAdhanPackage) }

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

    /** After the takeover's CALL button: no takeover comes back before this time (epoch ms). Survives restarts. */
    var stepAsideUntilMs: Long
        get() = prefs.getLong("step_aside_until_ms", 0)
        set(value) = edit { putLong("step_aside_until_ms", value) }

    /** True while VIGIL has Do Not Disturb on (the Jummah lock): it must turn it off again. Survives restarts. */
    var dndByVigil: Boolean
        get() = prefs.getBoolean("dnd_by_vigil", false)
        set(value) = edit { putBoolean("dnd_by_vigil", value) }

    /** The interruption filter the user had before VIGIL turned Do Not Disturb on (restored when it goes off). */
    var dndPrevious: Int
        get() = prefs.getInt("dnd_previous", 0)
        set(value) = edit { putInt("dnd_previous", value) }

    /** The newest process exit (ApplicationExitInfo timestamp, epoch ms) already journaled; 0 = none seen yet. */
    var lastExitSeenMs: Long
        get() = prefs.getLong("last_exit_seen_ms", 0)
        set(value) = edit { putLong("last_exit_seen_ms", value) }

    private fun splitWords(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private inline fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        changed()
    }
}
