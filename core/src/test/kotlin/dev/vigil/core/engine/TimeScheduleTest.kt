package dev.vigil.core.engine

import dev.vigil.core.plan.Directive
import dev.vigil.core.plan.Prayer
import dev.vigil.core.plan.QuietPolicy
import dev.vigil.core.plan.Step
import dev.vigil.core.plan.Trigger
import dev.vigil.core.plan.UsageLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class TimeScheduleTest {
    private val london = ZoneId.of("Europe/London")

    /** A local wall-clock time in London (2026-10-12 is a Monday, 2026-10-13 a Tuesday). */
    private fun local(s: String): Instant = LocalDateTime.parse(s).atZone(london).toInstant()

    private fun utc(s: String): Instant = Instant.parse(s)

    private fun timed(id: String, at: String, vararg days: DayOfWeek) = directive(
        id,
        Trigger.Time(LocalTime.parse(at), if (days.isEmpty()) DayOfWeek.entries.toSet() else days.toSet()),
    )

    private fun directive(id: String, trigger: Trigger) = Directive(
        id = id,
        tier = 1,
        title = id,
        trigger = trigger,
        steps = listOf(Step("Do it.", 0)),
        why = "",
        quiet = QuietPolicy.QUEUE,
    )

    // ------------------------------------------------------------------ next()

    @Test
    fun nextIsLaterToday() {
        val fire = TimeSchedule.next(listOf(timed("water", "10:00")), local("2026-10-13T08:00"), london)
        assertEquals(TimeFire(local("2026-10-13T10:00"), listOf("water")), fire)
    }

    @Test
    fun nextIsTomorrowWhenTodaysTimeHasPassed() {
        val fire = TimeSchedule.next(listOf(timed("water", "10:00")), local("2026-10-13T10:01"), london)
        assertEquals(local("2026-10-14T10:00"), fire!!.at)
    }

    @Test
    fun nextIsStrictlyAfter() {
        val fire = TimeSchedule.next(listOf(timed("water", "10:00")), local("2026-10-13T10:00"), london)
        assertEquals(local("2026-10-14T10:00"), fire!!.at)
        val justBefore = local("2026-10-13T10:00").minusMillis(1)
        assertEquals(local("2026-10-13T10:00"), TimeSchedule.next(listOf(timed("water", "10:00")), justBefore, london)!!.at)
    }

    @Test
    fun daysFilterSkipsToTheNextListedDay() {
        val d = timed("gym", "18:00", MONDAY, WEDNESDAY)
        // Tuesday morning: Wednesday is next.
        assertEquals(local("2026-10-14T18:00"), TimeSchedule.next(listOf(d), local("2026-10-13T08:00"), london)!!.at)
        // Wednesday after the time: next Monday.
        assertEquals(local("2026-10-19T18:00"), TimeSchedule.next(listOf(d), local("2026-10-14T18:30"), london)!!.at)
        // Monday before the time: today.
        assertEquals(local("2026-10-12T18:00"), TimeSchedule.next(listOf(d), local("2026-10-12T17:59"), london)!!.at)
    }

    @Test
    fun singleDayAtExactlyItsTimeIsNextWeek() {
        val d = timed("review", "20:00", MONDAY)
        assertEquals(local("2026-10-19T20:00"), TimeSchedule.next(listOf(d), local("2026-10-12T20:00"), london)!!.at)
    }

    @Test
    fun directivesAtTheSameMinuteAreGroupedInPlanOrder() {
        val directives = listOf(
            timed("late", "08:00"),
            timed("a", "07:00"),
            timed("b", "07:00"),
            timed("other-day", "07:00", WEDNESDAY),
        )
        val fire = TimeSchedule.next(directives, local("2026-10-13T06:00"), london)
        assertEquals(TimeFire(local("2026-10-13T07:00"), listOf("a", "b")), fire)
    }

    @Test
    fun earliestWinsWhateverTheListOrder() {
        val a = timed("a", "07:00")
        val b = timed("b", "06:00")
        val after = local("2026-10-13T05:00")
        assertEquals(listOf("b"), TimeSchedule.next(listOf(a, b), after, london)!!.directiveIds)
        assertEquals(listOf("b"), TimeSchedule.next(listOf(b, a), after, london)!!.directiveIds)
    }

    @Test
    fun noTimeTriggersMeansNoFire() {
        val now = local("2026-10-13T08:00")
        assertNull(TimeSchedule.next(emptyList(), now, london))
        val others = listOf(
            directive("isha", Trigger.Adhan(Prayer.ISHA)),
            directive("tiktok", Trigger.AppUsage(listOf("com.zhiliaoapp.musically"), UsageLimit.Opens(3, 10), 10)),
            directive("geo", Trigger.Unsupported("geofence")),
        )
        assertNull(TimeSchedule.next(others, now, london))
        assertEquals(SchedulePass(emptyList(), emptyList(), null), TimeSchedule.pass(others, now.minusSeconds(86_400), now, london))
    }

    @Test
    fun nonTimeDirectivesAreIgnoredAmongTimeOnes() {
        val directives = listOf(directive("isha", Trigger.Adhan(Prayer.ISHA)), timed("water", "10:00"))
        assertEquals(listOf("water"), TimeSchedule.next(directives, local("2026-10-13T08:00"), london)!!.directiveIds)
    }

    @Test
    fun wallClockTimeIsKeptAcrossDaylightSaving() {
        val d = timed("fajr-alarm", "05:30")
        // BST (UTC+1) on Saturday, GMT on Monday after the clocks went back.
        assertEquals(utc("2026-10-24T04:30:00Z"), TimeSchedule.next(listOf(d), utc("2026-10-24T00:00:00Z"), london)!!.at)
        assertEquals(utc("2026-10-26T05:30:00Z"), TimeSchedule.next(listOf(d), utc("2026-10-26T00:00:00Z"), london)!!.at)
    }

    // ------------------------------------------------------------------ daylight saving

    @Test
    fun springForwardGapFiresRightAfterTheGapOnce() {
        // 2026-03-29: 01:00 GMT jumps to 02:00 BST, so 01:30 does not exist. The gap ends at 01:00Z.
        val d = timed("night", "01:30")
        val first = TimeSchedule.next(listOf(d), utc("2026-03-29T00:00:00Z"), london)
        assertEquals(TimeFire(utc("2026-03-29T01:00:00Z"), listOf("night")), first)
        assertEquals(utc("2026-03-30T00:30:00Z"), TimeSchedule.next(listOf(d), first!!.at, london)!!.at)

        val pass = TimeSchedule.pass(listOf(d), utc("2026-03-28T23:00:00Z"), utc("2026-03-29T03:00:00Z"), london)
        assertEquals(listOf(TimeFire(utc("2026-03-29T01:00:00Z"), listOf("night"))), pass.missed + pass.due)
        assertEquals(utc("2026-03-30T00:30:00Z"), pass.next!!.at)
    }

    @Test
    fun springForwardKeepsTheOrderOfTheNight() {
        // A time skipped by the gap must not end up after a time that comes later on the clock.
        val directives = listOf(timed("late", "02:15"), timed("skipped", "01:30"))
        val pass = TimeSchedule.pass(directives, utc("2026-03-28T23:00:00Z"), utc("2026-03-29T03:00:00Z"), london)
        assertEquals(
            listOf(
                TimeFire(utc("2026-03-29T01:00:00Z"), listOf("skipped")),
                TimeFire(utc("2026-03-29T01:15:00Z"), listOf("late")),
            ),
            pass.missed + pass.due,
        )
    }

    @Test
    fun fallBackOverlapFiresOnceAtTheFirstOccurrence() {
        // 2026-10-25: 02:00 BST goes back to 01:00 GMT, so 01:30 happens at 00:30Z and again at 01:30Z.
        val d = timed("night", "01:30")
        val first = TimeSchedule.next(listOf(d), utc("2026-10-24T23:00:00Z"), london)
        assertEquals(utc("2026-10-25T00:30:00Z"), first!!.at)
        assertEquals(utc("2026-10-26T01:30:00Z"), TimeSchedule.next(listOf(d), first.at, london)!!.at)
        // From inside the repeated hour the second 01:30 is skipped too.
        assertEquals(utc("2026-10-26T01:30:00Z"), TimeSchedule.next(listOf(d), utc("2026-10-25T01:10:00Z"), london)!!.at)

        val pass = TimeSchedule.pass(listOf(d), utc("2026-10-24T23:00:00Z"), utc("2026-10-25T03:00:00Z"), london)
        assertEquals(listOf(TimeFire(utc("2026-10-25T00:30:00Z"), listOf("night"))), pass.missed + pass.due)
    }

    @Test
    fun gapAndOverlapAtMidnight() {
        // São Paulo 2018-11-04 (a Sunday): 00:00 -03 jumped to 01:00 -02, so that day had no midnight.
        val saoPaulo = ZoneId.of("America/Sao_Paulo")
        val d = timed("midnight", "00:00", DayOfWeek.SUNDAY)
        val gapDay = TimeSchedule.next(listOf(d), utc("2018-11-03T12:00:00Z"), saoPaulo)
        assertEquals(utc("2018-11-04T03:00:00Z"), gapDay!!.at)
        assertEquals(utc("2018-11-11T02:00:00Z"), TimeSchedule.next(listOf(d), gapDay.at, saoPaulo)!!.at)

        // 2019-02-17 00:00 -02 went back to 2019-02-16 23:00 -03, so 23:30 on the 16th happened twice.
        val late = timed("late", "23:30")
        val first = TimeSchedule.next(listOf(late), utc("2019-02-16T12:00:00Z"), saoPaulo)
        assertEquals(utc("2019-02-17T01:30:00Z"), first!!.at)
        assertEquals(utc("2019-02-18T02:30:00Z"), TimeSchedule.next(listOf(late), first.at, saoPaulo)!!.at)
        assertEquals(utc("2019-02-18T02:30:00Z"), TimeSchedule.next(listOf(late), utc("2019-02-17T02:10:00Z"), saoPaulo)!!.at)
    }

    // ------------------------------------------------------------------ pass()

    @Test
    fun passSplitsMissedAndDueByTheGracePeriod() {
        val directives = listOf(timed("old", "07:00"), timed("edge", "07:30"), timed("recent", "07:50"), timed("later", "09:00"))
        val now = local("2026-10-13T08:00")
        val pass = TimeSchedule.pass(directives, local("2026-10-13T06:00"), now, london, graceMinutes = 30)
        assertEquals(listOf(TimeFire(local("2026-10-13T07:00"), listOf("old"))), pass.missed)
        // Exactly at the start of the grace period still counts as due.
        assertEquals(
            listOf(TimeFire(local("2026-10-13T07:30"), listOf("edge")), TimeFire(local("2026-10-13T07:50"), listOf("recent"))),
            pass.due,
        )
        assertEquals(TimeFire(local("2026-10-13T09:00"), listOf("later")), pass.next)
    }

    @Test
    fun passIncludesNowAndExcludesTheCursor() {
        val d = timed("water", "10:00")
        val ten = local("2026-10-13T10:00")
        // An alarm that goes off exactly on time: due once, and the next fire is tomorrow.
        val onTime = TimeSchedule.pass(listOf(d), local("2026-10-13T09:00"), ten, london)
        assertEquals(listOf(TimeFire(ten, listOf("water"))), onTime.due)
        assertEquals(local("2026-10-14T10:00"), onTime.next!!.at)
        // Waking again with the cursor at 10:00 must not repeat it.
        val again = TimeSchedule.pass(listOf(d), ten, ten.plusSeconds(5), london)
        assertTrue(again.missed.isEmpty() && again.due.isEmpty())
    }

    @Test
    fun passGroupsDirectivesAtTheSameMoment() {
        val directives = listOf(timed("a", "10:00"), timed("b", "10:00"))
        val pass = TimeSchedule.pass(directives, local("2026-10-13T09:00"), local("2026-10-13T10:05"), london)
        assertEquals(listOf(TimeFire(local("2026-10-13T10:00"), listOf("a", "b"))), pass.due)
    }

    @Test
    fun catchUpAfterALongGapListsEveryMissedFireInOrder() {
        val directives = listOf(timed("sleep", "22:00"), timed("water", "10:00"), timed("gym", "18:00", WEDNESDAY))
        // Phone off from Monday 09:00 until Thursday 10:10.
        val pass = TimeSchedule.pass(directives, local("2026-10-12T09:00"), local("2026-10-15T10:10"), london)
        val expectedMissed = listOf(
            "2026-10-12T10:00" to "water", "2026-10-12T22:00" to "sleep",
            "2026-10-13T10:00" to "water", "2026-10-13T22:00" to "sleep",
            "2026-10-14T10:00" to "water", "2026-10-14T18:00" to "gym", "2026-10-14T22:00" to "sleep",
        ).map { (at, id) -> TimeFire(local(at), listOf(id)) }
        assertEquals(expectedMissed, pass.missed)
        assertEquals(listOf(TimeFire(local("2026-10-15T10:00"), listOf("water"))), pass.due)
        assertEquals(TimeFire(local("2026-10-15T22:00"), listOf("sleep")), pass.next)
    }

    @Test
    fun catchUpOverMonthsIsComplete() {
        val directives = listOf(timed("a", "06:00"), timed("b", "12:00"), timed("c", "18:00"))
        val pass = TimeSchedule.pass(directives, local("2026-01-01T00:00"), local("2026-10-13T00:00"), london)
        // 285 days, three fires a day, none due.
        assertEquals(285 * 3, pass.missed.size)
        assertTrue(pass.due.isEmpty())
        assertEquals(pass.missed.sortedBy { it.at }, pass.missed)
        assertEquals(pass.missed.size, pass.missed.map { it.at }.toSet().size)
    }

    @Test
    fun passWithCursorAtNowFiresNothing() {
        val now = local("2026-10-13T10:00")
        val pass = TimeSchedule.pass(listOf(timed("water", "10:00")), now, now, london)
        assertEquals(SchedulePass(emptyList(), emptyList(), TimeFire(local("2026-10-14T10:00"), listOf("water"))), pass)
    }

    @Test
    fun passWithCursorInTheFutureFiresNothing() {
        // The clock was moved back: nothing is replayed, and the next fire is computed from now.
        val now = local("2026-10-13T08:00")
        val pass = TimeSchedule.pass(listOf(timed("water", "10:00")), local("2026-10-13T12:00"), now, london)
        assertEquals(SchedulePass(emptyList(), emptyList(), TimeFire(local("2026-10-13T10:00"), listOf("water"))), pass)
    }
}
