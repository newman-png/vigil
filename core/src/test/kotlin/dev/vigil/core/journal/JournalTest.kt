package dev.vigil.core.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalTest {
    private val a = Entry("2026-10-19T12:30:00+02:00", EntryType.ADHAN, prayer = "dhuhr")
    private val b = Entry(
        "2026-10-19T12:30:00+02:00", EntryType.FIRED, protocol = "prayer-dhuhr", run = "run-1", version = "full", stage = 1,
        reason = "adhan",
    )
    private val c = Entry("2026-10-19T12:40:00+02:00", EntryType.STEP_DONE, protocol = "x", run = "run-2", step = 0, text = "line one\nline \"two\"")

    @Test
    fun roundTrip() {
        val text = JournalJson.encodeLines(listOf(a, b, c))
        assertEquals(3, text.lines().count { it.isNotEmpty() })
        assertTrue(text.endsWith("\n"))
        assertEquals(listOf(a, b, c), JournalJson.decodeLines(text))
    }

    @Test
    fun tsIsTheFirstFieldAndAbsentFieldsAreLeftOut() {
        assertEquals("""{"ts":"2026-10-19T12:30:00+02:00","type":"adhan","prayer":"dhuhr"}""", JournalJson.encodeLine(a))
    }

    @Test
    fun damagedLinesAreSkipped() {
        val text = JournalJson.encodeLine(a) + "\n" +
            "{\"ts\":\"2026-10-19T12:31:00+02:00\",\"type\":\"fir" + "\n" + // cut short by a crash
            "\n" +
            "garbage" + JournalJson.encodeLine(b) + "\n" + // junk left by a power cut, then a whole entry
            JournalJson.encodeLine(c).dropLast(3) + JournalJson.encodeLine(a) + "\n"
        assertEquals(listOf(a, b, a), JournalJson.decodeLines(text))
    }

    @Test
    fun unknownFieldsFromANewerAppAreIgnored() {
        val line = """{"ts":"2026-10-19T12:30:00+02:00","type":"adhan","prayer":"dhuhr","mood":"good"}"""
        assertEquals(listOf(a), JournalJson.decodeLines(line))
    }

    @Test
    fun everyTypeIsDescribed() {
        val constants = EntryType::class.java.declaredFields
            .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.get(null) as String }
        assertTrue(constants.size >= 37)
        constants.forEach { assertTrue("$it has no description", it in EntryType.DESCRIPTIONS) }
    }
}
