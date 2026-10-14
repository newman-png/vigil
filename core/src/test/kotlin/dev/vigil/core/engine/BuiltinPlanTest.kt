package dev.vigil.core.engine

import dev.vigil.core.plan.ParseResult
import dev.vigil.core.plan.Plan
import dev.vigil.core.plan.PlanParser
import dev.vigil.core.plan.Prayer
import dev.vigil.core.plan.RatingPolicy
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

    /** The prayers are never rated, water now and then, sleep every night. */
    @Test
    fun ratingsAreSetPerDirective() {
        assertEquals(Plan.SUPPORTED_SCHEMA_VERSION, plan.schemaVersion)
        for (d in plan.directives) {
            val expected = when {
                d.trigger is Trigger.Adhan -> RatingPolicy.NEVER
                d.title == "WATER" -> RatingPolicy.SAMPLE
                d.title == "SLEEP" -> RatingPolicy.ALWAYS
                else -> fail("${d.id} needs an expected rating here") as Nothing
            }
            assertEquals(d.id, expected, d.rating)
        }
        // Explicit in the file, so a change to the tier defaults cannot change the built-in plan.
        assertEquals(plan.directives.size, Regex("\"rating\":").findAll(BuiltinPlan.text).count())
    }

    /** It is the bare minimum already: nothing to step down to, no minimum day and no check-in. */
    @Test
    fun hasNoVariantsMinimumOrCheckIn() {
        for (d in plan.directives) {
            assertNull(d.id, d.easier)
            assertNull(d.id, d.stepDownAfterHard)
            assertTrue("${d.id} uses an id reserved for the app", !d.id.startsWith(PlanParser.RESERVED_ID_PREFIX))
        }
        assertNull(plan.minimum)
        assertNull(plan.afterBadDay)
    }

    @Test
    fun neverExpiresAndHasNoFallback() {
        assertTrue(plan.expires.isAfter(LocalDateTime.of(2090, 1, 1, 0, 0)))
        assertNull(plan.fallbackPlan)
        assertEquals(Plan.DEFAULT_DAY_BOUNDARY, plan.dayBoundary)
    }
}
