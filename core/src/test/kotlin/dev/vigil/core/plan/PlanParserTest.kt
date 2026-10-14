package dev.vigil.core.plan

import dev.vigil.core.engine.BuiltinPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random

/**
 * Tries to break [PlanParser]. Most tests take the valid fixture parser/full-plan.json, change one thing,
 * and check that the parser reports exactly that thing, at the right JSON path, in words a person can act on.
 */
class PlanParserTest {

    // ---------------------------------------------------------------- valid plans

    @Test
    fun fullPlanLoadsWithEveryFieldAndNoWarnings() {
        val result = PlanParser.parse(FULL)
        val plan = result.plan()
        assertEquals(emptyList<Issue>(), result.warnings)

        assertEquals(2, plan.schemaVersion)
        assertEquals(LocalDate.of(2026, 10, 19), plan.weekOf)
        assertEquals(LocalTime.of(3, 30), plan.dayBoundary)
        // A date-only expires means that date at the plan's day boundary.
        assertEquals(LocalDateTime.of(2026, 10, 26, 3, 30), plan.expires)
        assertEquals("test planner", plan.generatedBy)
        assertEquals("Exam week. Sleep is the priority.", plan.context)

        assertEquals(listOf("fajr", "isha", "water", "sleep", "tiktok-opens", "scroll_long"), plan.directives.map { it.id })

        val fajr = plan.directives[FAJR]
        assertEquals(3, fajr.tier)
        assertEquals("FAJR", fajr.title)
        assertEquals(Trigger.Adhan(Prayer.FAJR), fajr.trigger)
        assertEquals(listOf(Step("Sit up. Feet on the floor.", 5), Step("Make wudu.", 60), Step("Pray Fajr.", 180)), fajr.steps)
        assertEquals("The day starts with this.", fajr.why)
        assertEquals(QuietPolicy.OVERRIDE, fajr.quiet)

        val isha = plan.directives[ISHA]
        assertEquals("isha", isha.title) // no title in the plan: falls back to the id
        assertEquals(Trigger.Adhan(Prayer.ISHA), isha.trigger)
        assertEquals(0, isha.steps[0].minSeconds)
        assertEquals(QuietPolicy.QUEUE, isha.quiet)

        val water = plan.directives[WATER]
        assertEquals(Trigger.Time(LocalTime.of(11, 0), setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)), water.trigger)
        assertEquals(QuietPolicy.SILENT, water.quiet)

        val sleep = plan.directives[SLEEP]
        assertEquals(Trigger.Time(LocalTime.of(23, 0), DayOfWeek.entries.toSet()), sleep.trigger)
        assertEquals(PlanParser.MAX_STEP_SECONDS, sleep.steps[1].minSeconds)

        val opens = plan.directives[OPENS].trigger as Trigger.AppUsage
        assertEquals(listOf("com.zhiliaoapp.musically", "com.instagram.android"), opens.apps)
        assertEquals(UsageLimit.Opens(opens = 5, windowMin = 30), opens.limit)
        assertEquals("cooldown defaults to window_min", 30, opens.cooldownMin)
        assertEquals(QuietPolicy.IGNORE, plan.directives[OPENS].quiet)

        val scroll = plan.directives[SCROLL].trigger as Trigger.AppUsage
        assertEquals(UsageLimit.Continuous(20), scroll.limit)
        assertEquals("explicit cooldown 0 is kept", 0, scroll.cooldownMin)

        assertEquals(listOf("fajr", "isha", "sleep"), plan.badDay.keep)

        val fallback = plan.fallbackPlan!!.directives
        assertEquals(listOf("fallback-fajr", "fallback-sleep"), fallback.map { it.id })
        assertEquals(Trigger.Time(LocalTime.of(23, 30), DayOfWeek.entries.toSet()), fallback[1].trigger)
        assertEquals(fallback[0], plan.directive("fallback-fajr"))
        assertEquals(fajr, plan.directive("fajr"))
        assertNull(plan.directive("nope"))

        // Version 2 fields. A rating left out is "sample" for tier 1 and "always" for tiers 2 and 3.
        assertEquals(RatingPolicy.ALWAYS, fajr.rating)
        assertEquals(RatingPolicy.ALWAYS, isha.rating)
        assertEquals(RatingPolicy.SAMPLE, water.rating)
        assertEquals(RatingPolicy.ALWAYS, sleep.rating)
        assertEquals(RatingPolicy.NEVER, plan.directives[OPENS].rating)
        assertEquals(RatingPolicy.SAMPLE, plan.directives[SCROLL].rating)
        assertEquals(RatingPolicy.SAMPLE, fallback[0].rating)
        assertEquals(RatingPolicy.SAMPLE, fallback[1].rating)

        val sleepEasier = Variant("PHONE AWAY", listOf(Step("Put the phone on charge, away from the bed.", 10)), "Just the phone tonight.")
        assertEquals(sleepEasier, sleep.easier)
        assertEquals(3, sleep.stepDownAfterHard)
        assertEquals(Variant(null, listOf(Step("Look away from the screen.", 5)), null), plan.directives[SCROLL].easier)
        assertEquals(1, plan.directives[SCROLL].stepDownAfterHard)
        assertEquals(Variant(null, listOf(Step("Pray Fajr.", 180)), null), fallback[0].easier)
        assertEquals(2, fallback[0].stepDownAfterHard)
        for (d in listOf(fajr, isha, water, plan.directives[OPENS], fallback[1])) {
            assertNull(d.id, d.easier)
            assertNull(d.id, d.stepDownAfterHard)
        }

