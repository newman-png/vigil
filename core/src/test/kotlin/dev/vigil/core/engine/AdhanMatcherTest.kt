package dev.vigil.core.engine

import dev.vigil.core.plan.Prayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdhanMatcherTest {
    private val default = AdhanConfig.DEFAULT
    private val anyPhrase = AdhanConfig.DEFAULT.copy(requiredPhrase = "")

    private fun match(title: String?, text: String? = null, config: AdhanConfig = default, pkg: String = "com.example.adhan") =
        AdhanMatcher.match(config, pkg, title, text)

    @Test
    fun itsTimeForIsha() {
        assertEquals(Prayer.ISHA, match("It's time for Isha"))
        assertEquals(Prayer.ISHA, match(null, "It's time for Isha"))
        assertEquals(Prayer.ISHA, match("It’s time for Isha")) // curly apostrophe
        assertEquals(Prayer.ISHA, match("IT'S TIME FOR ISHA"))
        assertEquals(Prayer.ISHA, match("it's time for isha prayer 🕌"))
    }

    @Test
    fun everyPrayerHasADefaultName() {
        assertEquals(Prayer.FAJR, match("It's time for Fajr"))
        assertEquals(Prayer.DHUHR, match("It's time for Dhuhr"))
        assertEquals(Prayer.ASR, match("It's time for Asr"))
        assertEquals(Prayer.MAGHRIB, match("It's time for Maghrib"))
        assertEquals(Prayer.ISHA, match("It's time for Isha"))
    }

    @Test
    fun phraseAndNameMaySitInTitleAndText() {
        assertEquals(Prayer.ISHA, match("It's time for", "Isha"))
        assertEquals(Prayer.ISHA, match("Isha", "It's time for Isha prayer"))
        assertEquals(Prayer.MAGHRIB, match("Maghrib 18:42", "Time for Maghrib in London"))
        // The name only before the phrase is not enough.
        assertNull(match("Isha", "It's time for prayer"))
    }

    @Test
    fun reminderBeforeTheAdhanDoesNotMatchTheDefaultPhrase() {
        assertNull(match("Isha in 15 minutes"))
        assertNull(match("Upcoming prayer", "Isha in 15 minutes"))
        assertEquals(Prayer.ISHA, match("Isha in 15 minutes", config = anyPhrase))
    }

    @Test
    fun requiredPhraseIsMatchedOnWholeWords() {
        assertNull(match("Overtime for Isha"))
        assertNull(match("It's time forward Isha"))
    }

    @Test
    fun requiredPhraseIsNormalizedLikeTheText() {
        val config = default.copy(requiredPhrase = "IT'S  TIME FOR")
        assertEquals(Prayer.ISHA, match("It’s time for Isha", config = config))
        assertNull(match("Time for Isha", config = config))
    }

    @Test
    fun packageFilter() {
        val config = default.copy(packages = setOf("com.mycompany.adhan"))
        assertEquals(Prayer.ISHA, match("It's time for Isha", config = config, pkg = "com.mycompany.adhan"))
        assertNull(match("It's time for Isha", config = config, pkg = "com.whatsapp"))
        // No packages configured: any app is accepted.
        assertEquals(Prayer.ISHA, match("It's time for Isha", pkg = "com.whatsapp"))
    }

    @Test
    fun emptyPhraseAcceptsAnyMentionOfAPrayer() {
        assertEquals(Prayer.MAGHRIB, match("Maghrib", config = anyPhrase))
        assertEquals(Prayer.FAJR, match("Adhan", "Fajr 05:12", config = anyPhrase))
        // A phrase made only of punctuation normalizes to nothing and behaves the same.
        assertEquals(Prayer.MAGHRIB, match("Maghrib", config = default.copy(requiredPhrase = " - ")))
        assertNull(match("Prayer reminder", config = anyPhrase))
    }

    @Test
    fun blankNotificationNeverMatches() {
        assertNull(match(null, null))
        assertNull(match("", "   ", config = anyPhrase))
        assertNull(match("!!!", "…", config = anyPhrase))
    }

    @Test
    fun jumuahCountsAsDhuhr() {
        assertEquals(Prayer.DHUHR, match("It's time for Jumu'ah"))
        assertEquals(Prayer.DHUHR, match("It's time for Jumu’ah"))
        assertEquals(Prayer.DHUHR, match("It's time for Jumu‘ah"))
        assertEquals(Prayer.DHUHR, match("It's time for Jumuʿah"))
        assertEquals(Prayer.DHUHR, match("It's time for Jummah"))
    }

    @Test
    fun accentsAndTransliterationMarksAreIgnored() {
        assertEquals(Prayer.ISHA, match("It's time for Ishā"))
        assertEquals(Prayer.ISHA, match("It's time for ʿIshāʾ"))
        assertEquals(Prayer.DHUHR, match("It's time for Ẓuhr"))
        assertEquals(Prayer.ASR, match("It's time for ʿAṣr"))
        assertEquals(Prayer.MAGHRIB, match("It's time for Maġrib", config = default.copy(keywords = mapOf(Prayer.MAGHRIB to listOf("magrib")))))
    }

    @Test
    fun namesMatchOnlyAsWholeWords() {
        assertNull(match("It's time for Nasr"))
        assertNull(match("It's time for Ishaq"))
        assertNull(match("It's time for Dhuha"))
        assertNull(match("It's time for Sunrise"))
        assertNull(match("It's time for Ishraq"))
        assertEquals(Prayer.ASR, match("It's time for Asr-prayer"))
        assertEquals(Prayer.ASR, match("Nasr: it's time for Asr"))
    }

    @Test
    fun firstNameAfterThePhraseWins() {
        assertEquals(Prayer.MAGHRIB, match("It's time for Maghrib. Isha at 20:15"))
        assertEquals(Prayer.ISHA, match("It's time for Isha", "Maghrib has ended"))
        // A name before the phrase does not count.
        assertEquals(Prayer.MAGHRIB, match("Isha at 20:15", "It's time for Maghrib"))
        // The phrase may appear more than once; the search continues after its first occurrence.
        assertEquals(Prayer.ASR, match("Time for prayer", "It's time for Asr"))
    }

    @Test
    fun customKeywordsAndPhrase() {
        val turkish = AdhanConfig(
            packages = emptySet(),
            requiredPhrase = "vakti",
            keywords = mapOf(Prayer.ISHA to listOf("Yatsı"), Prayer.FAJR to listOf("İmsak")),
        )
        assertEquals(Prayer.ISHA, AdhanMatcher.match(turkish, "tr.app", "Ezan vakti", "Yatsı namazı"))
        assertEquals(Prayer.FAJR, AdhanMatcher.match(turkish, "tr.app", "EZAN VAKTI: İMSAK", null))
        assertNull(AdhanMatcher.match(turkish, "tr.app", "It's time for Isha", null))
        val emptyWords = turkish.copy(keywords = mapOf(Prayer.ISHA to listOf("", " ")))
        assertNull(AdhanMatcher.match(emptyWords, "tr.app", "Ezan vakti Yatsı", null))
    }

    @Test
    fun defaultKeywordsAreCanonicalAndUnambiguous() {
        val all = AdhanConfig.DEFAULT_KEYWORDS
        assertEquals(Prayer.entries.toSet(), all.keys)
        for ((prayer, words) in all) {
            assertTrue("$prayer has no keywords", words.isNotEmpty())
            for (w in words) assertEquals("keyword \"$w\" is not normalized", AdhanMatcher.normalize(w), w)
        }
        val flat = all.values.flatten()
        assertEquals("a keyword is listed for two prayers", flat.size, flat.toSet().size)
    }

    @Test
    fun normalize() {
        assertEquals("its time for isha", AdhanMatcher.normalize("  It’s   TIME—for Ishā!! "))
        assertEquals("isha 19 45", AdhanMatcher.normalize("Isha (19:45)"))
        assertEquals("jumuah", AdhanMatcher.normalize("Jumu'ah"))
        assertEquals("jumuah", AdhanMatcher.normalize("Jumu‘ah"))
        assertEquals("isha", AdhanMatcher.normalize("ʿIshāʾ"))
        assertEquals("cafe", AdhanMatcher.normalize("CAFÉ"))
        assertEquals("", AdhanMatcher.normalize(""))
        assertEquals("", AdhanMatcher.normalize(" !?… "))
        assertEquals("a b", AdhanMatcher.normalize("a\n\tb"))
    }

    @Test
    fun debouncerIgnoresRepeatsWithinTheWindow() {
        val d = Debouncer(windowMs = 60_000)
        assertTrue(d.accept("isha", 1_000))
        assertFalse(d.accept("isha", 1_000))
        assertFalse(d.accept("isha", 60_999))
        // Other keys are independent.
        assertTrue(d.accept("fajr", 2_000))
        // Rejected repeats do not extend the window.
        assertTrue(d.accept("isha", 61_000))
        assertFalse(d.accept("isha", 100_000))
    }

    @Test
    fun debouncerWithZeroWindowAcceptsEverything() {
        val d = Debouncer(windowMs = 0)
        assertTrue(d.accept("isha", 5))
        assertTrue(d.accept("isha", 5))
    }

    @Test
    fun debouncerAcceptsAfterTheClockWentBack() {
        val d = Debouncer(windowMs = 60_000)
        assertTrue(d.accept("isha", 500_000))
        assertTrue(d.accept("isha", 100_000))
        assertFalse(d.accept("isha", 120_000))
    }
}
