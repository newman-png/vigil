package dev.vigil.core.engine

import dev.vigil.core.plan.Prayer
import java.text.Normalizer

/**
 * App settings for the `adhan` trigger. The app does not compute prayer times; it waits for the user's
 * adhan app to post its notification and reads the prayer name from it.
 */
data class AdhanConfig(
    /** Package names of the adhan app. Empty means "any app" (then [requiredPhrase] does the filtering). */
    val packages: Set<String>,
    /** Text that must appear before the prayer name, e.g. "time for" in "It's time for Isha". Empty = none. */
    val requiredPhrase: String,
    /** Words that identify each prayer in the notification text. */
    val keywords: Map<Prayer, List<String>>,
) {
    companion object {
        val DEFAULT_KEYWORDS: Map<Prayer, List<String>> = mapOf(
            Prayer.FAJR to listOf("fajr", "fajar", "subh", "subuh", "sobh"),
            Prayer.DHUHR to listOf("dhuhr", "zuhr", "duhr", "zohr", "dhuhur", "zuhur", "dhur", "jumuah", "jummah", "jumua", "jumah"),
            Prayer.ASR to listOf("asr", "asar"),
            Prayer.MAGHRIB to listOf("maghrib", "magrib", "maghreb"),
            Prayer.ISHA to listOf("isha", "ishaa", "esha"),
        )

        /** Works out of the box with notifications like "It's time for Isha". */
        val DEFAULT = AdhanConfig(packages = emptySet(), requiredPhrase = "time for", keywords = DEFAULT_KEYWORDS)
    }
}

object AdhanMatcher {
    /**
     * Returns the prayer a notification announces, or null when it does not match.
     * Matching ignores case, accents and punctuation, and only accepts whole words, so "Isha in 15 minutes"
     * does not match when the required phrase is "time for", and "Asr" does not match inside "Nasr".
     */
    fun match(config: AdhanConfig, packageName: String, title: String?, text: String?): Prayer? {
        if (config.packages.isNotEmpty() && packageName !in config.packages) return null
        val haystack = normalize(listOfNotNull(title, text).joinToString(" "))
        if (haystack.isBlank()) return null

        val phrase = normalize(config.requiredPhrase)
        val searchFrom = if (phrase.isBlank()) {
            0
        } else {
            val at = findWord(haystack, phrase, 0)
            if (at < 0) return null
            at + phrase.length
        }

        var best: Prayer? = null
        var bestAt = Int.MAX_VALUE
        for ((prayer, words) in config.keywords) {
            for (word in words) {
                val w = normalize(word)
                if (w.isBlank()) continue
                val at = findWord(haystack, w, searchFrom)
                if (at in 0 until bestAt) {
                    best = prayer
                    bestAt = at
                }
            }
        }
        return best
    }

    /** Lowercase, strip accents and apostrophes, turn every run of other non-letters/digits into one space. */
    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        var lastSpace = true
        for (ch in decomposed) {
            when {
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> Unit
                // Apostrophes inside words ("It's", "Jumu‘ah", "ʿIshāʾ") are dropped rather than splitting the word.
                ch in APOSTROPHES -> Unit
                ch.isLetterOrDigit() -> {
                    sb.append(ch.lowercaseChar())
                    lastSpace = false
                }
                !lastSpace -> {
                    sb.append(' ')
                    lastSpace = true
                }
            }
        }
        return sb.toString().trim()
    }

    /** Index of [word] in [text] at or after [from] on word boundaries, or -1. Both must be normalized. */
    private fun findWord(text: String, word: String, from: Int): Int {
        var i = text.indexOf(word, from)
        while (i >= 0) {
            val beforeOk = i == 0 || text[i - 1] == ' '
            val end = i + word.length
            val afterOk = end == text.length || text[end] == ' '
            if (beforeOk && afterOk) return i
            i = text.indexOf(word, i + 1)
        }
        return -1
    }

    /** ' ` ´ ‘ ’ and the modifier letters ʻ ʼ ʽ ʾ ʿ that transliterations use for ʿayn and hamza. */
    private const val APOSTROPHES = "'`\u00B4\u2018\u2019\u02BB\u02BC\u02BD\u02BE\u02BF"
}

/** Ignores repeats of the same key within a time window (adhan apps often re-post or update a notification). */
class Debouncer(private val windowMs: Long) {
    private val last = mutableMapOf<String, Long>()

    /** True if [key] should be acted on at [nowMs]; records it when true. */
    fun accept(key: String, nowMs: Long): Boolean {
        val prev = last[key]
        if (prev != null && nowMs - prev in 0 until windowMs) return false
        last[key] = nowMs
        return true
    }
}
