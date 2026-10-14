package dev.vigil.core.engine

import dev.vigil.core.log.EventType
import dev.vigil.core.plan.BadDay
import dev.vigil.core.plan.Directive
import dev.vigil.core.plan.Plan
import dev.vigil.core.plan.QuietPolicy
import dev.vigil.core.plan.Step
import dev.vigil.core.plan.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class SchedulingTest {
    private val zone = ZoneId.of("Europe/London")

    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun timeDirective(id: String, time: String, tier: Int = 2) = Directive(
        id, tier, id.uppercase(), Trigger.Time(LocalTime.parse(time), DayOfWeek.entries.toSet()),
        listOf(Step("Do it.", 0)), "Because.", QuietPolicy.QUEUE,
    )

    private val water = timeDirective("water", "10:30", tier = 1)
    private val sleep = timeDirective("sleep", "23:00", tier = 3)

    private fun plan(vararg directives: Directive, expires: String = "2026-10-26T04:00") = Plan(
        schemaVersion = 1,
        weekOf = LocalDate.parse("2026-10-19"),
        expires = LocalDateTime.parse(expires),
        generatedBy = null,
        context = null,
        dayBoundary = LocalTime.of(4, 0),
        directives = directives.toList(),
        badDay = BadDay.NONE,
        fallbackPlan = null,
    )

    private fun env(now: Instant, plan: Plan): Env {
        var n = 0
        val active = ActivePlanResolver.resolve(plan, BuiltinPlan.plan, LocalDateTime.ofInstant(now, zone))
        return Env(now, zone, active, allowQuietOverride = true) { "t${++n}" }
    }

    @Test
    fun dueFireShowsAndCursorAdvances() {
        val p = plan(water, sleep)
        val state = RuntimeState(scheduleCursorMs = at("2026-10-20T10:00").toEpochMilli())
        val now = at("2026-10-20T10:30:05")
        val (outcome, next) = Scheduling.onClock(state, env(now, p), catchUp = true)

        assertEquals("water", outcome.state.active?.directiveId)
        val fired = outcome.events.single { it.type == EventType.DIRECTIVE_FIRED }
        assertEquals("time", fired.trigger)
        assertEquals("2026-10-20T10:30:00+01:00", fired.scheduledFor)
        assertEquals(now.toEpochMilli(), outcome.state.scheduleCursorMs)
        // Water is now on screen, so the next wake is its hard maximum, before sleep at 23:00.
        assertEquals(now.plusSeconds(Engine.MAX_TAKEOVER_MINUTES * 60), next)
    }

    @Test
    fun longGapLogsMissedInsteadOfShowing() {
        val p = plan(water, sleep)
        val state = RuntimeState(scheduleCursorMs = at("2026-10-20T09:00").toEpochMilli())
        val (outcome, _) = Scheduling.onClock(state, env(at("2026-10-20T11:30"), p), catchUp = true)

        assertNull(outcome.state.active)
        val missed = outcome.events.single { it.type == EventType.MISSED }
        assertEquals("water", missed.directiveId)
        assertEquals("2026-10-20T10:30:00+01:00", missed.scheduledFor)
    }

    @Test
    fun withoutCatchUpNothingOldIsReplayed() {
        val p = plan(water, sleep)
        val state = RuntimeState(scheduleCursorMs = at("2026-10-20T09:00").toEpochMilli())
        val (outcome, next) = Scheduling.onClock(state, env(at("2026-10-20T10:40"), p), catchUp = false)

        assertTrue(outcome.events.none { it.type == EventType.MISSED || it.type == EventType.DIRECTIVE_FIRED })
        assertEquals(at("2026-10-20T23:00"), next)
    }

    @Test
    fun firstRunHasNoCursorAndReplaysNothing() {
        val (outcome, _) = Scheduling.onClock(RuntimeState(), env(at("2026-10-20T10:40"), plan(water)), catchUp = true)
        assertTrue(outcome.events.none { it.type == EventType.MISSED || it.type == EventType.DIRECTIVE_FIRED })
    }

    @Test
    fun weeksOffAreSummarisedAfterTheCap() {
        val p = plan(water, sleep, expires = "2027-01-01T04:00")
        val state = RuntimeState(scheduleCursorMs = at("2026-10-20T00:00").toEpochMilli())
        val (outcome, _) = Scheduling.onClock(state, env(at("2026-11-20T12:00"), p), catchUp = true)

        val missed = outcome.events.filter { it.type == EventType.MISSED }
        val perDirective = missed.filter { it.directiveId != null }
        val summary = missed.single { it.directiveId == null }
        assertEquals(Scheduling.MAX_MISSED_LOGGED, perDirective.size)
        assertTrue(summary.detail!!.contains("more missed"))
    }

    @Test
    fun afterExpiryTheFallbackOnlyCountsFromTheSwitch() {
        // The plan expires at 04:00; the built-in plan takes over. Its 23:00 sleep the evening before was not running
        // then, so it must not be logged as missed when the 04:00 wake-up catches up.
        val p = plan(water, expires = "2026-10-21T04:00")
        val state = RuntimeState(scheduleCursorMs = at("2026-10-20T22:00").toEpochMilli(), lastSource = PlanSource.PLAN.json)
        val (outcome, _) = Scheduling.onClock(state, env(at("2026-10-21T04:00:05"), p), catchUp = true)
        assertTrue(outcome.events.none { it.type == EventType.MISSED })
        assertTrue(outcome.events.any { it.type == EventType.PLAN_EXPIRED })
    }

    @Test
    fun nextWakeIsTheEarlierOfFireAndExpiry() {
        val p = plan(sleep, expires = "2026-10-20T22:00")
        val now = at("2026-10-20T21:00")
        assertEquals(at("2026-10-20T22:00"), Scheduling.nextWakeFor(RuntimeState(), env(now, p)))
    }

    @Test
    fun nextWakeIncludesTheActiveTakeoversHardMaximum() {
        val p = plan(sleep)
        val now = at("2026-10-20T21:00")
        val ticket = Ticket("t1", "sleep", "debug", silent = false, firedAtMs = now.toEpochMilli(), activeSinceMs = now.toEpochMilli())
        val wake = Scheduling.nextWakeFor(RuntimeState(active = ticket), env(now, p))
        assertEquals(now.plusSeconds(Engine.MAX_TAKEOVER_MINUTES * 60), wake)
    }

    @Test
    fun clearAllDropsShowingAndWaitingTakeovers() {
        val p = plan(water, sleep)
        val now = at("2026-10-20T21:00")
        val state = RuntimeState(
            quiet = true,
            active = Ticket("t1", "water", "time", silent = false, firedAtMs = now.toEpochMilli()),
            pending = listOf(Ticket("t2", "sleep", "time", silent = false, firedAtMs = now.toEpochMilli())),
        )
        val outcome = Scheduling.clearAll(state, env(now, p), "developer kill switch")
        assertNull(outcome.state.active)
        assertTrue(outcome.state.pending.isEmpty())
        assertTrue(outcome.state.quiet)
        assertEquals(listOf("water", "sleep"), outcome.events.map { it.directiveId })
        assertTrue(outcome.events.all { it.type == EventType.DROPPED && it.detail == "developer kill switch" })
    }

    @Test
    fun clearAllDropsACheckInAndLeavesItFired() {
        val p = plan(water, sleep)
        val now = at("2026-10-20T08:00")
        val checkIn = CheckInState("2026-10-20", "bad_day", fired = true, takeoverId = "t1")
        val state = RuntimeState(
            lastSource = PlanSource.PLAN.json,
            active = Ticket("t1", Engine.CHECK_IN_ID, Engine.TRIGGER_CHECK_IN, silent = false, firedAtMs = now.toEpochMilli()),
            pending = listOf(Ticket("t2", "sleep", "time", silent = false, firedAtMs = now.toEpochMilli(), variant = "easier")),
            checkIn = checkIn,
            dayMode = DayModeState("2026-10-20", "bad_day", DayModeState.SOURCE_CHECK_IN_PENDING),
        )
        val outcome = Scheduling.clearAll(state, env(now, p), "developer kill switch")
        assertNull(outcome.state.active)
        assertTrue(outcome.state.pending.isEmpty())
        assertEquals(checkIn, outcome.state.checkIn)
        assertEquals(state.dayMode, outcome.state.dayMode)
        assertEquals(listOf(Engine.CHECK_IN_ID, "sleep"), outcome.events.map { it.directiveId })
        assertEquals(listOf(null, "easier"), outcome.events.map { it.variant })
        // A dropped check-in is not shown again.
        assertTrue(Engine.onUserPresent(outcome.state, env(now, p)).events.isEmpty())
    }

    @Test
    fun theClockEndsABadDayAtTheBoundary() {
        val p = plan(water, sleep)
        val state = RuntimeState(
            lastSource = PlanSource.PLAN.json,
            scheduleCursorMs = at("2026-10-21T03:00").toEpochMilli(),
            dayMode = DayModeState("2026-10-20", "bad_day", DayModeState.SOURCE_USER),
        )
        val (outcome, _) = Scheduling.onClock(state, env(at("2026-10-21T04:00:05"), p), catchUp = true)
        assertEquals(listOf(EventType.DAY_MODE), outcome.events.map { it.type })
        assertEquals("day ended", outcome.events.single().detail)
        assertNull(outcome.state.dayMode)
    }
}
