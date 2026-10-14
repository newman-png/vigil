package dev.vigil.core.engine

import dev.vigil.core.plan.AfterBadDay
import dev.vigil.core.plan.BadDay
import dev.vigil.core.plan.DayMode
import dev.vigil.core.plan.Directive
import dev.vigil.core.plan.FallbackPlan
import dev.vigil.core.plan.Minimum
import dev.vigil.core.plan.ParseResult
import dev.vigil.core.plan.Plan
import dev.vigil.core.plan.PlanParser
import dev.vigil.core.plan.QuietPolicy
import dev.vigil.core.plan.Step
import dev.vigil.core.plan.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ActivePlanResolverTest {
    private fun directive(id: String) = Directive(
        id, 1, id, Trigger.Time(LocalTime.of(9, 0), DayOfWeek.entries.toSet()), listOf(Step("Do it.", 5)), "Because.", QuietPolicy.SILENT,
    )

    private val expires = LocalDateTime.of(2026, 10, 19, 4, 0)

    private fun plan(fallback: List<Directive>? = null, boundary: LocalTime = LocalTime.of(5, 0)) = Plan(
        schemaVersion = 1,
        weekOf = LocalDate.of(2026, 10, 12),
        expires = expires,
        generatedBy = "test",
        context = null,
        dayBoundary = boundary,
        directives = listOf(directive("a"), directive("b")),
        badDay = BadDay(listOf("a")),
        fallbackPlan = fallback?.let { FallbackPlan(it) },
    )

    private val builtin = BuiltinPlan.plan

    @Test
    fun noPlanRunsBuiltinDefault() {
        val set = ActivePlanResolver.resolve(null, builtin, expires)
        assertEquals(PlanSource.DEFAULT, set.source)
        assertEquals(builtin.directives, set.directives)
        assertEquals(builtin.badDay.keep.toSet(), set.badDayKeep)
        assertEquals(builtin.dayBoundary, set.dayBoundary)
        assertNull(set.plan)
    }

    @Test
    fun builtinDefaultHonoursItsOwnBadDayList() {
        val set = ActivePlanResolver.resolve(null, builtin, expires)
        assertTrue("default-isha" in set.badDayKeep)
        assertTrue("default-water-am" !in set.badDayKeep)
    }

    @Test
    fun planRunsUntilItExpires() {
        val p = plan(fallback = listOf(directive("fb")))
        val set = ActivePlanResolver.resolve(p, builtin, expires.minusSeconds(1))
        assertEquals(PlanSource.PLAN, set.source)
        assertEquals(listOf("a", "b"), set.directives.map { it.id })
        assertEquals(setOf("a"), set.badDayKeep)
        assertEquals(LocalTime.of(5, 0), set.dayBoundary)
        assertSame(p, set.plan)
        assertEquals("a", set.find("a")?.id)
        assertNull(set.find("fb"))
    }

    @Test
    fun expiredPlanRunsFallbackAndKeepsAllOfIt() {
        val p = plan(fallback = listOf(directive("fb1"), directive("fb2")))
        val set = ActivePlanResolver.resolve(p, builtin, expires)
        assertEquals(PlanSource.FALLBACK, set.source)
        assertEquals(listOf("fb1", "fb2"), set.directives.map { it.id })
        assertEquals(setOf("fb1", "fb2"), set.badDayKeep)
        assertEquals(LocalTime.of(5, 0), set.dayBoundary)
        assertSame(p, set.plan)
        assertNull(set.find("a"))
    }

    @Test
    fun expiredPlanWithoutFallbackRunsBuiltinWithPlanBoundary() {
        val p = plan()
        val set = ActivePlanResolver.resolve(p, builtin, expires.plusDays(30))
        assertEquals(PlanSource.DEFAULT, set.source)
        assertEquals(builtin.directives, set.directives)
        assertEquals(builtin.badDay.keep.toSet(), set.badDayKeep)
        assertEquals(LocalTime.of(5, 0), set.dayBoundary)
        assertSame(p, set.plan)
    }

    private val after = AfterBadDay(better = DayMode.NORMAL, same = DayMode.BAD_DAY, worse = DayMode.MINIMUM)

    @Test
    fun runningPlanCarriesMinimumAndAfterBadDay() {
        val p = plan(fallback = listOf(directive("fb"))).copy(minimum = Minimum(listOf("a")), afterBadDay = after)
        val set = ActivePlanResolver.resolve(p, builtin, expires.minusSeconds(1))
        assertEquals(setOf("a"), set.minimumKeep)
        assertEquals(after, set.afterBadDay)
    }

    @Test
    fun fallbackKeepsAllOfItOnAMinimumDayAndKeepsThePlansCheckIn() {
        val p = plan(fallback = listOf(directive("fb1"), directive("fb2"))).copy(minimum = Minimum(listOf("a")), afterBadDay = after)
        val set = ActivePlanResolver.resolve(p, builtin, expires)
        assertEquals(PlanSource.FALLBACK, set.source)
        assertEquals(setOf("fb1", "fb2"), set.minimumKeep)
        assertEquals(after, set.afterBadDay)
    }

    @Test
    fun withoutMinimumOrAfterBadDayThereIsNoCheckInAndAMinimumDayKeepsTheBadDayList() {
        // No check-in can choose a minimum day here, but one chosen before a new import or the switch to the built-in
        // plan can still be running: then the essentials (bad_day.keep) fire, not nothing.
        val running = ActivePlanResolver.resolve(plan(), builtin, expires.minusSeconds(1))
        assertEquals(setOf("a"), running.minimumKeep)
        assertNull(running.afterBadDay)
        // The built-in plan has neither, also when an expired plan without a fallback had them.
        val none = ActivePlanResolver.resolve(null, builtin, expires)
        assertNull(builtin.minimum)
        assertEquals(builtin.badDay.keep.toSet(), none.minimumKeep)
        assertTrue("default-fajr" in none.minimumKeep)
        assertNull(none.afterBadDay)
        val expired = ActivePlanResolver.resolve(plan().copy(minimum = Minimum(listOf("a")), afterBadDay = after), builtin, expires)
        assertEquals(PlanSource.DEFAULT, expired.source)
        assertEquals(builtin.badDay.keep.toSet(), expired.minimumKeep)
        assertNull(expired.afterBadDay)
        // An explicit empty minimum.keep stays empty (the parser warns that nothing fires on a minimum day).
        val empty = ActivePlanResolver.resolve(plan().copy(minimum = Minimum(emptyList())), builtin, expires.minusSeconds(1))
        assertTrue(empty.minimumKeep.isEmpty())
    }

    @Test
    fun parsedPlanResolves() {
        val json = """
            {
              "schema_version": 1,
              "week_of": "2026-10-12",
              "expires": "2026-10-19",
              "directives": [
                {"id": "isha", "tier": 2, "trigger": {"type": "adhan", "prayer": "isha"},
                 "steps": [{"text": "Pray Isha.", "min_seconds": 60}], "why": "Close the day.", "quiet": "queue"}
              ],
              "bad_day": {"keep": ["isha"]},
              "fallback_plan": {"directives": [
                {"id": "fb-isha", "tier": 1, "trigger": {"type": "adhan", "prayer": "isha"},
                 "steps": [{"text": "Pray Isha.", "min_seconds": 60}], "why": "Still.", "quiet": "silent"}
              ]}
            }
        """.trimIndent()
        val p = (PlanParser.parse(json) as ParseResult.Success).plan
        // A date-only expires means the day boundary on that date.
        assertEquals(PlanSource.PLAN, ActivePlanResolver.resolve(p, builtin, LocalDateTime.of(2026, 10, 19, 3, 59)).source)
        val after = ActivePlanResolver.resolve(p, builtin, LocalDateTime.of(2026, 10, 19, 4, 0))
        assertEquals(PlanSource.FALLBACK, after.source)
        assertEquals(setOf("fb-isha"), after.badDayKeep)
    }

    @Test
    fun dayKeyWithFourAmBoundary() {
        val four = LocalTime.of(4, 0)
        val wed = LocalDate.of(2026, 10, 14)
        assertEquals(wed.minusDays(1), ActivePlanResolver.dayKey(wed.atTime(1, 30), four))
        assertEquals(wed.minusDays(1), ActivePlanResolver.dayKey(wed.atTime(0, 0), four))
        assertEquals(wed.minusDays(1), ActivePlanResolver.dayKey(wed.atTime(3, 59, 59, 999_999_999), four))
        assertEquals(wed, ActivePlanResolver.dayKey(wed.atTime(4, 0), four))
        assertEquals(wed, ActivePlanResolver.dayKey(wed.atTime(23, 59, 59), four))
        // Across a month and year boundary.
        assertEquals(LocalDate.of(2026, 12, 31), ActivePlanResolver.dayKey(LocalDateTime.of(2027, 1, 1, 2, 0), four))
    }

    @Test
    fun dayKeyWithMidnightBoundary() {
        val wed = LocalDate.of(2026, 10, 14)
        assertEquals(wed, ActivePlanResolver.dayKey(wed.atTime(0, 0), LocalTime.MIDNIGHT))
        assertEquals(wed, ActivePlanResolver.dayKey(wed.atTime(23, 59), LocalTime.MIDNIGHT))
    }
}
