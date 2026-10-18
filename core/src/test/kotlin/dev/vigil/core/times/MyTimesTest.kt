package dev.vigil.core.times

import dev.vigil.core.machine.ROME
import dev.vigil.core.machine.rome
import dev.vigil.core.protocol.SlotType
import dev.vigil.core.protocol.TimeBase
import dev.vigil.core.protocol.TimeExpr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class MyTimesTest {
    private val edge = LocalTime.of(5, 0)
    private val all = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    private fun day(date: String, times: MyTimes = MyTimes()) = DayTimes(LocalDate.parse(date), ROME, edge, times)

    private fun expr(text: String) = (TimeExpr.parse(text) as dev.vigil.core.protocol.Parsed.Ok).value

    private val timetable = MyTimes(
        wake = all.associateWith { "07:00" } + ("thu" to "06:45"),
        bedtimeGoal = "00:30",
        classes = listOf(
            Slot(listOf("tue", "fri"), "13:00", "14:30", label = "Analysis"),
            Slot(listOf("tue"), "14:30", "16:00", label = "Physics"),
            Slot(listOf("thu"), "08:30", minutes = 90, label = "Chemistry"),
        ),
        meals = listOf(Slot(all, "19:45", label = "dinner"), Slot(listOf("mon"), "12:30", label = "lunch")),
        study = listOf(Slot(listOf("mon", "wed"), "16:00", minutes = 100, label = "Analysis ch. 4")),
        calls = listOf(Slot(all, "13:00", label = "Mom")),
        commute = listOf(Slot(listOf("tue"), "11:45")),
    )

    @Test
    fun defaults() {
        val t = MyTimes()
        assertEquals(all.associateWith { "07:00" }, t.wake)
        assertEquals("23:00", t.bedtimeGoal)
        assertEquals("00:30", t.bedtimeStart)
        assertEquals(60, t.commuteMin)
        assertEquals("14:40", t.jummahTime)
        assertEquals(listOf("breakfast", "lunch", "dinner"), t.meals.map { it.label })
        assertTrue(t.problems().isEmpty())
    }

    @Test
    fun jsonRoundTripAndDamagedText() {
        assertEquals(timetable, MyTimes.decode(timetable.encode()))
        assertEquals(MyTimes(), MyTimes.decode("{not json"))
        assertEquals(MyTimes(), MyTimes.decode(null))
        // Unknown fields from a newer app are ignored.
        assertEquals("21:00", MyTimes.decode("""{"bedtime_goal":"21:00","colour":"red"}""").bedtimeGoal)
        val store = MyTimesStore(current = timetable, pending = MyTimes(), edited = true)
        assertEquals(store, MyTimesStore.decode(store.encode()))
        assertEquals(MyTimesStore(), MyTimesStore.decode("garbage"))
    }

    @Test
    fun problemsInPlainLanguage() {
        val bad = MyTimes(
            wake = mapOf("mon" to "7:00", "funday" to "08:00"),
            bedtimeGoal = "late",
            classes = listOf(Slot(emptyList(), "13:00"), Slot(listOf("xyz"), "25:00", "14:00")),
            meals = listOf(Slot(all, "12:00", label = "brunch")),
            gym = listOf(Slot(listOf("mon"), "18:00", minutes = 0)),
            commuteMin = 999,
        )
        val p = bad.problems()
        assertTrue(p.contains("Wake MON: \"7:00\" is not a time (HH:MM)."))
        assertTrue(p.contains("Wake: \"funday\" is not a day (use mon to sun)."))
        assertTrue(p.contains("Bedtime goal: \"late\" is not a time (HH:MM)."))
        assertTrue(p.contains("Classes row 1: pick at least one day."))
        assertTrue(p.contains("Classes row 1: a class needs an end or minutes."))
        assertTrue(p.contains("Classes row 2: \"xyz\" is not a day (use mon to sun)."))
        assertTrue(p.contains("Classes row 2: start \"25:00\" is not a time (HH:MM)."))
        assertTrue(p.contains("Meals row 1: the label must be breakfast, lunch or dinner."))
        assertTrue(p.contains("Gym row 1: 0 minutes is not from 1 to 600."))
        assertTrue(p.contains("Commute: 999 minutes is not from 0 to 300."))
    }

    @Test
    fun pendingBecomesCurrentAtDayStart() {
        val store = MyTimesStore().edit(timetable)
        assertEquals(MyTimes(), store.current)
        assertEquals(timetable, store.pending)
        assertEquals(timetable, store.upcoming)
        val next = store.atDayStart()
        assertEquals(timetable, next.current)
        assertNull(next.pending)
        assertEquals(next, next.atDayStart())
    }

    @Test
    fun firstEditMayApplyNowLaterOnesNever() {
        val first = MyTimesStore().edit(timetable, applyNow = true)
        assertEquals(timetable, first.current)
        assertNull(first.pending)
        assertTrue(first.edited)
        val second = first.edit(MyTimes(), applyNow = true)
        assertEquals(timetable, second.current)
        assertEquals(MyTimes(), second.pending)
        // Editing back to the current times leaves nothing pending.
        assertNull(second.edit(timetable).pending)
    }

    @Test
    fun perWeekdayResolution() {
        val mon = day("2026-10-19", timetable)
        assertEquals("mon", mon.dayCode)
        assertEquals(rome("2026-10-19T07:00"), mon.wake)
        assertEquals(rome("2026-10-22T06:45"), day("2026-10-22", timetable).wake)
        assertEquals(rome("2026-10-19T12:30"), mon.meal("lunch"))
        assertNull(day("2026-10-20", timetable).meal("lunch"))
        assertEquals(rome("2026-10-19T19:45"), mon.meal("DINNER"))
        assertTrue(mon.classes.isEmpty())
        assertNull(mon.classStart)
        assertNull(mon.resolve(expr("class_end+10")))
        val tue = day("2026-10-20", timetable)
        assertEquals(listOf("Analysis", "Physics"), tue.classes.map { it.label })
        assertEquals(rome("2026-10-20T13:00"), tue.classStart)
        assertEquals(rome("2026-10-20T16:00"), tue.classEnd)
        val thu = day("2026-10-22", timetable)
        assertEquals(rome("2026-10-22T10:00"), thu.classes.single().end)
    }

    @Test
    fun nightEdgePutsEarlyTimesAfterMidnight() {
        val mon = day("2026-10-19", timetable)
        // bedtime_goal 00:30 is the night after Monday.
        assertEquals(rome("2026-10-20T00:30"), mon.bedtime)
        assertEquals(rome("2026-10-19T23:30"), mon.resolve(expr("bedtime-60")))
        assertEquals(rome("2026-10-20T04:59"), mon.resolve(expr("04:59")))
        assertEquals(rome("2026-10-19T05:00"), mon.resolve(expr("05:00")))
        assertEquals(rome("2026-10-19T08:00"), mon.resolve(expr("wake+60")))
        assertEquals(rome("2026-10-19T20:30"), mon.resolve(expr("meal:dinner+45")))
        // wake is always the morning of the day, even before the night edge.
        val early = day("2026-10-19", MyTimes(wake = all.associateWith { "04:30" }))
        assertEquals(rome("2026-10-19T04:30"), early.wake)
        // A 23:00 goal stays on the day.
        assertEquals(rome("2026-10-19T23:00"), day("2026-10-19").bedtime)
    }

    @Test
    fun dstSafe() {
        // 29 March 2026: 02:00 -> 03:00 in Rome. A skipped 02:30 happens at the end of the gap.
        val spring = DayTimes(LocalDate.parse("2026-03-29"), ROME, LocalTime.of(2, 0), MyTimes())
        assertEquals(Instant.parse("2026-03-29T01:00:00Z"), spring.at(LocalTime.of(2, 30)))
        // 25 October 2026: 03:00 -> 02:00. A repeated 02:30 happens once, at its first (summer time) occurrence.
        val autumn = DayTimes(LocalDate.parse("2026-10-25"), ROME, LocalTime.of(2, 0), MyTimes())
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), autumn.at(LocalTime.of(2, 30)))
        // Wall-clock times keep their local time across the change.
        assertEquals(Instant.parse("2026-10-24T05:00:00Z"), day("2026-10-24").wake)
        assertEquals(Instant.parse("2026-10-25T06:00:00Z"), day("2026-10-25").wake)
    }

    @Test
    fun slotsForTriggers() {
        val mon = day("2026-10-19", timetable)
        assertEquals(listOf("Analysis ch. 4"), mon.slots(SlotType.STUDY).map { it.label })
        assertEquals(rome("2026-10-19T17:40"), mon.slots(SlotType.STUDY).single().end)
        val call = mon.slots(SlotType.CALL).single()
        assertEquals("Mom", call.label)
        assertEquals(rome("2026-10-19T16:00"), call.end)
        assertEquals(20, call.minutes)
        assertEquals(listOf("lunch", "dinner"), mon.slots(SlotType.MEAL).map { it.label })
        val tue = day("2026-10-20", timetable)
        assertEquals(rome("2026-10-20T12:45"), tue.slots(SlotType.COMMUTE).single().end)
        assertTrue(mon.slots(SlotType.JUMMAH).isEmpty())
        val fri = day("2026-10-23", timetable)
        assertEquals(rome("2026-10-23T14:40"), fri.slots(SlotType.JUMMAH).single().start)
        assertTrue(mon.slots(SlotType.GYM).isEmpty())
    }

    @Test
    fun classInProgressAndBlockEnd() {
        val tue = day("2026-10-20", timetable)
        assertNull(tue.classAt(rome("2026-10-20T12:59")))
        assertEquals("Analysis", tue.classAt(rome("2026-10-20T13:00"))!!.label)
        // Back-to-back classes: the block ends when the last one does.
        assertEquals(rome("2026-10-20T16:00"), tue.classBlockEnd(rome("2026-10-20T13:10")))
        assertEquals(rome("2026-10-20T16:00"), tue.classBlockEnd(rome("2026-10-20T15:00")))
        assertNull(tue.classBlockEnd(rome("2026-10-20T16:00")))
        val fri = day("2026-10-23", timetable)
        assertEquals(rome("2026-10-23T14:30"), fri.classBlockEnd(rome("2026-10-23T14:00")))
        assertFalse(fri.classAt(rome("2026-10-23T14:30")) != null)
    }

    @Test
    fun brokenRowsAreSkipped() {
        val t = MyTimes(meals = listOf(Slot(all, "nope", label = "dinner")), wake = mapOf("mon" to "x"))
        val mon = day("2026-10-19", t)
        assertNull(mon.meal("dinner"))
        assertEquals(rome("2026-10-19T07:00"), mon.wake)
        assertEquals(TimeExpr(TimeBase.Wake, 0), expr("wake"))
    }
}
