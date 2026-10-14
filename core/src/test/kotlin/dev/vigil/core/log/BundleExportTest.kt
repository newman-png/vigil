package dev.vigil.core.log

import dev.vigil.core.engine.Engine
import dev.vigil.core.plan.CheckInAnswer
import dev.vigil.core.plan.ParseResult
import dev.vigil.core.plan.Plan
import dev.vigil.core.plan.PlanParser
import dev.vigil.core.plan.Rating
import dev.vigil.core.plan.Variant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime

class BundleExportTest {
    private val planText = """{"_readme": "a plan", "schema_version": 2, "week_of": "2026-10-19", "steps": [1, 2.5, -3, null, true, "x"]}"""

    // ---------------------------------------------------------------- layout

    @Test
    fun buildHasEveryTopLevelFieldInOrder() {
        val out = BundleExport.build(emptyList(), meta())
        assertTrue(out.startsWith("{\n    \"_readme\": "))
        assertTrue(out.endsWith("}\n"))
        assertEquals(
            listOf(
                "_readme", "format", "schema_version", "exported_at", "app_version", "timezone", "period", "plan_status",
                "plan", "stepped_down", "settings", "summary", "ratings", "check_ins", "event_types", "events",
            ),
            parse(out).keys.toList(),
        )
    }