        assertEquals(Minimum(listOf("fajr", "sleep")), plan.minimum)
        assertEquals(AfterBadDay(DayMode.NORMAL, DayMode.BAD_DAY, DayMode.MINIMUM), plan.afterBadDay)
    }

    @Test
    fun builtinDefaultPlanLoadsWithoutWarnings() {
        val result = PlanParser.parse(BuiltinPlan.text)
        result.plan()
        assertEquals(emptyList<Issue>(), result.warnings)
    }

    @Test
    fun optionalFieldsCanBeLeftOut() {
        val plan = parse(
            "\$schema" to Remove,
            "_readme" to Remove,
            "generated_by" to Remove,
            "context" to Remove,
            "day_boundary" to Remove,
            "fallback_plan" to Remove,
            "directives[$FAJR].title" to Remove,
            "directives[$SLEEP].rating" to Remove,
            "directives[$SLEEP].variants" to Remove,
            "directives[$SLEEP].adapt" to Remove,
            "minimum" to Remove,
            "after_bad_day" to Remove,
        ).plan()
        assertNull(plan.generatedBy)
        assertNull(plan.context)
        assertNull(plan.fallbackPlan)
        assertEquals(Plan.DEFAULT_DAY_BOUNDARY, plan.dayBoundary)
        assertEquals("date-only expires uses the default boundary", LocalDateTime.of(2026, 10, 26, 4, 0), plan.expires)
        assertEquals("fajr", plan.directives[FAJR].title)
        assertEquals(RatingPolicy.ALWAYS, plan.directives[SLEEP].rating)
        assertNull(plan.directives[SLEEP].easier)
        assertNull(plan.directives[SLEEP].stepDownAfterHard)
        assertNull(plan.minimum)
        assertNull(plan.afterBadDay)
    }

    @Test
    fun optionalFieldsWrittenAsNullCountAsLeftOut() {
        val result = parse(
            "generated_by" to null,
            "context" to null,
            "day_boundary" to null,
            "fallback_plan" to null,
            "directives[$FAJR].title" to null,
            "directives[$WATER].trigger.days" to null,
            "directives[$OPENS].trigger.cooldown_min" to null,
            "directives[$OPENS].trigger.continuous_min" to null,
            "directives[$OPENS].rating" to null,
            "directives[$SLEEP].variants.easier.title" to null,
            "directives[$SLEEP].variants.easier.why" to null,
            "directives[$SCROLL].variants" to null,
            "directives[$SCROLL].adapt" to null,
            "minimum" to null,
            "after_bad_day" to null,
        )
        val plan = result.plan()
        assertEquals(emptyList<Issue>(), result.warnings)
        assertEquals(RatingPolicy.ALWAYS, plan.directives[OPENS].rating)
        assertEquals(Variant(null, plan.directives[SLEEP].easier!!.steps, null), plan.directives[SLEEP].easier)
        assertNull(plan.directives[SCROLL].easier)
        assertNull(plan.directives[SCROLL].stepDownAfterHard)
        assertNull(plan.minimum)
        assertNull(plan.afterBadDay)
        assertEquals(Plan.DEFAULT_DAY_BOUNDARY, plan.dayBoundary)
        assertNull(plan.fallbackPlan)
        assertEquals("fajr", plan.directives[FAJR].title)
        assertEquals(DayOfWeek.entries.toSet(), (plan.directives[WATER].trigger as Trigger.Time).days)
        val opens = plan.directives[OPENS].trigger as Trigger.AppUsage
        assertEquals(UsageLimit.Opens(5, 30), opens.limit)
        assertEquals(30, opens.cooldownMin)
    }

    @Test
    fun expiresWithTimeKeepsThatTime() {
        assertEquals(LocalDateTime.of(2026, 10, 26, 6, 15), parse("expires" to "2026-10-26T06:15").plan().expires)
        assertEquals(LocalDateTime.of(2026, 10, 26, 6, 15, 30), parse("expires" to "2026-10-26T06:15:30").plan().expires)
    }

    // ---------------------------------------------------------------- missing fields

    @Test
    fun everyRequiredTopLevelFieldMissing() {
        for (key in listOf("schema_version", "week_of", "expires", "directives")) {
            assertIssue(parse(key to Remove).errors(), key, key, "missing")
            assertIssue(parse(key to null).errors(), key, key, "missing")
        }
    }

    @Test
    fun everyRequiredDirectiveFieldMissing() {
        for (key in listOf("id", "tier", "trigger", "steps", "why", "quiet")) {
            val path = "directives[$ISHA].$key"
            assertIssue(parse(path to Remove).errors(), path, key, "missing")
        }
        val fallbackPath = "fallback_plan.directives[1].why"
        assertIssue(parse(fallbackPath to Remove).errors(), fallbackPath, "why", "missing")
    }

    @Test
    fun everyRequiredStepAndTriggerFieldMissing() {
        val cases = listOf(
            "directives[$FAJR].steps[1].text",
            "directives[$FAJR].steps[1].min_seconds",
            "directives[$FAJR].trigger.type",
            "directives[$FAJR].trigger.prayer",
            "directives[$WATER].trigger.at",
            "directives[$OPENS].trigger.apps",
            "directives[$OPENS].trigger.window_min",
            "directives[$OPENS].trigger.opens",
        )
        for (path in cases) {
            assertIssue(parse(path to Remove).errors(), path, path.substringAfterLast('.'), "missing")
        }
    }

    @Test
    fun emptyObjectListsEveryMissingFieldAtOnce() {
        val result = PlanParser.parse("{}")
        val errors = result.errors()
        for (key in listOf("schema_version", "week_of", "expires", "directives")) assertIssue(errors, key, "missing")
        assertIssue(result.warnings, "bad_day", "nothing will fire")
    }

    @Test
    fun problemsInSeveralDirectivesAreAllReported() {
        val errors = parse(
            "directives[$FAJR].tier" to 7,
            "directives[$SLEEP].trigger.at" to "25:00",
            "fallback_plan.directives[0].quiet" to "loud",
            "week_of" to "next monday",
        ).errors()
        assertIssue(errors, "directives[$FAJR].tier", "1, 2 or 3")
        assertIssue(errors, "directives[$SLEEP].trigger.at", "24-hour")
        assertIssue(errors, "fallback_plan.directives[0].quiet", "queue")
        assertIssue(errors, "week_of", "2026-10-19")
    }

    // ---------------------------------------------------------------- wrong JSON types

    @Test
    fun numbersWrittenAsTextAreRejected() {
        assertIssue(parse("schema_version" to "1").errors(), "schema_version", "without quotes")
        assertIssue(parse("directives[$FAJR].tier" to "3").errors(), "directives[$FAJR].tier", "without quotes")
        assertIssue(parse("directives[$FAJR].steps[0].min_seconds" to "60").errors(), "directives[$FAJR].steps[0].min_seconds", "without quotes")
        assertIssue(parse("directives[$OPENS].trigger.opens" to "5").errors(), "directives[$OPENS].trigger.opens", "without quotes")
    }

    @Test
    fun fractionsAndOddNumberSpellingsAreNotWholeNumbers() {
        val path = "directives[$FAJR].tier"
        for (literal in listOf("2.5", "2.0", "1e3", "1e", "01", "+1", "0x2", "true", "NaN", "Infinity")) {
            assertIssue(parse(path to lit(literal)).errors(), path, "whole number")
        }
        assertIssue(parse("directives[$FAJR].steps[0].min_seconds" to 2.5).errors(), "directives[$FAJR].steps[0].min_seconds", "whole number", "2.5")
        assertIssue(parse("directives[$OPENS].trigger.window_min" to 0.5).errors(), "directives[$OPENS].trigger.window_min", "whole number")
    }

    @Test
    fun hugeNumbersAreRejectedNotWrapped() {
        for (literal in listOf("4294967299", "99999999999999999999999", "-2147483649")) {
            assertIssue(parse("directives[$FAJR].tier" to lit(literal)).errors(), "directives[$FAJR].tier", "outside the allowed range")
        }
        assertIssue(parse("schema_version" to lit("4294967297")).errors(), "schema_version", "outside the allowed range")
    }

    @Test
    fun textFieldsWithNonTextValuesAreRejected() {
        val cases = listOf(
            "directives[$FAJR].id" to 5,
            "directives[$FAJR].why" to true,
            "directives[$FAJR].quiet" to lit("queue"),
            "directives[$FAJR].title" to 12,
            "directives[$FAJR].trigger.type" to lit("[\"adhan\"]"),
            "directives[$FAJR].trigger.prayer" to lit("{\"name\": \"fajr\"}"),
            "directives[$FAJR].steps[0].text" to 1,
            "directives[$WATER].trigger.at" to 1100,
            "week_of" to 20261019,
            "expires" to false,
            "generated_by" to 3,
            "context" to lit("[]"),
            "day_boundary" to 4,
        )
        for ((path, value) in cases) {
            assertIssue(parse(path to value).errors(), path, "text in quotes")
        }
    }

    @Test
    fun listsAndObjectsInTheWrongPlaceAreRejected() {
        assertIssue(parse("directives" to lit("{}")).errors(), "directives", "list of directives")
        assertIssue(parse("directives" to "fajr").errors(), "directives", "list of directives")
        assertIssue(parse("directives[$FAJR]" to lit("[]")).errors(), "directives[$FAJR]", "must be an object")
        assertIssue(parse("directives[$FAJR]" to "fajr").errors(), "directives[$FAJR]", "must be an object")
        assertIssue(parse("directives[$FAJR].steps" to lit("{}")).errors(), "directives[$FAJR].steps", "list of steps")
        assertIssue(parse("directives[$FAJR].steps[0]" to "Make wudu").errors(), "directives[$FAJR].steps[0]", "must be an object")
        assertIssue(parse("directives[$FAJR].trigger" to lit("[]")).errors(), "directives[$FAJR].trigger", "object with a \"type\"")
        assertIssue(parse("directives[$FAJR].trigger" to "adhan").errors(), "directives[$FAJR].trigger", "object with a \"type\"")
        assertIssue(parse("directives[$WATER].trigger.days" to "mon").errors(), "directives[$WATER].trigger.days", "must be a list")
        assertIssue(parse("directives[$WATER].trigger.days" to lit("{\"mon\": true}")).errors(), "directives[$WATER].trigger.days", "must be a list")
        assertIssue(parse("directives[$OPENS].trigger.apps" to "com.instagram.android").errors(), "directives[$OPENS].trigger.apps", "must be a list")
        assertIssue(parse("bad_day" to lit("[\"fajr\"]")).errors(), "bad_day", "must be an object")
        assertIssue(parse("bad_day.keep" to "fajr").errors(), "bad_day.keep", "list of directive ids")
        assertIssue(parse("bad_day.keep" to Remove).errors(), "bad_day.keep", "list of directive ids")
        assertIssue(parse("bad_day.keep[0]" to 1).errors(), "bad_day.keep[0]", "directive id")
        assertIssue(parse("fallback_plan" to lit("[]")).errors(), "fallback_plan", "must be an object")
        assertIssue(parse("fallback_plan.directives" to Remove).errors(), "fallback_plan.directives", "missing")
        assertIssue(parse("fallback_plan.directives" to lit("[]")).errors(), "fallback_plan.directives", "empty")
        assertIssue(parse("directives" to lit("[]")).errors(), "directives", "empty")
        assertIssue(parse("directives[$FAJR].steps" to lit("[]")).errors(), "directives[$FAJR].steps", "empty")
    }

    // ---------------------------------------------------------------- whole-file problems

    @Test
    fun malformedJsonGivesPlainMessageWithLineAndColumn() {
        val missingComma = "{\n  \"schema_version\": 1\n  \"week_of\": \"2026-10-19\"\n}"
        val error = PlanParser.parse(missingComma).errors().single()
        assertEquals("", error.path)
        assertContains(error.message, "isn't valid JSON", "near line 3, column ", "missing comma")
        assertFalse("no parser internals in the message", "JsonDecodingException" in error.message || "\$" in error.message)

        val trailingComma = "{\n  \"bad_day\": {\"keep\": [\"fajr\",]}\n}"
        assertContains(PlanParser.parse(trailingComma).errors().single().message, "isn't valid JSON", "line 2", "comma after the last item")

        assertContains(PlanParser.parse("{\n  // planner notes\n}").errors().single().message, "isn't valid JSON", "line 2")
        assertContains(PlanParser.parse("{'schema_version': 1}").errors().single().message, "isn't valid JSON", "line 1, column 2")
        assertContains(PlanParser.parse("{} {}").errors().single().message, "isn't valid JSON", "line 1")
    }

    @Test
    fun cutOffFileReportsWhereItEnds() {
        for (text in listOf("{\n  \"schema_version\": ", "{\"schema_version\": 1", "{\"week_of\": \"2026-", "{", "{\"a\"")) {
            val message = PlanParser.parse(text).errors().single().message
            assertContains(message, "isn't valid JSON", "line ", "column ")
        }
        assertContains(PlanParser.parse("{\n  \"schema_version\": ").errors().single().message, "line 2, column 20")
    }

    @Test
    fun emptyFileSaysEmpty() {
        for (text in listOf("", " ", "\n\t \r\n", "\uFEFF", "\uFEFF \n")) {
            val error = PlanParser.parse(text).errors().single()
            assertEquals("", error.path)
            assertContains(error.message, "empty")
        }
    }

    @Test
    fun byteOrderMarkIsIgnored() {
        val result = PlanParser.parse("\uFEFF" + FULL)
        result.plan()
        assertEquals(emptyList<Issue>(), result.warnings)
        // Positions are counted as an editor shows them, without the invisible mark.
        assertContains(PlanParser.parse("\uFEFF{\n  \"a\" 1}").errors().single().message, "line 2, column 7")
    }

    @Test
    fun topLevelMustBeAnObject() {
        for (text in listOf("[]", "[$FULL]", "null", "\"plan\"", "42", "true")) {
            val error = PlanParser.parse(text).errors().single()
            assertEquals("", error.path)
            assertContains(error.message, "JSON object", "{")
        }
    }

    @Test
    fun fileOverOneMegabyteIsRejected() {
        val text = FULL.replace("\"_readme\": \"", "\"_readme\": \"" + "x".repeat(PlanParser.MAX_FILE_CHARS))
        assertContains(PlanParser.parse(text).errors().single().message, "too large")
    }

    @Test
    fun deepNestingIsReportedNotThrown() {
        // 100 000 levels of objects is about as deep as fits in the 1 MB limit.
        for (depth in listOf(PlanParser.MAX_NESTING + 1, 5_000, 100_000)) {
            val arrays = "{\"_x\": " + "[".repeat(depth) + "]".repeat(depth) + "}"
            assertContains(PlanParser.parse(arrays).errors().single().message, "nested", "line 1")
            val objects = "{\"_x\": ".repeat(depth) + "1" + "}".repeat(depth)
            assertContains(PlanParser.parse(objects).errors().single().message, "nested")
        }
        // Built as text: the JSON library itself overflows the stack on input like this.
        val days = FULL.replace("[\"mon\", \"wed\", \"fri\"]", "[".repeat(10_000) + "]".repeat(10_000))
        assertTrue(days.length > FULL.length)
        val message = PlanParser.parse(days).errors().single().message
        assertContains(message, "nested", "line 39")
    }

    @Test
    fun packageNameWithThousandsOfDotsDoesNotOverflow() {
        val dotty = "com" + ".a".repeat(200_000)
        val apps = (parse("directives[$OPENS].trigger.apps[1]" to dotty).plan().directives[OPENS].trigger as Trigger.AppUsage).apps
        assertEquals(dotty, apps[1])
        val path = "directives[$OPENS].trigger.apps[1]"
        assertIssue(parse(path to "$dotty.").errors(), path, "not an Android package name")
    }

    @Test(timeout = 20_000)
    fun longListsStayFastAndIssueListsStayShort() {
        // Tens of thousands of distinct apps: duplicate checks must not be quadratic.
        val apps = (1..60_000).map { "com.a.b$it" }
        val loaded = parse("directives[$OPENS].trigger.apps" to apps).plan().directives[OPENS].trigger as Trigger.AppUsage
        assertEquals(60_000, loaded.apps.size)

        val result = parse("directives[$WATER].trigger.days" to List(150_000) { "x" }, "directives[$WATER].trigger.extra" to 1)
        val errors = result.errors()
        assertEquals(PlanParser.MAX_ISSUES + 1, errors.size)
        assertIssue(errors, "directives[$WATER].trigger.days[99]", "mon, tue")
        assertEquals(Issue("", "149900 more problems are not listed. Fix the ones above first."), errors.last())
        assertIssue(result.warnings, "directives[$WATER].trigger.extra", "Unknown field")

        val warned = parse("directives[$WATER].trigger.days" to List(1_000) { "mon" })
        warned.plan()
        assertEquals(PlanParser.MAX_ISSUES + 1, warned.warnings.size)
        assertContains(warned.warnings.last().message, "899 more warnings are not listed")
    }

    @Test
    fun bracketsInsideTextDoNotCountAsNesting() {
        val noisy = "[{\\\"[".repeat(500)
        val result = parse("_readme" to noisy, "context" to noisy)
        assertEquals(noisy, result.plan().context)
    }

    // ---------------------------------------------------------------- schema_version

    @Test
    fun newerSchemaVersionAsksForAppUpdate() {
        val error = parse("schema_version" to 3).errors().single()
        assertEquals("schema_version", error.path)
        assertContains(error.message, "schema_version 3", "versions up to 2", "Update the app", "version 2 plan")
    }

    @Test
    fun schemaVersionBelowOneIsRejected() {
        for (version in listOf(0, -1)) {
            assertIssue(parse("schema_version" to version).errors(), "schema_version", "must be 2", "version 1 plans still load")
        }
    }

    @Test
    fun versionOneAndTwoBothLoadAndReadTheSameFields() {
        assertEquals(2, Plan.SUPPORTED_SCHEMA_VERSION)
        val v2 = parse().plan()
        val v1Result = parse("schema_version" to 1)
        val v1 = v1Result.plan()
        assertEquals(emptyList<Issue>(), v1Result.warnings)
        assertEquals(1, v1.schemaVersion)
        // The version 2 fields are optional and read in a version 1 plan too.
        assertEquals(v2.copy(schemaVersion = 1), v1)
        assertTrue(v1.minimum != null && v1.afterBadDay != null && v1.directives[SLEEP].easier != null)
    }

    // ---------------------------------------------------------------- ids

    @Test
    fun invalidIdsAreRejected() {
        val path = "directives[$FAJR].id"
        for (id in listOf("Fajr", "fajr prayer", "-fajr", "_fajr", "", "fajr!", "fajr.am", "ﬁjr", "a".repeat(49), "fajr\n")) {
            assertIssue(parse(path to id).errors(), path, "not a valid id", "lowercase")
        }
        // bad_day.keep and minimum.keep name fajr, so they follow the new id.
        fun renamed(id: String) = parse(path to id, "bad_day.keep[0]" to id, "minimum.keep[0]" to id).plan().directives[FAJR].id
        assertEquals("a".repeat(48), renamed("a".repeat(48)))
        assertEquals("0-fajr_2", renamed("0-fajr_2"))
    }

    @Test
    fun duplicateIdsAreRejected() {
        val errors = parse("directives[$SLEEP].id" to "fajr").errors()
        assertIssue(errors, "directives[$SLEEP].id", "\"fajr\"", "used twice", "directives[$FAJR]")
    }

    @Test
    fun idsMustBeUniqueAcrossDirectivesAndFallbackPlan() {
        val errors = parse("fallback_plan.directives[1].id" to "sleep").errors()
        assertIssue(errors, "fallback_plan.directives[1].id", "used twice", "directives[$SLEEP]")
        val inFallback = parse("fallback_plan.directives[1].id" to "fallback-fajr").errors()
        assertIssue(inFallback, "fallback_plan.directives[1].id", "used twice", "fallback_plan.directives[0]")
    }

    // ---------------------------------------------------------------- tier, steps, quiet, title, why

    @Test
    fun tierOutOfRangeIsRejected() {
        for (tier in listOf(0, 4, -1, 100)) {
            assertIssue(parse("directives[$ISHA].tier" to tier).errors(), "directives[$ISHA].tier", "1, 2 or 3", "$tier")
        }
    }

    @Test
    fun tierOneMustHaveExactlyOneStep() {
        val twoSteps = lit("[{\"text\": \"Drink.\", \"min_seconds\": 5}, {\"text\": \"Again.\", \"min_seconds\": 5}]")
        assertIssue(parse("directives[$WATER].steps" to twoSteps).errors(), "directives[$WATER].steps", "exactly one step", "2")
        // A tier 2 or 3 directive may have a single step.
        parse("directives[$ISHA].steps" to lit("[{\"text\": \"Pray.\", \"min_seconds\": 5}]")).plan()
    }

    @Test
    fun longTierTwoChecklistWarns() {
        val nine = lit("[" + (1..9).joinToString { "{\"text\": \"Step $it.\", \"min_seconds\": 1}" } + "]")
        val result = parse("directives[$ISHA].steps" to nine)
        assertEquals(9, result.plan().directives[ISHA].steps.size)
        assertIssue(result.warnings, "directives[$ISHA].steps", "9 steps")
    }

    @Test
    fun stepProblemsAreRejected() {
        val p = "directives[$FAJR].steps[1]"
        assertIssue(parse("$p.min_seconds" to -1).errors(), "$p.min_seconds", "between 0 and 3600")
        assertIssue(parse("$p.min_seconds" to 3601).errors(), "$p.min_seconds", "between 0 and 3600")
        assertIssue(parse("$p.text" to "   ").errors(), "$p.text", "must not be empty")
        val long = parse("$p.text" to "x".repeat(121))
        long.plan()
        assertIssue(long.warnings, "$p.text", "121 characters")
    }

    @Test
    fun quietOverrideOnlyOnTierThree() {
        for (index in listOf(ISHA, WATER)) {
            val path = "directives[$index].quiet"
            assertIssue(parse(path to "override").errors(), path, "override", "tier 3")
        }
        assertIssue(parse("fallback_plan.directives[0].quiet" to "override").errors(), "fallback_plan.directives[0].quiet", "tier 3")
    }

    @Test
    fun unknownQuietValueIsRejected() {
        val path = "directives[$ISHA].quiet"
        for (value in listOf("loud", "Queue", "", "mute")) {
            assertIssue(parse(path to value).errors(), path, "\"queue\", \"silent\", \"ignore\" or \"override\"")
        }
    }

    @Test
    fun titleProblems() {
        val path = "directives[$FAJR].title"
        assertIssue(parse(path to "").errors(), path, "must not be empty")
        assertIssue(parse(path to "  ").errors(), path, "must not be empty")
        val long = parse(path to "X".repeat(41))
        assertEquals("X".repeat(41), long.plan().directives[FAJR].title)
        assertIssue(long.warnings, path, "41 characters")
    }

    @Test
    fun whyProblems() {
        val path = "directives[$FAJR].why"
        assertIssue(parse(path to "").errors(), path, "must not be empty")
        val long = parse(path to "w".repeat(91))
        long.plan()
        assertIssue(long.warnings, path, "91 characters")
    }

    // ---------------------------------------------------------------- time trigger

    @Test
    fun badTimesAreRejected() {
        val path = "directives[$WATER].trigger.at"
        for (at in listOf("24:00", "7:00", "07:60", "7am", "07:00:00", "0700", "07.00", " 07:00", "", "-1:00", "23:5")) {
            assertIssue(parse(path to at).errors(), path, "24-hour time", "00:00 to 23:59")
        }
        assertEquals(LocalTime.MIDNIGHT, (parse(path to "00:00").plan().directives[WATER].trigger as Trigger.Time).at)
        assertEquals(LocalTime.of(23, 59), (parse(path to "23:59").plan().directives[WATER].trigger as Trigger.Time).at)
    }

    @Test
    fun badDayBoundaryIsRejected() {
        assertIssue(parse("day_boundary" to "4:00").errors(), "day_boundary", "24-hour time")
        assertIssue(parse("day_boundary" to "24:00").errors(), "day_boundary", "24-hour time")
    }

    @Test
    fun badDayCodesAreRejected() {
        val path = "directives[$WATER].trigger.days"
        for (code in listOf<Any>("Mon", "monday", "m", "", 1)) {
            assertIssue(parse("$path[1]" to code).errors(), "$path[1]", "mon, tue, wed, thu, fri, sat, sun")
        }
        assertIssue(parse("$path[1]" to lit("[\"wed\"]")).errors(), "$path[1]", "got a list")
    }

    @Test
    fun emptyDaysListIsRejected() {
        val path = "directives[$WATER].trigger.days"
        assertIssue(parse(path to lit("[]")).errors(), path, "empty", "never fire")
    }

    @Test
    fun duplicateDaysWarnButLoad() {
        val path = "directives[$WATER].trigger.days"
        val result = parse(path to listOf("mon", "fri", "mon"))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), (result.plan().directives[WATER].trigger as Trigger.Time).days)
        assertIssue(result.warnings, "$path[2]", "\"mon\"", "twice")
    }

    @Test
    fun allSevenDaysAreAccepted() {
        val all = listOf("sun", "sat", "fri", "thu", "wed", "tue", "mon")
        val trigger = parse("directives[$WATER].trigger.days" to all).plan().directives[WATER].trigger as Trigger.Time
        assertEquals(DayOfWeek.entries.toSet(), trigger.days)
    }

    // ---------------------------------------------------------------- adhan trigger

    @Test
    fun badPrayerIsRejected() {
        val path = "directives[$ISHA].trigger.prayer"
        for (prayer in listOf("Isha", "jumuah", "isha'a", "", "ISHA")) {
            assertIssue(parse(path to prayer).errors(), path, "fajr, dhuhr, asr, maghrib, isha")
        }
        for (prayer in Prayer.entries) {
            assertEquals(Trigger.Adhan(prayer), parse(path to prayer.json).plan().directives[ISHA].trigger)
        }
    }

    // ---------------------------------------------------------------- app_usage trigger

    @Test
    fun appUsageAppsProblems() {
        val t = "directives[$OPENS].trigger"
        assertIssue(parse("$t.apps" to Remove).errors(), "$t.apps", "missing", "package")
        assertIssue(parse("$t.apps" to lit("[]")).errors(), "$t.apps", "empty")
        for (bad in listOf<Any>("TikTok", "com", "com..tiktok", "1com.tiktok", "com.tik tok", ".com.tiktok", "com.tiktok.", "", 5)) {
            assertIssue(parse("$t.apps[1]" to bad).errors(), "$t.apps[1]", "not an Android package name", "com.zhiliaoapp.musically")
        }
        assertIssue(parse("$t.apps[1]" to lit("{\"package\": \"com.x.y\"}")).errors(), "$t.apps[1]", "an object is not an Android package name")
    }

    @Test
    fun duplicateAppsWarnButLoad() {
        val t = "directives[$OPENS].trigger"
        val result = parse("$t.apps" to listOf("com.instagram.android", "com.instagram.android"))
        assertEquals(listOf("com.instagram.android"), (result.plan().directives[OPENS].trigger as Trigger.AppUsage).apps)
        assertIssue(result.warnings, "$t.apps[1]", "twice")
    }

    @Test
    fun appUsageNeedsExactlyOneKindOfLimit() {
        val t = "directives[$OPENS].trigger"
        assertIssue(parse("$t.continuous_min" to 10).errors(), t, "either", "not both")
        assertIssue(parse("$t.opens" to Remove, "$t.window_min" to Remove).errors(), t, "\"opens\" + \"window_min\"", "\"continuous_min\"")
        assertIssue(parse("$t.window_min" to Remove).errors(), "$t.window_min", "missing")
        assertIssue(parse("$t.opens" to Remove).errors(), "$t.opens", "missing")
        val c = "directives[$SCROLL].trigger"
        assertIssue(parse("$c.window_min" to 10).errors(), c, "not both")
    }

    @Test
    fun appUsageRanges() {
        val t = "directives[$OPENS].trigger"
        val c = "directives[$SCROLL].trigger"
        assertIssue(parse("$t.opens" to 0).errors(), "$t.opens", "between 1 and 1000")
        assertIssue(parse("$t.opens" to 1001).errors(), "$t.opens", "between 1 and 1000")
        assertIssue(parse("$t.window_min" to 0).errors(), "$t.window_min", "between 1 and 1440")
        assertIssue(parse("$t.window_min" to 1441).errors(), "$t.window_min", "between 1 and 1440")
        assertIssue(parse("$c.continuous_min" to 0).errors(), "$c.continuous_min", "between 1 and 1440")
        assertIssue(parse("$c.continuous_min" to 1441).errors(), "$c.continuous_min", "between 1 and 1440")
        assertIssue(parse("$c.cooldown_min" to -1).errors(), "$c.cooldown_min", "between 0 and 1440")
        assertIssue(parse("$c.cooldown_min" to 1441).errors(), "$c.cooldown_min", "between 0 and 1440")

        val edges = parse("$t.opens" to 1000, "$t.window_min" to 1440, "$c.continuous_min" to 1, "$c.cooldown_min" to 1440).plan()
        assertEquals(UsageLimit.Opens(1000, 1440), (edges.directives[OPENS].trigger as Trigger.AppUsage).limit)
        assertEquals(1440, (edges.directives[SCROLL].trigger as Trigger.AppUsage).cooldownMin)
    }

    @Test
    fun cooldownDefaultsToTheLimitWindow() {
        val c = "directives[$SCROLL].trigger"
        val continuous = parse("$c.cooldown_min" to Remove).plan().directives[SCROLL].trigger as Trigger.AppUsage
        assertEquals(20, continuous.cooldownMin)
        val t = "directives[$OPENS].trigger"
        val opens = parse("$t.window_min" to 45).plan().directives[OPENS].trigger as Trigger.AppUsage
        assertEquals(45, opens.cooldownMin)
        val explicit = parse("$t.cooldown_min" to 5).plan().directives[OPENS].trigger as Trigger.AppUsage
        assertEquals(5, explicit.cooldownMin)
    }

    // ---------------------------------------------------------------- unknown and reserved trigger types

    @Test
    fun unknownTriggerTypeWarnsAndPlanStillLoads() {
        val path = "directives[$SLEEP].trigger"
        val result = parse("$path.type" to "wifi")
        val plan = result.plan()
        assertEquals(Trigger.Unsupported("wifi"), plan.directives[SLEEP].trigger)
        assertEquals("the rest of the directive is kept", "SLEEP", plan.directives[SLEEP].title)
        assertIssue(result.warnings, "$path.type", "\"wifi\"", "isn't supported", "never fire", "time, adhan, app_usage")
        // Its other fields are not checked against any trigger type.
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun reservedTriggerTypesSayReserved() {
        for (type in Trigger.RESERVED_TYPES) {
            val result = parse("directives[$SLEEP].trigger" to lit("{\"type\": \"$type\", \"lat\": 1.5}"))
            assertEquals(Trigger.Unsupported(type), result.plan().directives[SLEEP].trigger)
            assertIssue(result.warnings, "directives[$SLEEP].trigger.type", "reserved")
        }
    }

    @Test
    fun misspelledTriggerTypeSuggestsTheRightOne() {
        val path = "directives[$SLEEP].trigger.type"
        for ((typo, meant) in listOf("Time" to "time", "ADHAN" to "adhan", "app-usage" to "app_usage", " time" to "time", "App Usage" to "app_usage")) {
            val result = parse(path to typo)
            result.plan()
            assertIssue(result.warnings, path, "Did you mean \"$meant\"?")
        }
        assertFalse(parse(path to "geofence").warnings.single().message.contains("Did you mean"))
    }

    @Test
    fun emptyTriggerTypeIsUnsupported() {
        val result = parse("directives[$SLEEP].trigger.type" to "")
        assertEquals(Trigger.Unsupported(""), result.plan().directives[SLEEP].trigger)
        assertIssue(result.warnings, "directives[$SLEEP].trigger.type", "isn't supported")
    }

    // ---------------------------------------------------------------- unknown keys

    @Test
    fun unknownKeysWarnEverywhereWithTheirPath() {
        val paths = listOf(
            "plan_name",
            "directives[$FAJR].colour",
            "directives[$FAJR].steps[0].duration",
            "directives[$FAJR].trigger.offset_min",
            "directives[$WATER].trigger.repeat",
            "directives[$OPENS].trigger.limit",
            "bad_day.until",
            "fallback_plan.note",
            "fallback_plan.directives[1].priority",
            "directives[$SLEEP].variants.easier.sound",
            "directives[$SLEEP].variants.easier.steps[0].duration",
            "directives[$SLEEP].adapt.step_up_after_easy",
            "fallback_plan.directives[0].adapt.reset",
            "minimum.until",
            "after_bad_day.unsure",
        )
        val result = parse(*paths.map { it to "x" }.toTypedArray())
        result.plan()
        for (path in paths) assertIssue(result.warnings, path, "Unknown field", path.substringAfterLast('.'), "ignored")
        assertEquals(paths.size, result.warnings.size)
    }

    @Test
    fun underscoreKeysNeverWarn() {
        val paths = listOf(
            "_note",
            "_",
            "directives[$FAJR]._why_this",
            "directives[$FAJR].steps[0]._source",
            "directives[$FAJR].trigger._comment",
            "directives[$OPENS].trigger._tiktok",
            "bad_day._reason",
            "fallback_plan._readme",
            "fallback_plan.directives[0]._x",
            "directives[$SLEEP].variants._why",
            "directives[$SLEEP].variants.easier._note",
            "directives[$SLEEP].variants.easier.steps[0]._x",
            "directives[$SLEEP].adapt._because",
            "minimum._note",
            "after_bad_day._note",
        )
        val result = parse(*paths.map { it to lit("{\"anything\": [1, {\"deep\": true}]}") }.toTypedArray())
        result.plan()
        assertEquals(emptyList<Issue>(), result.warnings)
    }

    // ---------------------------------------------------------------- bad_day

    @Test
    fun badDayKeepMustReferenceRealDirectives() {
        assertIssue(parse("bad_day.keep[1]" to "ishaa").errors(), "bad_day.keep[1]", "\"ishaa\"", "no directive")
        // A fallback_plan id does not count: bad_day applies to the main directives.
        assertIssue(parse("bad_day.keep[1]" to "fallback-fajr").errors(), "bad_day.keep[1]", "no directive in \"directives\"")
    }

    @Test
    fun badDayKeepIsCheckedEvenWhenADirectiveHasOtherProblems() {
        val errors = parse("directives[$WATER].tier" to 9, "bad_day.keep[1]" to "ishaa").errors()
        assertIssue(errors, "directives[$WATER].tier", "1, 2 or 3")
        assertIssue(errors, "bad_day.keep[1]", "no directive")
        // The broken directive's own id still counts as known.
        parse("directives[$WATER].tier" to 9, "bad_day.keep[1]" to "water").errors().forEach {
            assertFalse("unexpected $it", it.path.startsWith("bad_day"))
        }
    }

    @Test
    fun missingBadDayWarnsAndLoads() {
        val result = parse("bad_day" to Remove)
        assertEquals(BadDay.NONE, result.plan().badDay)
        assertIssue(result.warnings, "bad_day", "nothing will fire")
        assertIssue(parse("bad_day" to null).warnings, "bad_day", "nothing will fire")
    }

    @Test
    fun emptyOrDuplicateBadDayKeepWarns() {
        val empty = parse("bad_day.keep" to lit("[]"))
        assertEquals(emptyList<String>(), empty.plan().badDay.keep)
        assertIssue(empty.warnings, "bad_day.keep", "empty")

        val twice = parse("bad_day.keep" to listOf("fajr", "sleep", "fajr"))
        assertEquals(listOf("fajr", "sleep"), twice.plan().badDay.keep)
        assertIssue(twice.warnings, "bad_day.keep[2]", "twice")
    }

    @Test
    fun badDayMayKeepAnUnsupportedDirective() {
        val result = parse("directives[$SLEEP].trigger.type" to "geofence")
        assertTrue("sleep" in result.plan().badDay.keep)
    }

    // ---------------------------------------------------------------- reserved ids

    @Test
    fun idsStartingWithVigilAreReservedForTheApp() {
        val path = "directives[$WATER].id"
        assertIssue(parse(path to "vigil-water").errors(), path, "\"vigil-\"", "reserved", "built-in", "like \"water\"")
        for (id in listOf("vigil-demo", "vigil-demo-3", "vigil-check-in")) {
            assertIssue(parse(path to id).errors(), path, "reserved")
        }
        // Nothing valid is left to suggest.
        val bare = parse(path to "vigil-").errors().single { it.path == path }
        assertContains(bare.message, "reserved", "Choose another id.")
        assertFalse(bare.message, bare.message.contains("like"))
        // Stripping the prefix once would leave another reserved id, so that is not suggested either.
        val twice = parse(path to "vigil-vigil-water").errors().single { it.path == path }
        assertContains(twice.message, "reserved", "Choose another id.")
        assertFalse(twice.message, twice.message.contains("like"))
        assertIssue(parse("fallback_plan.directives[0].id" to "vigil-fajr").errors(), "fallback_plan.directives[0].id", "reserved")
        // Only the prefix is reserved.
        for (id in listOf("vigilance", "vigil_water", "my-vigil-water", "vigil")) {
            assertEquals(id, parse(path to id).plan().directives[WATER].id)
        }
    }

    // ---------------------------------------------------------------- rating

    @Test
    fun ratingDefaultsToSampleForTierOneAndAlwaysForTiersTwoAndThree() {
        assertEquals(RatingPolicy.SAMPLE, RatingPolicy.defaultFor(1))
        val plan = parse(
            "directives[$SLEEP].rating" to Remove,
            "directives[$OPENS].rating" to null,
            "fallback_plan.directives[0].rating" to Remove,
        ).plan()
        assertEquals(RatingPolicy.ALWAYS, plan.directives[FAJR].rating) // tier 3
        assertEquals(RatingPolicy.ALWAYS, plan.directives[ISHA].rating) // tier 2
        assertEquals(RatingPolicy.SAMPLE, plan.directives[WATER].rating) // tier 1
        assertEquals(RatingPolicy.ALWAYS, plan.directives[SLEEP].rating)
        assertEquals(RatingPolicy.ALWAYS, plan.directives[OPENS].rating)
        assertEquals(RatingPolicy.SAMPLE, plan.directives[SCROLL].rating)
        assertEquals(RatingPolicy.ALWAYS, plan.fallbackPlan!!.directives[0].rating)
        assertEquals(RatingPolicy.SAMPLE, plan.fallbackPlan!!.directives[1].rating)
        // The default follows the tier the plan gives.
        assertEquals(RatingPolicy.ALWAYS, parse("directives[$WATER].tier" to 2).plan().directives[WATER].rating)
    }

    @Test
    fun everyRatingValueIsRead() {
        for (policy in RatingPolicy.entries) {
            val plan = parse("directives[$WATER].rating" to policy.json, "fallback_plan.directives[1].rating" to policy.json).plan()
            assertEquals(policy, plan.directives[WATER].rating)
            assertEquals(policy, plan.fallbackPlan!!.directives[1].rating)
        }
    }

    @Test
    fun badRatingIsRejected() {
        val path = "directives[$ISHA].rating"
        for (value in listOf("often", "Always", "NEVER", "", "sampled", "every 3")) {
            assertIssue(parse(path to value).errors(), path, "\"always\", \"sample\" or \"never\"")
        }
        for (value in listOf<Any>(1, true, lit("[\"always\"]"), lit("{\"policy\": \"always\"}"))) {
            assertIssue(parse(path to value).errors(), path, "text in quotes")
        }
        assertIssue(parse("fallback_plan.directives[1].rating" to "x").errors(), "fallback_plan.directives[1].rating", "\"sample\"")
    }

    // ---------------------------------------------------------------- variants.easier

    @Test
    fun withVariantRunsTheEasierVersionThePlanWrote() {
        val plan = parse().plan()
        val sleep = plan.directives[SLEEP]
        val easier = sleep.withVariant(Variant.EASIER)
        assertEquals("PHONE AWAY", easier.title)
        assertEquals("Just the phone tonight.", easier.why)
        assertEquals(sleep.easier!!.steps, easier.steps)
        // Everything else stays the directive's own.
        assertEquals(sleep.copy(title = easier.title, steps = easier.steps, why = easier.why), easier)

        // Without its own title and why, the easier version keeps the directive's (here the id, as there is no title).
        val scroll = plan.directives[SCROLL]
        val scrollEasier = scroll.withVariant(Variant.EASIER)
        assertEquals("scroll_long", scrollEasier.title)
        assertEquals("Twenty minutes is enough.", scrollEasier.why)
        assertEquals(listOf(Step("Look away from the screen.", 5)), scrollEasier.steps)

        // No variant, an unknown one, or a directive without variants.easier: unchanged.
        assertEquals(sleep, sleep.withVariant(null))
        assertEquals(sleep, sleep.withVariant("harder"))
        assertEquals(plan.directives[WATER], plan.directives[WATER].withVariant(Variant.EASIER))
    }

    @Test
    fun easierStepsFollowTheSameRulesAsTheDirectiveSteps() {
        val e = "directives[$SLEEP].variants.easier"
        assertIssue(parse("$e.steps[0].min_seconds" to -1).errors(), "$e.steps[0].min_seconds", "between 0 and 3600")
        assertIssue(parse("$e.steps[0].min_seconds" to 3601).errors(), "$e.steps[0].min_seconds", "between 0 and 3600")
        assertIssue(parse("$e.steps[0].min_seconds" to 2.5).errors(), "$e.steps[0].min_seconds", "whole number")
        assertIssue(parse("$e.steps[0].min_seconds" to Remove).errors(), "$e.steps[0].min_seconds", "missing")
        assertIssue(parse("$e.steps[0].text" to " ").errors(), "$e.steps[0].text", "must not be empty")
        assertIssue(parse("$e.steps[0].text" to Remove).errors(), "$e.steps[0].text", "missing")
        assertIssue(parse("$e.steps[0]" to "Phone away.").errors(), "$e.steps[0]", "must be an object")
        assertIssue(parse("$e.steps" to Remove).errors(), "$e.steps", "missing", "variants.easier needs at least one step")
        assertIssue(parse("$e.steps" to null).errors(), "$e.steps", "missing")
        assertIssue(parse("$e.steps" to lit("[]")).errors(), "$e.steps", "empty", "variants.easier")
        assertIssue(parse("$e.steps" to lit("{}")).errors(), "$e.steps", "list of steps")

        val long = parse("$e.steps[0].text" to "x".repeat(121))
        long.plan()
        assertIssue(long.warnings, "$e.steps[0].text", "121 characters")
    }

    @Test
    fun easierOfATierOneDirectiveHasExactlyOneStep() {
        val e = "directives[$SCROLL].variants.easier"
        val two = lit("[{\"text\": \"Look away.\", \"min_seconds\": 5}, {\"text\": \"Stand up.\", \"min_seconds\": 5}]")
        assertIssue(parse("$e.steps" to two).errors(), "$e.steps", "tier 1", "exactly one step", "2")
        // Tier 2 and 3 easier versions may have several steps, like their directives.
        val sleep = parse("directives[$SLEEP].variants.easier.steps" to two).plan().directives[SLEEP]
        assertEquals(2, sleep.easier!!.steps.size)
    }

    @Test
    fun easierStepLocksMustFitInsideTheTakeoverMaximum() {
        val e = "directives[$SLEEP].variants.easier"
        fun locks(vararg seconds: Int) = lit("[" + seconds.joinToString { "{\"text\": \"Rest.\", \"min_seconds\": $it}" } + "]")
        assertEquals(4800, parse("$e.steps" to locks(3600, 1200)).plan().directives[SLEEP].easier!!.steps.sumOf { it.minSeconds })
        val error = parse("$e.steps" to locks(3600, 1201)).errors().single()
        assertEquals("$e.steps", error.path)
        assertContains(error.message, "81 minutes", "90 minutes", "variants.easier under 80 minutes")
    }

    @Test
    fun longTierTwoEasierChecklistWarns() {
        val nine = lit("[" + (1..9).joinToString { "{\"text\": \"Step $it.\", \"min_seconds\": 1}" } + "]")
        val result = parse(
            "directives[$ISHA].variants" to lit("{\"easier\": {\"steps\": $nine}}"),
            "directives[$ISHA].adapt" to lit("{\"step_down_after_hard\": 2}"),
        )
        assertEquals(9, result.plan().directives[ISHA].easier!!.steps.size)
        assertIssue(result.warnings, "directives[$ISHA].variants.easier.steps", "9 steps")
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun easierTitleAndWhyProblems() {
        val e = "directives[$SLEEP].variants.easier"
        assertIssue(parse("$e.title" to "").errors(), "$e.title", "must not be empty")
        assertIssue(parse("$e.title" to "  ").errors(), "$e.title", "must not be empty")
        assertIssue(parse("$e.title" to 5).errors(), "$e.title", "text in quotes")
        assertIssue(parse("$e.why" to " ").errors(), "$e.why", "must not be empty", "leave it out")
        assertIssue(parse("$e.why" to false).errors(), "$e.why", "text in quotes")

        val long = parse("$e.title" to "T".repeat(41), "$e.why" to "w".repeat(91))
        val easier = long.plan().directives[SLEEP].easier!!
        assertEquals("T".repeat(41), easier.title)
        assertIssue(long.warnings, "$e.title", "41 characters")
        assertIssue(long.warnings, "$e.why", "91 characters")

        // Left out: the directive's own title and why are used when it runs.
        val plain = parse("$e.title" to Remove, "$e.why" to Remove).plan().directives[SLEEP]
        assertEquals(Variant(null, plain.easier!!.steps, null), plain.easier)
        assertEquals("SLEEP", plain.withVariant(Variant.EASIER).title)
        assertEquals("Tomorrow is decided tonight.", plain.withVariant(Variant.EASIER).why)
    }

    @Test
    fun variantsAndEasierMustBeObjects() {
        val v = "directives[$SLEEP].variants"
        for (value in listOf<Any>("easier", lit("[]"), lit("[{\"steps\": []}]"), 1)) {
            assertIssue(parse(v to value).errors(), v, "variants must be an object")
        }
        for (value in listOf<Any>("fewer steps", lit("[]"), 2, true)) {
            assertIssue(parse("$v.easier" to value).errors(), "$v.easier", "must be an object", "\"steps\"")
        }
    }

    @Test
    fun brokenEasierIsReportedOnlyOnce() {
        // adapt is fine: the problem is the variant, so nothing is reported at adapt as well.
        val errors = parse("directives[$SLEEP].variants.easier" to "short").errors()
        assertEquals(listOf("directives[$SLEEP].variants.easier"), errors.map { it.path })
        val noSteps = parse("directives[$SLEEP].variants.easier.steps" to Remove).errors()
        assertEquals(listOf("directives[$SLEEP].variants.easier.steps"), noSteps.map { it.path })
        val notAnObject = parse("directives[$SLEEP].variants" to "easier").errors()
        assertEquals(listOf("directives[$SLEEP].variants"), notAnObject.map { it.path })
    }

    @Test
    fun unknownVariantNamesWarnAndAreIgnored() {
        val result = parse("directives[$WATER].variants" to lit("{\"harder\": {\"steps\": []}, \"_note\": \"later\"}"))
        val water = result.plan().directives[WATER]
        assertNull(water.easier)
        assertIssue(result.warnings, "directives[$WATER].variants.harder", "Unknown variant", "\"harder\"", "only knows \"easier\"")
        assertEquals(1, result.warnings.size)

        // Next to an easier version, which is still read.
        val both = parse("directives[$SLEEP].variants.shorter" to lit("{\"steps\": [{\"text\": \"x\", \"min_seconds\": 1}]}"))
        assertEquals("PHONE AWAY", both.plan().directives[SLEEP].easier!!.title)
        assertIssue(both.warnings, "directives[$SLEEP].variants.shorter", "Unknown variant")
        assertEquals(1, both.warnings.size)
    }

    @Test
    fun emptyOrNullVariantsMeanNoEasierVersion() {
        for (variants in listOf(lit("{}"), lit("{\"easier\": null}"), JsonNull)) {
            val result = parse("directives[$WATER].variants" to variants)
            assertNull(result.plan().directives[WATER].easier)
            assertEquals(emptyList<Issue>(), result.warnings)
        }
    }

    @Test
    fun easierWithoutAdaptIsNeverUsedAndWarns() {
        for (adapt in listOf<Any?>(Remove, null)) {
            val result = parse("directives[$SCROLL].adapt" to adapt)
            val scroll = result.plan().directives[SCROLL]
            assertEquals("still read", listOf(Step("Look away from the screen.", 5)), scroll.easier!!.steps)
            assertNull(scroll.stepDownAfterHard)
            assertIssue(result.warnings, "directives[$SCROLL].variants.easier", "never used", "adapt", "step_down_after_hard")
            assertEquals(1, result.warnings.size)
        }
        val fallback = parse("fallback_plan.directives[0].adapt" to Remove)
        assertIssue(fallback.warnings, "fallback_plan.directives[0].variants.easier", "never used")
    }

    // ---------------------------------------------------------------- adapt

    @Test
    fun stepDownAfterHardIsOneToTen() {
        val a = "directives[$SLEEP].adapt"
        for (n in listOf(1, 10)) {
            assertEquals(n, parse("$a.step_down_after_hard" to n).plan().directives[SLEEP].stepDownAfterHard)
        }
        for (n in listOf(0, 11, -1, 100)) {
            assertIssue(parse("$a.step_down_after_hard" to n).errors(), "$a.step_down_after_hard", "between 1 and 10", "$n")
        }
        assertIssue(parse("$a.step_down_after_hard" to 1.5).errors(), "$a.step_down_after_hard", "whole number")
        assertIssue(parse("$a.step_down_after_hard" to "2").errors(), "$a.step_down_after_hard", "without quotes")
        assertIssue(parse("$a.step_down_after_hard" to Remove).errors(), "$a.step_down_after_hard", "missing")
        assertIssue(parse("$a.step_down_after_hard" to null).errors(), "$a.step_down_after_hard", "missing")
        assertEquals(10, PlanParser.MAX_STEP_DOWN_AFTER_HARD)
    }

    @Test
    fun adaptMustBeAnObject() {
        val a = "directives[$SLEEP].adapt"
        for (value in listOf<Any>(2, "2", lit("[2]"), true)) {
            assertIssue(parse(a to value).errors(), a, "adapt must be an object", "step_down_after_hard")
        }
    }

    @Test
    fun adaptNeedsAnEasierVariant() {
        val adapt = lit("{\"step_down_after_hard\": 2}")
        val a = "directives[$WATER].adapt"
        assertIssue(parse(a to adapt).errors(), a, "adapt needs variants.easier", "remove adapt")
        assertIssue(parse(a to adapt, "directives[$WATER].variants" to null).errors(), a, "needs variants.easier")
        assertIssue(parse(a to adapt, "directives[$WATER].variants" to lit("{}")).errors(), a, "needs variants.easier")
        assertIssue(parse(a to adapt, "directives[$WATER].variants" to lit("{\"easier\": null}")).errors(), a, "needs variants.easier")
        val onlyUnknown = parse(a to adapt, "directives[$WATER].variants" to lit("{\"shorter\": {\"steps\": []}}"))
        assertIssue(onlyUnknown.errors(), a, "needs variants.easier")
        assertIssue(onlyUnknown.warnings, "directives[$WATER].variants.shorter", "Unknown variant")
        val f = "fallback_plan.directives[1].adapt"
        assertIssue(parse(f to adapt).errors(), f, "needs variants.easier")
        // Removing the variant from a directive that has adapt.
        assertIssue(parse("directives[$SLEEP].variants" to Remove).errors(), "directives[$SLEEP].adapt", "needs variants.easier")
    }

    @Test
    fun adaptWithRatingNeverWarns() {
        val result = parse("directives[$SLEEP].rating" to "never")
        val sleep = result.plan().directives[SLEEP]
        assertEquals(RatingPolicy.NEVER, sleep.rating)
        assertEquals(3, sleep.stepDownAfterHard)
        assertIssue(result.warnings, "directives[$SLEEP].adapt", "rating is \"never\"", "never run", "\"always\" or \"sample\"")
        assertEquals(1, result.warnings.size)
        // "sample" still asks now and then, so it can step down.
        assertEquals(emptyList<Issue>(), parse("directives[$SLEEP].rating" to "sample").warnings)
    }

    // ---------------------------------------------------------------- minimum

    @Test
    fun minimumIsOptional() {
        for (value in listOf<Any?>(Remove, null)) {
            val result = parse("minimum" to value, "after_bad_day" to value)
            assertNull(result.plan().minimum)
            assertEquals(emptyList<Issue>(), result.warnings)
        }
    }

    @Test
    fun minimumKeepMustReferenceRealDirectives() {
        assertIssue(parse("minimum.keep[1]" to "slep").errors(), "minimum.keep[1]", "\"slep\"", "no directive")
        // A fallback_plan id does not count, as for bad_day.
        assertIssue(parse("minimum.keep[1]" to "fallback-fajr").errors(), "minimum.keep[1]", "no directive in \"directives\"")
        // Checked even when a directive has other problems; the broken directive's id still counts.
        val errors = parse("directives[$WATER].tier" to 9, "minimum.keep[1]" to "water").errors()
        assertTrue(errors.toString(), errors.none { it.path.startsWith("minimum") })
        // Any directive may be kept, also one bad_day does not keep.
        assertEquals(listOf("fajr", "water"), parse("minimum.keep[1]" to "water").plan().minimum!!.keep)
    }

    @Test
    fun minimumShapeProblems() {
        assertIssue(parse("minimum" to lit("[\"fajr\"]")).errors(), "minimum", "minimum must be an object", "keep")
        assertIssue(parse("minimum" to "fajr").errors(), "minimum", "must be an object")
        assertIssue(parse("minimum.keep" to "fajr").errors(), "minimum.keep", "list of directive ids")
        assertIssue(parse("minimum.keep" to Remove).errors(), "minimum.keep", "list of directive ids")
        assertIssue(parse("minimum.keep" to null).errors(), "minimum.keep", "list of directive ids")
        assertIssue(parse("minimum.keep[0]" to 1).errors(), "minimum.keep[0]", "directive id")
    }

    @Test
    fun emptyOrDuplicateMinimumKeepWarns() {
        val empty = parse("minimum.keep" to lit("[]"))
        assertEquals(emptyList<String>(), empty.plan().minimum!!.keep)
        assertIssue(empty.warnings, "minimum.keep", "empty", "nothing will fire on a minimum day")
        assertEquals(1, empty.warnings.size)

        val twice = parse("minimum.keep" to listOf("sleep", "fajr", "sleep"))
        assertEquals(listOf("sleep", "fajr"), twice.plan().minimum!!.keep)
        assertIssue(twice.warnings, "minimum.keep[2]", "\"sleep\"", "twice in minimum.keep")
    }

    @Test
    fun minimumThatNothingChoosesWarns() {
        for (afterBadDay in listOf<Any?>(Remove, null)) {
            val result = parse("after_bad_day" to afterBadDay)
            assertEquals(listOf("fajr", "sleep"), result.plan().minimum!!.keep)
            assertIssue(result.warnings, "minimum", "never used", "Add \"after_bad_day\"", "\"worse\": \"minimum\"", "remove minimum")
            assertEquals(1, result.warnings.size)
        }
        // With an after_bad_day that never says "minimum", the fix is to change an answer, not to add after_bad_day.
        val neverChosen = parse("after_bad_day" to lit("{\"better\": \"normal\", \"same\": \"bad_day\", \"worse\": \"bad_day\"}"))
        assertEquals(listOf("fajr", "sleep"), neverChosen.plan().minimum!!.keep)
        val warning = neverChosen.warnings.single()
        assertEquals("minimum", warning.path)
        assertContains(warning.message, "never used", "None of the after_bad_day answers", "usually \"worse\"", "remove minimum")
        assertFalse(warning.message, warning.message.contains("Add \"after_bad_day\""))
        // Any one answer choosing it is enough.
        val viaBetter = parse("after_bad_day" to lit("{\"better\": \"minimum\", \"same\": \"bad_day\", \"worse\": \"bad_day\"}"))
        assertEquals(emptyList<Issue>(), viaBetter.warnings)
        // A broken after_bad_day is reported by itself; minimum is not blamed as well.
        for (broken in listOf(parse("after_bad_day" to "minimum"), parse("after_bad_day.worse" to "minimal"), parse("after_bad_day.worse" to Remove))) {
            broken.errors()
            assertTrue(broken.warnings.toString(), broken.warnings.none { it.path == "minimum" })
        }
    }

    // ---------------------------------------------------------------- after_bad_day

    @Test
    fun afterBadDayMapsEachAnswerToAMode() {
        val after = parse().plan().afterBadDay!!
        assertEquals(DayMode.NORMAL, after.modeFor(CheckInAnswer.BETTER))
        assertEquals(DayMode.BAD_DAY, after.modeFor(CheckInAnswer.SAME))
        assertEquals(DayMode.MINIMUM, after.modeFor(CheckInAnswer.WORSE))

        // Any mode for any answer: the plan decides.
        val odd = parse(
            "after_bad_day" to lit("{\"better\": \"bad_day\", \"same\": \"minimum\", \"worse\": \"normal\", \"_why\": \"testing\"}"),
        )
        assertEquals(emptyList<Issue>(), odd.warnings)
        assertEquals(AfterBadDay(DayMode.BAD_DAY, DayMode.MINIMUM, DayMode.NORMAL), odd.plan().afterBadDay)
    }

    @Test
    fun afterBadDayIsOptional() {
        for (value in listOf<Any?>(Remove, null)) {
            val result = parse("after_bad_day" to value, "minimum" to Remove)
            assertNull(result.plan().afterBadDay)
            assertEquals(emptyList<Issue>(), result.warnings)
        }
        // It does not need a minimum list when no answer chooses "minimum".
        val noMinimum = parse("minimum" to Remove, "after_bad_day.worse" to "bad_day")
        assertEquals(AfterBadDay(DayMode.NORMAL, DayMode.BAD_DAY, DayMode.BAD_DAY), noMinimum.plan().afterBadDay)
        assertEquals(emptyList<Issue>(), noMinimum.warnings)
    }

    @Test
    fun everyAnswerNeedsAMode() {
        for (key in listOf("better", "same", "worse")) {
            for (value in listOf<Any?>(Remove, null)) {
                assertIssue(parse("after_bad_day.$key" to value).errors(), "after_bad_day.$key", "missing", "better, same and worse")
            }
        }
        val errors = parse("after_bad_day" to lit("{}")).errors()
        for (key in listOf("better", "same", "worse")) assertIssue(errors, "after_bad_day.$key", "missing")
    }

    @Test
    fun unknownModeIsRejected() {
        val path = "after_bad_day.worse"
        for (value in listOf("off", "Normal", "bad-day", "badday", "", "minimal")) {
            assertIssue(parse(path to value).errors(), path, "\"normal\", \"bad_day\" or \"minimum\"")
        }
        for (value in listOf<Any>(1, true, lit("[\"normal\"]"))) {
            assertIssue(parse(path to value).errors(), path, "text in quotes")
        }
    }

    @Test
    fun afterBadDayMustBeAnObject() {
        for (value in listOf<Any>("minimum", lit("[\"normal\", \"bad_day\", \"minimum\"]"), 3)) {
            assertIssue(parse("after_bad_day" to value).errors(), "after_bad_day", "must be an object", "\"worse\": \"minimum\"")
        }
    }

    @Test
    fun choosingMinimumNeedsAMinimumList() {
        for (value in listOf<Any?>(Remove, null)) {
            val errors = parse("minimum" to value).errors()
            assertIssue(errors, "after_bad_day.worse", "chooses \"minimum\"", "no minimum list", "\"minimum\": {\"keep\"", "\"bad_day\" instead")
            assertEquals(1, errors.size)
        }
        // Each answer that chooses it is named.
        val two = parse("minimum" to Remove, "after_bad_day.same" to "minimum").errors()
        assertIssue(two, "after_bad_day.same", "no minimum list")
        assertIssue(two, "after_bad_day.worse", "no minimum list")
        // A minimum that is there but broken is reported by itself.
        val broken = parse("minimum" to lit("[\"fajr\"]")).errors()
        assertEquals(listOf("minimum"), broken.map { it.path })
    }

    // ---------------------------------------------------------------- dates

    @Test
    fun expiresWithTimezoneIsRejectedHelpfully() {
        for (value in listOf("2026-10-26T04:00Z", "2026-10-26T04:00:00z", "2026-10-26T04:00:00+03:00", "2026-10-26T04:00-0500", "2026-10-26T04:00:00.000Z", "2026-10-26T04:00+03")) {
            assertIssue(parse("expires" to value).errors(), "expires", "local time", "without a timezone", "2026-10-26T04:00")
        }
    }

    @Test
    fun badExpiresFormatsAreNotMistakenForTimezones() {
        for (value in listOf("26-10-2026", "2026/10/26", "next week", "2026-10-26 04:00", "2026-10-26T4:00", "2026-10-26T24:00", "")) {
            val error = parse("expires" to value).errors().single { it.path == "expires" }
            assertContains(error.message, "must look like 2026-10-26T04:00 or 2026-10-26")
        }
        assertIssue(parse("expires" to "2026-02-30").errors(), "expires", "not a real date")
        assertIssue(parse("expires" to "2026-02-30T04:00").errors(), "expires", "not a real date")
    }

    @Test
    fun badWeekOfIsRejected() {
        assertIssue(parse("week_of" to "2026-10-19T00:00").errors(), "week_of", "date like 2026-10-19")
        assertIssue(parse("week_of" to "19/10/2026").errors(), "week_of", "date like 2026-10-19")
        assertIssue(parse("week_of" to "2026-13-01").errors(), "week_of", "not a real date")
    }

    @Test
    fun expiresMustBeAfterWeekOf() {
        assertIssue(parse("expires" to "2026-10-12").errors(), "expires", "must be after week_of", "2026-10-19")
        assertIssue(parse("expires" to "2026-10-18T23:59").errors(), "expires", "must be after week_of")
        assertIssue(parse("expires" to "2026-10-19T00:00").errors(), "expires", "must be after week_of")
        // A date-only expires on the week_of date with a midnight day boundary is that same instant.
        assertIssue(parse("expires" to "2026-10-19", "day_boundary" to "00:00").errors(), "expires", "must be after week_of")
        assertEquals(LocalDateTime.of(2026, 10, 19, 0, 1), parse("expires" to "2026-10-19T00:01").plan().expires)
    }

    // ---------------------------------------------------------------- messages

    @Test
    fun issueTextStartsWithTheJsonPath() {
        val error = parse("fallback_plan.directives[1].trigger.at" to "25:00").errors().single()
        assertEquals("fallback_plan.directives[1].trigger.at", error.path)
        assertTrue(error.toString(), error.toString().startsWith("fallback_plan.directives[1].trigger.at: "))
        assertEquals("This file is empty.", PlanParser.parse("").errors().single().toString())
    }

    @Test
    fun hugeValuesAreShortenedInMessages() {
        val huge = "X".repeat(100_000)
        val cases = listOf(
            "directives[$FAJR].id" to huge,
            "directives[$FAJR].quiet" to huge,
            "directives[$FAJR].trigger.prayer" to huge,
            "directives[$FAJR].tier" to lit("9".repeat(100_000)),
            "directives[$FAJR].id" to lit("x".repeat(100_000)),
            "directives[$WATER].trigger.at" to huge,
            "directives[$WATER].trigger.days[0]" to huge,
            "directives[$OPENS].trigger.apps[0]" to huge,
            "directives[$OPENS].trigger.apps[0]" to "com." + "a".repeat(100_000),
            "expires" to huge,
            "week_of" to huge,
            "bad_day.keep[0]" to huge,
            "minimum.keep[0]" to huge,
            "after_bad_day.worse" to huge,
            "directives[$FAJR].rating" to huge,
            "directives[$SLEEP].variants.$huge" to lit("{}"),
            "directives[$SLEEP].variants.easier.title" to huge,
            "directives[$SLEEP].variants.easier.why" to huge,
            "directives[$SLEEP].variants.easier.steps[0].text" to huge,
            "directives[$SLEEP].adapt.step_down_after_hard" to lit("9".repeat(100_000)),
        )
        for (edit in cases) {
            val result = parse(edit)
            for (issue in (result as? ParseResult.Failure)?.errors.orEmpty() + result.warnings) {
                assertTrue("message too long for ${edit.first}: ${issue.message.take(200)}", issue.message.length < 400)
            }
        }
        val type = parse("directives[$SLEEP].trigger.type" to huge).warnings.single()
        assertTrue(type.message.length < 400)
    }

    @Test
    fun messagesAreWrittenForPeopleNotProgrammers() {
        val texts = listOf(
            "{}",
            "[]",
            "",
            "{\"a\": }",
            edited("directives[$FAJR].tier" to 2.5, "directives[$WATER].trigger.days" to lit("[\"x\"]"), "plan_name" to 1),
            edited(
                "directives[$FAJR].id" to "vigil-fajr",
                "directives[$FAJR].rating" to "often",
                "directives[$WATER].adapt" to lit("{\"step_down_after_hard\": 0}"),
                "directives[$ISHA].variants" to lit("{\"harder\": {}, \"easier\": {\"steps\": []}}"),
                "directives[$SLEEP].variants.easier" to lit("[]"),
                "directives[$SCROLL].variants.easier.steps" to lit("[{\"text\": \"a\", \"min_seconds\": 1}, {\"text\": \"b\", \"min_seconds\": 1}]"),
                "minimum" to Remove,
                "after_bad_day.same" to Remove,
                "after_bad_day.better" to "fine",
            ),
            edited("minimum.keep" to lit("[]"), "after_bad_day" to Remove, "directives[$OPENS].rating" to "never", "directives[$OPENS].adapt" to lit("{\"step_down_after_hard\": 2}")),
        )
        val issues = texts.flatMap { text ->
            val result = PlanParser.parse(text)
            (result as? ParseResult.Failure)?.errors.orEmpty() + result.warnings
        }
        for (issue in issues) {
            for (jargon in listOf("Exception", "null pointer", "kotlin", "JsonPrimitive", "JsonLiteral", "at path: \$", "offset")) {
                assertFalse("\"$jargon\" in: $issue", issue.message.contains(jargon, ignoreCase = true))
            }
            assertTrue("ends like a sentence: $issue", issue.message.trimEnd().last() in ".?)")
        }
    }

    // ---------------------------------------------------------------- never throws

    @Test
    fun everyTruncationOfAValidPlanFailsCleanly() {
        for (end in 0 until FULL.length) {
            val text = FULL.substring(0, end)
            val result = parseWithoutThrowing(text)
            // Only the full text is a complete JSON object; the trailing newline is the only removable part.
            if (end < FULL.trimEnd().length) assertTrue("cut at $end loaded", result is ParseResult.Failure)
        }
        for (start in 1 until FULL.length) parseWithoutThrowing(FULL.substring(start))
    }

    @Test
    fun randomMutationsNeverThrow() {
        val random = Random(20261013)
        val alphabet = "{}[]\":,0123456789.-+eE \\nultrfase\u0000\uD800\uFEFFéx_"
        repeat(3_000) {
            val chars = StringBuilder(FULL)
            repeat(1 + random.nextInt(4)) {
                val at = random.nextInt(chars.length)
                when (random.nextInt(4)) {
                    0 -> chars.setCharAt(at, alphabet[random.nextInt(alphabet.length)])
                    1 -> chars.deleteCharAt(at)
                    2 -> chars.insert(at, alphabet[random.nextInt(alphabet.length)])
                    else -> chars.delete(at, minOf(chars.length, at + random.nextInt(40)))
                }
            }
            parseWithoutThrowing(chars.toString())
        }
    }

    @Test
    fun randomValuesInEveryFieldNeverThrow() {
        val random = Random(7)
        val values = listOf(
            "null", "true", "0", "-0", "1", "-1", "2.5", "1e999", "-1e-999", "99999999999999999999", "\"\"", "\" \"",
            "\"\\u0000\"", "\"\\ud800\"", "[]", "{}", "[null]", "[[]]", "[{}]", "{\"type\": null}", "\"07:00\"",
            "\"2026-10-26\"", "\"override\"", "\"time\"", "\"app_usage\"", "\"geofence\"", "[\"mon\", \"mon\"]",
            "[\"com.a.b\", 1]", "{\"keep\": [1, null, \"fajr\"]}", "unquoted", "'single'", "\"never\"", "\"minimum\"",
            "{\"easier\": {}}", "{\"easier\": {\"steps\": [{}]}}", "{\"step_down_after_hard\": 2}", "\"vigil-x\"",
        ).map { lit(it) }
        val paths = mutableListOf<String>()
        collectPaths(Json.parseToJsonElement(FULL), "", paths)
        repeat(3_000) {
            val edits = (1..1 + random.nextInt(3)).map { paths[random.nextInt(paths.size)] to values[random.nextInt(values.size)] }
            val text = try {
                edited(*edits.toTypedArray())
            } catch (e: RuntimeException) {
                return@repeat // an earlier edit replaced the parent of a later path
            }
            parseWithoutThrowing(text)
        }
    }

    @Test
    fun oddButValidJsonNeverThrows() {
        val texts = listOf(
            "{\"schema_version\": 1, \"schema_version\": 2}",
            "{\"\": 1}",
            "{\"directives\": [null, 1, \"a\", [], {}]}",
            "{\"directives\": [{\"trigger\": {\"type\": \"app_usage\", \"apps\": [null], \"opens\": null}}]}",
            "{\"bad_day\": {\"keep\": null}}",
            "{\"fallback_plan\": {}}",
            "{\"directives\": [{\"variants\": {\"easier\": {\"steps\": [null]}}, \"adapt\": {\"step_down_after_hard\": null}}]}",
            "{\"directives\": [{\"variants\": null, \"adapt\": [], \"rating\": {}}]}",
            "{\"minimum\": {\"keep\": [null]}, \"after_bad_day\": {\"better\": null, \"same\": [], \"worse\": \"minimum\"}}",
            "{\"after_bad_day\": \"minimum\", \"minimum\": 1}",
            "{\"directives\": {\"0\": {}}}",
            "\uFEFF\uFEFF{}",
            "{\"a\": \"\\u0000\\ud800\\udfff\"}",
            " \n {} \n ",
        )
        for (text in texts) parseWithoutThrowing(text)
    }

    // ---------------------------------------------------------------- helpers

    private object Remove

    companion object {
        // Directive indexes in full-plan.json.
        const val FAJR = 0 // adhan, tier 3, override
        const val ISHA = 1 // adhan, tier 2, no title
        const val WATER = 2 // time with days, tier 1
        const val SLEEP = 3 // time without days, tier 3, override
        const val OPENS = 4 // app_usage opens/window_min, tier 2
        const val SCROLL = 5 // app_usage continuous_min with cooldown 0, tier 1

        val FULL: String = PlanParserTest::class.java.getResourceAsStream("/parser/full-plan.json")!!
            .use { it.readBytes().decodeToString() }
    }

    /** An unquoted JSON literal or a whole JSON value, used as-is. */
    private fun lit(json: String): JsonElement = Json.parseToJsonElement(json)

    private fun parse(vararg edits: Pair<String, Any?>): ParseResult = parseWithoutThrowing(edited(*edits))

    /** full-plan.json with each `path to value` applied: [Remove] deletes the key, Kotlin null writes JSON null. */
    private fun edited(vararg edits: Pair<String, Any?>): String {
        var root: JsonElement = Json.parseToJsonElement(FULL)
        for ((path, value) in edits) {
            val segments = Regex("""([^.\[\]]+)|\[(\d+)]""").findAll(path).map { m ->
                m.groups[2]?.value?.toInt() ?: m.groups[1]!!.value
            }.toList()
            root = put(root, segments, toJson(value))
        }
        return root.toString()
    }

    private fun put(element: JsonElement, segments: List<Any>, value: JsonElement?): JsonElement {
        val rest = segments.drop(1)
        return when (val head = segments.first()) {
            is Int -> {
                val list = element.jsonArray.toMutableList()
                when {
                    rest.isNotEmpty() -> list[head] = put(list[head], rest, value)
                    value == null -> list.removeAt(head)
                    head == list.size -> list += value
                    else -> list[head] = value
                }
                JsonArray(list)
            }
            else -> {
                val map = element.jsonObject.toMutableMap()
                val key = head as String
                when {
                    rest.isNotEmpty() -> map[key] = put(map.getValue(key), rest, value)
                    value == null -> map.remove(key)
                    else -> map[key] = value
                }
                JsonObject(map)
            }
        }
    }

    private fun toJson(value: Any?): JsonElement? = when (value) {
        Remove -> null
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map { toJson(it)!! })
        else -> error("Can't write $value as JSON")
    }

    private fun collectPaths(element: JsonElement, path: String, out: MutableList<String>) {
        when (element) {
            is JsonObject -> element.forEach { (key, child) ->
                val p = if (path.isEmpty()) key else "$path.$key"
                out += p
                collectPaths(child, p, out)
            }
            is JsonArray -> element.forEachIndexed { i, child ->
                out += "$path[$i]"
                collectPaths(child, "$path[$i]", out)
            }
            else -> Unit
        }
    }

    private fun parseWithoutThrowing(text: String): ParseResult {
        val result = try {
            PlanParser.parse(text)
        } catch (t: Throwable) {
            throw AssertionError("parse threw ${t::class.simpleName} for input of ${text.length} chars: ${text.take(300)}", t)
        }
        if (result is ParseResult.Failure) {
            assertTrue("Failure without errors for: ${text.take(300)}", result.errors.isNotEmpty())
        }
        return result
    }

    private fun ParseResult.plan(): Plan = when (this) {
        is ParseResult.Success -> plan
        is ParseResult.Failure -> fail("Expected the plan to load, but got errors:\n  ${errors.joinToString("\n  ")}") as Nothing
    }

    private fun ParseResult.errors(): List<Issue> = when (this) {
        is ParseResult.Failure -> errors
        is ParseResult.Success -> fail("Expected errors, but the plan loaded (warnings: $warnings)") as Nothing
    }

    private fun assertIssue(issues: List<Issue>, path: String, vararg fragments: String) {
        val match = issues.any { issue -> issue.path == path && fragments.all { issue.message.contains(it, ignoreCase = true) } }
        if (!match) {
            fail("No issue at \"$path\" containing ${fragments.toList()}. Got:\n  ${issues.joinToString("\n  ")}")
        }
    }

    private fun assertContains(message: String, vararg fragments: String) {
        for (fragment in fragments) {
            assertTrue("\"$fragment\" not in: $message", message.contains(fragment, ignoreCase = true))
        }
    }

    @Test
    fun stepLocksMustFitInsideTheTakeoverMaximum() {
        fun planWithLocks(vararg seconds: Int): String {
            val steps = seconds.joinToString(",") { """{"text": "Work.", "min_seconds": $it}""" }
            return """{"schema_version": 1, "week_of": "2026-10-19", "expires": "2026-10-26T04:00",
                "directives": [{"id": "study", "tier": 3, "trigger": {"type": "time", "at": "09:00"},
                "steps": [$steps], "why": "Focus.", "quiet": "queue"}], "bad_day": {"keep": ["study"]}}"""
        }
        assertTrue(PlanParser.parse(planWithLocks(3600, 1200)) is ParseResult.Success)
        val failure = PlanParser.parse(planWithLocks(3600, 1201)) as ParseResult.Failure
        val issue = failure.errors.single()
        assertEquals("directives[0].steps", issue.path)
        assertTrue(issue.message, issue.message.contains("90 minutes"))
    }
}
