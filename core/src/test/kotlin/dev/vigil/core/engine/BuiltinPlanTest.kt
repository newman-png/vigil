package dev.vigil.core.engine

import dev.vigil.core.plan.ParseResult
import dev.vigil.core.plan.Plan
import dev.vigil.core.plan.PlanParser
import dev.vigil.core.plan.Prayer
import dev.vigil.core.plan.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime

class BuiltinPlanTest {
    private val plan = BuiltinPlan.plan

    @Test
    fun parsesWithoutErrorsOrWarnings() {
        when (val result = PlanParser.parse(BuiltinPlan.text)) {
            is ParseResult.Success -> assertEquals(emptyList<Any>(), result.warnings)
            is ParseResult.Failure -> fail("Built-in plan has errors: ${result.errors}")
        }
    }

    @Test
    fun badDayKeepIsValid() {
        val keep = plan.badDay.keep
        val ids = plan.directives.map { it.id }
        assertTrue("bad_day.keep must not be empty", keep.isNotEmpty())
        assertEquals("bad_day.keep has duplicates", keep.distinct(), keep)
        for (id in keep) assertTrue("bad_day.keep lists unknown id $id", id in ids)
    }

    @Test
    fun keepsEveryPrayerOnABadDay() {
        for (prayer in Prayer.entries) {
            val matching = plan.directives.filter { (it.trigger as? Trigger.Adhan)?.prayer == prayer }
            assertEquals("exactly one directive for $prayer", 1, matching.size)
            assertTrue("$prayer must be in bad_day.keep", matching.single().id in plan.badDay.keep)
        }
    }

    @Test
    fun coversWaterAndSleep() {
        val timed = plan.directives.filter { it.trigger is Trigger.Time }
        assertTrue(timed.any { it.title == "WATER" })
        assertTrue(timed.any { it.title == "SLEEP" })
    }

    @Test
    fun everyDirectiveCanFire() {
        val ids = plan.directives.map { it.id }
        assertEquals("ids must be unique", ids.distinct(), ids)
        for (d in plan.directives) {
            assertTrue("${d.id} has an unsupported trigger", d.trigger !is Trigger.Unsupported)
            assertTrue("${d.id} must not collide with the debug demo", d.id != Engine.DEMO_ID)
        }
    }

    @Test
    fun neverExpiresAndHasNoFallback() {
        assertTrue(plan.expires.isAfter(LocalDateTime.of(2090, 1, 1, 0, 0)))
        assertNull(plan.fallbackPlan)
        assertEquals(Plan.DEFAULT_DAY_BOUNDARY, plan.dayBoundary)
    }
}