    @Test
    fun buildWritesTheMeta() {
        val root = parse(BundleExport.build(emptyList(), meta()))
        assertEquals(BundleExport.README, root.string("_readme"))
        assertEquals("vigil-bundle", root.string("format"))
        assertEquals(2, root.int("schema_version"))
        assertEquals("2026-10-26T07:12:40Z", root.string("exported_at"))
        assertEquals("1.1", root.string("app_version"))
        assertEquals("Europe/London", root.string("timezone"))
        val period = root["period"]!!.jsonObject
        assertEquals(listOf("since", "until"), period.keys.toList())
        assertEquals("2026-10-18T21:14:09+01:00", period.string("since"))
        assertEquals("2026-10-26T07:12:40Z", period.string("until"))
        assertEquals("complete", root.string("plan_status"))
        assertEquals(listOf("morning", "study-block"), root["stepped_down"]!!.jsonArray.map { it.jsonPrimitive.content })
        val settings = root["settings"]!!.jsonObject
        assertEquals("in the order given", listOf(LogExport.SETTING_ALLOW_QUIET_OVERRIDE, "sound"), settings.keys.toList())
        assertEquals("true", settings.string(LogExport.SETTING_ALLOW_QUIET_OVERRIDE))
        assertEquals("false", settings.string("sound"))
        assertEquals(
            EventType.DESCRIPTIONS,
            root["event_types"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals("in the same order", EventType.DESCRIPTIONS.keys.toList(), root["event_types"]!!.jsonObject.keys.toList())
    }

    @Test
    fun buildWithNothingWritesNullsAndEmptyParts() {
        val meta = BundleMeta(
            "2026-10-26T07:12:40Z", "1.1", "UTC", null, "2026-10-26T07:12:40Z", null, BundleExport.STATUS_NONE, emptyList(), emptyMap(),
        )
        val root = parse(BundleExport.build(emptyList(), meta))
        assertEquals(JsonNull, root["period"]!!.jsonObject["since"])
        assertEquals(JsonNull, root["plan"])
        assertEquals("none", root.string("plan_status"))
        for (key in listOf("stepped_down", "ratings", "check_ins", "events")) assertEquals(key, JsonArray(emptyList()), root[key])
        for (key in listOf("settings", "summary")) assertEquals(key, JsonObject(emptyMap()), root[key])
    }

    /** The app (data/BundleExporter.kt) builds the meta with these names, types and this parameter order. */
    @Test
    fun metaKeepsTheParametersTheAppUses() {
        val positional = BundleMeta(
            "exportedAt", "appVersion", "timezone", "since", "until", "planText", "planStatus", listOf("steppedDown"),
            mapOf("k" to "v"),
        )
        val named = BundleMeta(
            exportedAt = "exportedAt", appVersion = "appVersion", timezone = "timezone", since = "since", until = "until",
            planText = "planText", planStatus = "planStatus", steppedDown = listOf("steppedDown"), settings = mapOf("k" to "v"),
        )
        assertEquals(named, positional)
        val build: (List<LogEvent>, BundleMeta) -> String = BundleExport::build
        assertTrue(build(emptyList(), named).isNotEmpty())
    }

    @Test
    fun formatAndSchemaVersion() {
        assertEquals("vigil-bundle", BundleExport.FORMAT)
        assertEquals("the next plan is written in the version this app reads", Plan.SUPPORTED_SCHEMA_VERSION, BundleExport.SCHEMA_VERSION)
        assertEquals(2, BundleExport.SCHEMA_VERSION)
        assertEquals(listOf("complete", "running", "none"), listOf(BundleExport.STATUS_COMPLETE, BundleExport.STATUS_RUNNING, BundleExport.STATUS_NONE))
    }

    @Test
    fun eventsAreKeptAsGivenTestFiresIncluded() {
        val events = listOf(
            LogEvent("2026-10-20T10:12:44+01:00", EventType.QUIET_OFF),
            LogEvent("2026-10-20T08:47:30+01:00", EventType.QUIET_ON),
            LogEvent("2026-10-20T09:00:02+01:00", EventType.DIRECTIVE_FIRED, directiveId = Engine.DEMO_2_ID, tier = 2, trigger = "debug", takeoverId = "d"),
            LogEvent("2026-10-22T23:04:27+01:00", EventType.RATED, directiveId = "sleep", tier = 3, trigger = "time", takeoverId = "t", variant = "easier", rating = "hard"),
        )
        val array = parse(BundleExport.build(events, meta()))["events"]!!.jsonArray
        assertEquals(events, array.map { LogJson.json.decodeFromJsonElement(LogEvent.serializer(), it) })
        assertEquals(listOf("ts", "type"), array[0].jsonObject.keys.toList())
        assertEquals(
            listOf("ts", "type", "directive_id", "tier", "trigger", "takeover_id", "variant", "rating"),
            array[3].jsonObject.keys.toList(),
        )
    }

    // ---------------------------------------------------------------- plan

    @Test
    fun planIsEmbeddedAsJsonWithItsKeysInOrder() {
        val plan = parse(BundleExport.build(emptyList(), meta(planText = planText)))["plan"]
        assertTrue(plan is JsonObject)
        assertEquals(Json.parseToJsonElement(planText), plan)
        assertEquals(listOf("_readme", "schema_version", "week_of", "steps"), plan!!.jsonObject.keys.toList())
        assertEquals(
            listOf("1", "2.5", "-3", "null", "true", "x"),
            plan.jsonObject["steps"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("a string stays a string", listOf(false, false, false, false, false, true), plan.jsonObject["steps"]!!.jsonArray.map { it.jsonPrimitive.isString })
    }

    @Test
    fun numbersAreWrittenInTheirPlainForm() {
        val text = """{"a": 2.50, "b": 1E2, "c": 12345678901234567890, "d": 0}"""
        val out = BundleExport.build(emptyList(), meta(planText = text))
        val plan = parse(out)["plan"]!!.jsonObject
        assertEquals(listOf("2.5", "100.0", "12345678901234567890", "0"), plan.values.map { it.jsonPrimitive.content })
    }

    @Test
    fun planWithANumberTooLargeToWriteIsKeptAsText() {
        val text = """{"schema_version": 2, "_note": 1e999}"""
        val plan = parse(BundleExport.build(emptyList(), meta(planText = text)))["plan"]
        assertEquals(JsonPrimitive(text), plan)
    }

    @Test
    fun planWithAByteOrderMarkOrSpacesIsStillEmbeddedAsJson() {
        for (text in listOf("﻿" + planText, "\n\n  $planText  \r\n")) {
            assertEquals(Json.parseToJsonElement(planText), parse(BundleExport.build(emptyList(), meta(planText = text)))["plan"])
        }
    }

    @Test
    fun planThatIsNotAJsonObjectIsKeptAsText() {
        val broken = listOf(
            "", "   ", "not json", "{\"week_of\": \"2026-10-19\",", "{\"a\": 1} trailing", "[1, 2]", "\"a string\"", "42", "null",
            "{week_of: 2026-10-19}", "[".repeat(5000) + "]".repeat(5000),
        )
        for (text in broken) {
            val plan = parse(BundleExport.build(emptyList(), meta(planText = text)))["plan"]
            assertEquals(text.take(20), JsonPrimitive(text), plan)
        }
    }

    @Test
    fun planNestedDeeperThanTheParserAcceptsIsKeptAsText() {
        fun nested(levels: Int) = "{\"a\":".repeat(levels) + "1" + "}".repeat(levels)
        val deepest = nested(PlanParser.MAX_NESTING)
        assertEquals(Json.parseToJsonElement(deepest), parse(BundleExport.build(emptyList(), meta(planText = deepest)))["plan"])
        for (levels in listOf(PlanParser.MAX_NESTING + 1, 20_000)) {
            val text = nested(levels)
            assertEquals("$levels levels", JsonPrimitive(text), parse(BundleExport.build(emptyList(), meta(planText = text)))["plan"])
        }
        val deepList = "{\"a\":" + "[".repeat(PlanParser.MAX_NESTING) + "]".repeat(PlanParser.MAX_NESTING) + "}"
        assertEquals(JsonPrimitive(deepList), parse(BundleExport.build(emptyList(), meta(planText = deepList)))["plan"])
    }

    @Test
    fun embeddedSamplePlanStillReadsAsTheSamePlan() {
        val plan = parse(BundleExport.build(emptyList(), meta(planText = SampleWeek.planText)))["plan"]!!
        val again = PlanParser.parse(plan.toString())
        assertTrue(again.toString(), again is ParseResult.Success)
        assertEquals(SampleWeek.plan, (again as ParseResult.Success).plan)
    }

    // ---------------------------------------------------------------- summary

    @Test
    fun summaryIsTheLogSummary() {
        val events = listOf(
            LogEvent("2026-10-19T05:47:52+01:00", EventType.DIRECTIVE_FIRED, directiveId = "fajr", trigger = "adhan", takeoverId = "a"),
            LogEvent("2026-10-19T05:57:02+01:00", EventType.DIRECTIVE_COMPLETED, directiveId = "fajr", trigger = "adhan", takeoverId = "a", durationS = 550),
            LogEvent("2026-10-19T05:57:06+01:00", EventType.RATED, directiveId = "fajr", trigger = "adhan", takeoverId = "a", rating = "ok"),
            LogEvent("2026-10-19T06:00:00+01:00", EventType.DIRECTIVE_FIRED, directiveId = Engine.CHECK_IN_ID, trigger = Engine.TRIGGER_CHECK_IN, takeoverId = "c"),
            LogEvent("2026-10-19T06:01:00+01:00", EventType.DIRECTIVE_FIRED, directiveId = "fajr", trigger = "debug", takeoverId = "d"),
        )
        val summary = parse(BundleExport.build(events, meta()))["summary"]!!.jsonObject
        assertEquals(LogExport.summary(events), summary)
        assertEquals("built-ins left out", listOf("fajr"), summary.keys.toList())
        val fajr = summary["fajr"]!!.jsonObject
        assertEquals("the test fire is left out", 1, fajr.int("directive_fired"))
        assertEquals(1, fajr.int("rated_ok"))
        for (key in listOf("rated_easy", "rated_hard", "rating_skipped", "skipped_minimum", "bypassed_minimum")) assertEquals(key, 0, fajr.int(key))
    }

    // ---------------------------------------------------------------- ratings

    @Test
    fun ratingsComeFromRatedAndRatingSkippedEventsInLogOrder() {
        fun e(ts: String, type: String, id: String, takeover: String, rating: String? = null, variant: String? = null, detail: String? = null) =
            LogEvent(ts, type, directiveId = id, tier = 2, trigger = "time", takeoverId = takeover, variant = variant, rating = rating, detail = detail)
        val events = listOf(
            e("2026-10-20T07:37:33+01:00", EventType.RATED, "morning", "t1", rating = "hard"),
            LogEvent("2026-10-20T08:00:00+01:00", EventType.QUIET_ON),
            e("2026-10-21T07:40:52+01:00", EventType.STEPPED_DOWN, "morning", "t2", variant = "easier"),
            e("2026-10-22T10:30:25+01:00", EventType.RATING_SKIPPED, "water-am", "t3", detail = "skipped"),
            e("2026-10-23T23:34:02+01:00", EventType.RATING_SKIPPED, "sleep", "t4", variant = "easier", detail = "not answered"),
            e("2026-10-24T07:30:33+01:00", EventType.RATED, "morning", "t5", rating = "ok", variant = "easier"),
            e("2026-10-24T07:30:34+01:00", EventType.DIRECTIVE_COMPLETED, "morning", "t5"),
        )
        val ratings = BundleExport.ratings(events)
        assertEquals(ratings, parse(BundleExport.build(events, meta()))["ratings"])
        assertEquals(4, ratings.size)
        assertEquals(
            JsonObject(
                linkedMapOf(
                    "ts" to JsonPrimitive("2026-10-20T07:37:33+01:00"),
                    "directive_id" to JsonPrimitive("morning"),
                    "takeover_id" to JsonPrimitive("t1"),
                    "rating" to JsonPrimitive("hard"),
                ),
            ),
            ratings[0],
        )
        assertEquals("no variant key when it ran as written", listOf("ts", "directive_id", "takeover_id", "rating"), ratings[0].jsonObject.keys.toList())
        assertEquals("skipped", ratings[1].jsonObject.string("rating"))
        assertEquals("water-am", ratings[1].jsonObject.string("directive_id"))
        assertEquals(listOf("ts", "directive_id", "takeover_id", "rating", "variant"), ratings[2].jsonObject.keys.toList())
        assertEquals("skipped", ratings[2].jsonObject.string("rating"))
        assertEquals("easier", ratings[2].jsonObject.string("variant"))
        assertEquals("ok", ratings[3].jsonObject.string("rating"))
        assertEquals("easier", ratings[3].jsonObject.string("variant"))
        assertEquals(listOf("t1", "t3", "t4", "t5"), ratings.map { it.jsonObject.string("takeover_id") })
    }

    @Test
    fun ratingsAndCheckInsLeaveOutTestFiresAndBuiltins() {
        // Played through the real engine: the TEST screen's previews and FIRE buttons, rated and answered.
        val phone = SampleWeek.Phone()
        phone.run("2026-10-20T12:00:00") { s, e -> Engine.fireNow(s, e, Engine.CHECK_IN_ID) }
        phone.answer("2026-10-20T12:00:09", CheckInAnswer.WORSE)
        phone.run("2026-10-20T12:01:00") { s, e -> Engine.fireNow(s, e, "water-am") }
        phone.steps("2026-10-20T12:01:01", 15)
        phone.rate("2026-10-20T12:01:20", Rating.HARD)
        phone.run("2026-10-20T12:02:00") { s, e -> Engine.fireNow(s, e, Engine.DEMO_1_ID) }
        phone.steps("2026-10-20T12:02:01", 3)
        phone.rate("2026-10-20T12:02:10", null)
        // An adhan test that the user left on screen and later dismissed.
        phone.trigger("2026-10-20T12:03:00", "dhuhr", "adhan_test")
        phone.run("2026-10-20T12:03:30") { s, e -> Engine.dismiss(s, e, phone.takeoverId) }

        val log = phone.log
        assertTrue("the engine logged them", log.count { it.type == EventType.RATED || it.type == EventType.RATING_SKIPPED } == 2)
        assertTrue(log.any { it.type == EventType.CHECK_IN && it.answer == "worse" })
        assertTrue("a test rating carries the test trigger", log.filter { it.type == EventType.RATED }.all { it.trigger == "debug" })
        val root = parse(BundleExport.build(log, meta()))
        assertEquals(JsonArray(emptyList()), root["ratings"])
        assertEquals(JsonArray(emptyList()), root["check_ins"])
        assertEquals(JsonObject(emptyMap()), root["summary"])
        assertEquals("events still hold them", log.size, root["events"]!!.jsonArray.size)
    }

    @Test
    fun aRatingOfABuiltinIsLeftOut() {
        val events = listOf(
            LogEvent("2026-10-20T12:00:00+01:00", EventType.RATED, directiveId = Engine.DEMO_3_ID, trigger = "time", takeoverId = "x", rating = "easy"),
            LogEvent("2026-10-20T12:00:01+01:00", EventType.RATED, directiveId = null, trigger = "time", takeoverId = "y", rating = "easy"),
            LogEvent("2026-10-20T12:00:02+01:00", EventType.RATED, directiveId = "fajr", trigger = "adhan", takeoverId = "z", rating = "easy"),
        )
        assertEquals(listOf("z"), BundleExport.ratings(events).map { it.jsonObject.string("takeover_id") })
    }

    // ---------------------------------------------------------------- check-ins

    @Test
    fun checkInsComeFromCheckInEvents() {
        val events = listOf(
            LogEvent("2026-10-23T04:00:03+01:00", EventType.DAY_MODE, mode = "bad_day", detail = "check-in due after a bad day"),
            LogEvent("2026-10-23T06:01:15+01:00", EventType.DIRECTIVE_FIRED, directiveId = Engine.CHECK_IN_ID, trigger = Engine.TRIGGER_CHECK_IN, takeoverId = "c1"),
            LogEvent(
                "2026-10-23T06:01:31+01:00", EventType.CHECK_IN, trigger = Engine.TRIGGER_CHECK_IN, takeoverId = "c1", answer = "worse",
                mode = "minimum", detail = "for 2026-10-23, after a bad day",
            ),
            LogEvent("2026-10-24T04:00:04+01:00", EventType.CHECK_IN, answer = "none", mode = "bad_day", detail = "not answered; the \"same\" answer applied"),
            LogEvent("2026-10-25T06:00:00Z", EventType.CHECK_IN, trigger = Engine.TRIGGER_CHECK_IN, takeoverId = "c2", answer = "better", mode = "normal"),
        )
        val checkIns = BundleExport.checkIns(events)
        assertEquals(checkIns, parse(BundleExport.build(events, meta()))["check_ins"])
        assertEquals(3, checkIns.size)
        assertEquals(listOf("ts", "answer", "mode", "detail"), checkIns[0].jsonObject.keys.toList())
        assertEquals(
            listOf(
                listOf("2026-10-23T06:01:31+01:00", "worse", "minimum", "for 2026-10-23, after a bad day"),
                listOf("2026-10-24T04:00:04+01:00", "none", "bad_day", "not answered; the \"same\" answer applied"),
            ),
            checkIns.take(2).map { c -> listOf("ts", "answer", "mode", "detail").map { c.jsonObject.string(it) } },
        )
        assertEquals("no detail key without a detail", listOf("ts", "answer", "mode"), checkIns[2].jsonObject.keys.toList())
    }

    // ---------------------------------------------------------------- readme

    @Test
    fun readmeIsOneShortCleanLine() {
        val readme = BundleExport.README
        assertFalse(readme.contains('\n'))
        assertFalse("double space", readme.contains("  "))
        assertFalse("unexpanded template", readme.contains("\${"))
        assertEquals(readme.trim(), readme)
        val words = readme.split(' ').size
        assertTrue("well under 300 words: $words", words <= 250)
    }

    @Test
    fun readmeExplainsEveryPartAndWhatMatters() {
        val readme = BundleExport.README
        val keys = parse(BundleExport.build(emptyList(), meta())).keys - "_readme"
        for (key in keys) assertTrue("readme should explain $key", readme.contains(key))
        val mentions = listOf(
            "VIGIL", "plan.json", "planner", "HANDOFF.md", "vision.md", "variants.easier", "adapt.step_down_after_hard",
            "EASY, OK or HARD", "takeover_id", "ts", "since null",
            BundleExport.STATUS_COMPLETE, BundleExport.STATUS_RUNNING, BundleExport.STATUS_NONE, BundleExport.RATING_SKIPPED,
            Engine.ANSWER_NONE, Engine.BUILTIN_ID_PREFIX, Variant.EASIER,
            "Ratings and check-ins are the main signal for next week's plan",
            "Bad days, minimum days and check-ins are never failures",
        )
        for (m in mentions) assertTrue("readme should mention $m", readme.contains(m))
        for (t in LogExport.TEST_TRIGGERS) assertTrue("readme should name the test trigger $t", readme.contains(t))
        assertFalse("the readme of the bundle is not the old log.json one", readme.contains("log.json"))
    }

    // ---------------------------------------------------------------- spec/sample-bundle.json

    @Test
    fun sampleBundleTellsTheWholeWeek() {
        val root = parse(SampleWeek.export())
        val events = root["events"]!!.jsonArray.map { LogJson.json.decodeFromJsonElement(LogEvent.serializer(), it) }
        fun has(type: String, predicate: (LogEvent) -> Boolean = { true }) =
            assertTrue("sample should contain $type", events.any { it.type == type && predicate(it) })

        // The plan that ran, complete: exported after it expired, while the fallback plan runs.
        assertEquals(Json.parseToJsonElement(SampleWeek.planText), root["plan"])
        assertEquals(BundleExport.STATUS_COMPLETE, root.string("plan_status"))
        has(EventType.PLAN_IMPORTED)
        has(EventType.PLAN_EXPIRED)
        has(EventType.DIRECTIVE_COMPLETED) { it.directiveId == "fb-fajr" }
        assertTrue("exported after the plan expired", OffsetDateTime.parse(root.string("exported_at")).toLocalDateTime() > SampleWeek.plan.expires)
        assertEquals(events.first().ts, root["period"]!!.jsonObject.string("since"))
        assertEquals(root.string("exported_at"), root["period"]!!.jsonObject.string("until"))

        // v1 behaviour.
        has(EventType.DIRECTIVE_COMPLETED) { it.directiveId == "fajr" && it.trigger == "adhan" }
        has(EventType.STEP_COMPLETED) { it.directiveId == "fajr" && it.durationS != null && it.stepText != null }
        has(EventType.DIRECTIVE_FIRED) { it.trigger == "app_usage" }
        has(EventType.DIRECTIVE_DISMISSED) { it.tier == 2 && it.stepsDone!! < it.stepsTotal!! }
        has(EventType.QUEUED_QUIET)
        has(EventType.DIRECTIVE_FIRED) { it.trigger == Engine.TRIGGER_QUIET_QUEUE }
        has(EventType.MISSED) { it.scheduledFor != null }

        // Ratings, and the step-down after two HARD ratings in a row, then the easier morning.
        val ratings = root["ratings"]!!.jsonArray.map { it.jsonObject }
        val steppedDown = events.single { it.type == EventType.STEPPED_DOWN }
        assertEquals("morning", steppedDown.directiveId)
        assertEquals(Variant.EASIER, steppedDown.variant)
        assertEquals(listOf("morning"), root["stepped_down"]!!.jsonArray.map { it.jsonPrimitive.content })
        val morningBefore = ratings.filter { it.string("directive_id") == "morning" && it.string("ts") <= steppedDown.ts }
        assertEquals(listOf("ok", "hard", "hard"), morningBefore.map { it.string("rating") })
        assertTrue(morningBefore.all { it["variant"] == null })
        val easier = ratings.filter { it.string("directive_id") == "morning" && it["variant"] != null }
        assertTrue("the easier morning was rated", easier.size >= 2 && easier.all { it.string("variant") == Variant.EASIER })
        has(EventType.DIRECTIVE_COMPLETED) { it.directiveId == "morning" && it.variant == Variant.EASIER && it.stepsTotal == 2 }
        has(EventType.DIRECTIVE_FIRED) { it.directiveId == "morning" && it.variant == null && it.stepsTotal == 4 }
        assertTrue("every rating is easy, ok, hard or skipped", ratings.all { it.string("rating") in setOf("easy", "ok", "hard", "skipped") })
        assertTrue("skipped by the user", ratings.any { it.string("rating") == "skipped" && it.string("directive_id") == "water-am" })
        has(EventType.RATING_SKIPPED) { it.detail == "skipped" }
        has(EventType.RATING_SKIPPED) { it.detail == "not answered" }
        assertEquals(events.count { it.type == EventType.RATED || it.type == EventType.RATING_SKIPPED }, ratings.size)

        // A hard day, the check-in the next morning (WORSE: a minimum day), and the one after it (BETTER).
        has(EventType.BAD_DAY_ON)
        has(EventType.BYPASSED_BAD_DAY) { it.directiveId == "morning" && it.variant == Variant.EASIER }
        has(EventType.SKIPPED_BAD_DAY)
        has(EventType.DIRECTIVE_FIRED) { it.directiveId == "sleep" && it.ts.startsWith("2026-10-22") } // kept on the bad day
        has(EventType.DAY_MODE) { it.mode == "bad_day" } // the check-in is due; its "same" answer applies meanwhile
        has(EventType.DIRECTIVE_FIRED) { it.directiveId == Engine.CHECK_IN_ID && it.trigger == Engine.TRIGGER_CHECK_IN }
        has(EventType.SKIPPED_MINIMUM)
        val checkIns = root["check_ins"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("worse" to "minimum", "better" to "normal"), checkIns.map { it.string("answer") to it.string("mode") })
        assertTrue(checkIns.all { it.string("ts").startsWith("2026-10-23") || it.string("ts").startsWith("2026-10-24") })

        // The summary counts the plan's directives only, and adds up.
        val summary = root["summary"]!!.jsonObject
        val ids = (SampleWeek.plan.directives + SampleWeek.plan.fallbackPlan!!.directives).map { it.id }.toSet()
        assertTrue(summary.keys.toString(), ids.containsAll(summary.keys))
        assertFalse(Engine.CHECK_IN_ID in summary.keys)
        val morning = summary["morning"]!!.jsonObject
        assertEquals(listOf(1, 2, 2), listOf("rated_easy", "rated_ok", "rated_hard").map { morning.int(it) })
        assertEquals(1, morning.int("skipped_minimum"))
        assertEquals(1, morning.int("bypassed_bad_day"))
        for ((id, counts) in summary) {
            val c = counts.jsonObject
            val closed = listOf("directive_completed", "directive_dismissed", "directive_timed_out", "bypassed_bad_day", "bypassed_minimum", "dropped")
                .sumOf { c.int(it) }
            assertEquals("$id: every fired takeover closed one way", c.int("directive_fired"), closed)
        }

        // A tidy log: in time order, every directive known, every takeover closed, no test fires.
        assertEquals("events are in time order", events.sortedBy { OffsetDateTime.parse(it.ts) }, events)
        assertTrue(events.all { it.directiveId == null || it.directiveId in ids || it.directiveId == Engine.CHECK_IN_ID })
        assertTrue(events.none { it.trigger in LogExport.TEST_TRIGGERS })
        val closers = setOf(
            EventType.DIRECTIVE_COMPLETED, EventType.DIRECTIVE_DISMISSED, EventType.DIRECTIVE_TIMED_OUT, EventType.BYPASSED_BAD_DAY,
            EventType.BYPASSED_MINIMUM, EventType.DROPPED, EventType.CHECK_IN,
        )
        for ((takeover, list) in events.filter { it.takeoverId != null }.groupBy { it.takeoverId }) {
            assertEquals("$takeover fired once", 1, list.count { it.type == EventType.DIRECTIVE_FIRED })
            assertEquals("$takeover closed once", 1, list.count { it.type in closers })
        }
        assertNull("nothing left open at the export", SampleWeek.phone.state.active)
    }

    /**
     * spec/sample-bundle.json must be exactly what [BundleExport.build] writes for [SampleWeek]. To regenerate it after a
     * deliberate change: VIGIL_UPDATE_SAMPLES=1 ./gradlew -p core cleanTest test --tests dev.vigil.core.log.BundleExportTest
     */
    @Test
    fun sampleBundleMatchesTheExport() {
        val file = File(System.getProperty("vigil.repoRoot"), "spec/sample-bundle.json")
        val expected = SampleWeek.export()
        if (System.getenv("VIGIL_UPDATE_SAMPLES") == "1") {
            file.parentFile.mkdirs()
            file.writeText(expected)
        }
        assertTrue("${file.path} is missing; see the KDoc of this test to generate it", file.isFile)
        assertEquals("${file.path} is out of date; see the KDoc of this test to regenerate it", expected, file.readText())
    }

    @Test
    fun theOldSampleLogIsGone() {
        assertFalse("spec/sample-bundle.json replaced it", File(System.getProperty("vigil.repoRoot"), "spec/sample-log.json").exists())
    }

    private fun meta(planText: String? = this.planText) = BundleMeta(
        exportedAt = "2026-10-26T07:12:40Z",
        appVersion = "1.1",
        timezone = "Europe/London",
        since = "2026-10-18T21:14:09+01:00",
        until = "2026-10-26T07:12:40Z",
        planText = planText,
        planStatus = BundleExport.STATUS_COMPLETE,
        steppedDown = listOf("morning", "study-block"),
        settings = linkedMapOf(LogExport.SETTING_ALLOW_QUIET_OVERRIDE to "true", "sound" to "false"),
    )

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.int(key: String) = this[key]!!.jsonPrimitive.int
}
