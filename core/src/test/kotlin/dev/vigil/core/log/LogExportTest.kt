package dev.vigil.core.log

import dev.vigil.core.engine.Engine
import dev.vigil.core.engine.TimeSchedule
import dev.vigil.core.plan.QuietPolicy
import dev.vigil.core.plan.Rating
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class LogExportTest {
    private val full = LogEvent(
        ts = "2026-10-19T05:48:04+01:00",
        type = EventType.STEP_COMPLETED,
        directiveId = "fajr",
        tier = 3,
        trigger = "adhan",
        takeoverId = "3f9a1c2e",
        scheduledFor = "2026-10-19T05:30:00+01:00",
        stepIndex = 0,
        stepText = "Sit up. \"Feet\" on the floor.\nThen stand.",
        durationS = 11,
        stepsDone = 1,
        stepsTotal = 3,
        silent = false,
        detail = "détail ✓",
    )

    // ---------------------------------------------------------------- JSON Lines

    @Test
    fun encodeLineOmitsNullFields() {
        val line = LogJson.encodeLine(LogEvent("2026-10-20T08:47:30+01:00", EventType.QUIET_ON))
        assertEquals("""{"ts":"2026-10-20T08:47:30+01:00","type":"quiet_on"}""", line)
    }

    @Test
    fun encodeLineUsesSnakeCaseStartsWithTsAndStaysOnOneLine() {
        val line = LogJson.encodeLine(full)
        assertTrue(line, line.startsWith("""{"ts":"""))
        assertFalse("a newline inside a field must be escaped", line.contains('\n'))
        for (key in listOf("directive_id", "takeover_id", "scheduled_for", "step_index", "step_text", "duration_s", "steps_done", "steps_total")) {
            assertTrue(key, line.contains("\"$key\":"))
        }
        assertTrue("false is a value, not a missing field", line.contains("\"silent\":false"))
    }

    @Test
    fun encodeLineRoundTrips() {
        val minimal = LogEvent("2026-10-22T06:33:41+01:00", EventType.BAD_DAY_ON, detail = "until 04:00 tomorrow")
        for (event in listOf(full, minimal)) {
            assertEquals(listOf(event), LogJson.decodeLines(LogJson.encodeLine(event)))
        }
    }

    @Test
    fun newFieldsAreWrittenOnlyWhenSet() {
        val rated = LogEvent(
            "2026-10-22T23:04:27+01:00", EventType.RATED, directiveId = "sleep", tier = 3, trigger = "time",
            takeoverId = "t1", variant = "easier", rating = "hard",
        )
        val line = LogJson.encodeLine(rated)
        assertTrue(line, line.endsWith(""""takeover_id":"t1","variant":"easier","rating":"hard"}"""))
        val checkIn = LogEvent("2026-10-23T07:12:00+01:00", EventType.CHECK_IN, answer = "worse", mode = "minimum", detail = "for 2026-10-23")
        assertEquals(
            """{"ts":"2026-10-23T07:12:00+01:00","type":"check_in","answer":"worse","mode":"minimum","detail":"for 2026-10-23"}""",
            LogJson.encodeLine(checkIn),
        )
        for (event in listOf(rated, checkIn)) assertEquals(listOf(event), LogJson.decodeLines(LogJson.encodeLine(event)))
        // A v1 line has none of them.
        val v1 = LogJson.encodeLine(full)
        for (key in listOf("variant", "rating", "answer", "mode")) assertFalse(key, v1.contains("\"$key\":"))
    }

    @Test
    fun decodeLinesSkipsBlankAndDamagedLines() {
        val a = LogEvent("2026-10-19T05:47:52+01:00", EventType.DIRECTIVE_FIRED, directiveId = "fajr")
        val b = LogEvent("2026-10-19T05:48:04+01:00", EventType.STEP_COMPLETED, directiveId = "fajr", stepIndex = 0)
        val c = LogEvent("2026-10-19T05:58:12+01:00", EventType.DIRECTIVE_COMPLETED, directiveId = "fajr")
        val text = listOf(
            "",
            LogJson.encodeLine(a),
            "   ",
            "not json at all",
            """{"type":"quiet_on"}""", // no ts
            """{"ts":"2026-10-19T06:00:00+01:00","type":"quiet_on","tier":"three"}""", // wrong type
            "[1, 2, 3]",
            LogJson.encodeLine(b) + "  ", // trailing spaces are harmless
            "\t",
            LogJson.encodeLine(c),
            LogJson.encodeLine(c).dropLast(17), // half-written last line
        ).joinToString("\r\n")
        assertEquals(listOf(a, b, c), LogJson.decodeLines(text))
        assertEquals(emptyList<LogEvent>(), LogJson.decodeLines(""))
        assertEquals(emptyList<LogEvent>(), LogJson.decodeLines("\n\n"))
    }

    @Test
    fun decodeLinesKeepsEventsAppendedToADamagedLine() {
        val a = LogEvent("2026-10-21T10:04:58+01:00", EventType.QUIET_ON)
        val b = LogEvent("2026-10-21T11:24:51+01:00", EventType.MISSED, directiveId = "water-am", detail = "{\"ts\" in text")
        val c = LogEvent("2026-10-21T11:30:12+01:00", EventType.QUIET_OFF)
        // A crash cut a write short and the next event was appended to the same line.
        val cut = LogJson.encodeLine(a).take(30) + LogJson.encodeLine(b)
        // A power cut left zero bytes before the next write.
        val zeros = "\u0000\u0000\u0000" + LogJson.encodeLine(c)
        // A writer that forgot the newline.
        val glued = LogJson.encodeLine(a) + LogJson.encodeLine(c)
        assertEquals(listOf(b, c, a, c), LogJson.decodeLines(listOf(cut, zeros, glued).joinToString("\n")))
    }

    @Test
    fun unknownFieldsInOldAndNewLinesAreIgnored() {
        val old = """{"ts":"2026-01-05T05:40:01+00:00","type":"directive_fired","directive_id":"fajr"}"""
        val new = """{"ts":"2027-03-01T05:40:01+00:00","type":"directive_fired","directive_id":"fajr","mood":"tired",""" +
            """"geo":{"lat":51.5,"tags":["a",null]},"retries":[1,2],"tier":3}"""
        val unknownType = """{"ts":"2027-03-01T05:41:00+00:00","type":"some_future_event"}"""
        assertEquals(
            listOf(
                LogEvent("2026-01-05T05:40:01+00:00", EventType.DIRECTIVE_FIRED, directiveId = "fajr"),
                LogEvent("2027-03-01T05:40:01+00:00", EventType.DIRECTIVE_FIRED, directiveId = "fajr", tier = 3),
                LogEvent("2027-03-01T05:41:00+00:00", "some_future_event"),
            ),
            LogJson.decodeLines(listOf(old, new, unknownType).joinToString("\n")),
        )
    }

    // ---------------------------------------------------------------- event types

    @Test
    fun everyEventTypeConstantIsDescribed() {
        val constants = EventType::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
        assertTrue("reflection found the constants", EventType.DIRECTIVE_FIRED in constants && EventType.BAD_DAY_OFF in constants)
        assertEquals("no duplicate values", constants.size, constants.toSet().size)
        assertEquals(constants.toSet(), EventType.DESCRIPTIONS.keys)
        for ((type, description) in EventType.DESCRIPTIONS) {
            assertTrue(type, type.matches(Regex("[a-z_]+")))
            assertTrue(type, description.isNotBlank() && description.endsWith("."))
        }
    }

    // ---------------------------------------------------------------- export

    @Test
    fun buildHasEveryTopLevelFieldInOrder() {
        val events = listOf(
            LogEvent("2026-10-19T05:47:52+01:00", EventType.DIRECTIVE_FIRED, directiveId = "fajr", tier = 3, trigger = "adhan"),
            LogEvent("2026-10-19T05:58:12+01:00", EventType.DIRECTIVE_COMPLETED, directiveId = "fajr", durationS = 620),
        )
        val out = LogExport.build(events, meta())
        assertTrue(out.startsWith("{\n    \"_readme\": "))
        assertTrue(out.endsWith("}\n"))
        val root = Json.parseToJsonElement(out).jsonObject
        assertEquals(
            listOf(
                "_readme", "schema_version", "exported_at", "app_version", "timezone", "plan", "since", "settings",
                "event_types", "summary", "events",
            ),
            root.keys.toList(),
        )
        assertEquals(LogExport.README, root.string("_readme"))
        assertEquals(LogExport.SCHEMA_VERSION, root["schema_version"]!!.jsonPrimitive.int)
        assertEquals("2026-10-23T21:06:15+01:00", root.string("exported_at"))
        assertEquals("1.0", root.string("app_version"))
        assertEquals("Europe/London", root.string("timezone"))
        val plan = root["plan"]!!.jsonObject
        assertEquals(listOf("week_of", "expires", "generated_by"), plan.keys.toList())
        assertEquals("2026-10-19", plan.string("week_of"))
        assertEquals("2026-10-26T04:00", plan.string("expires"))
        assertEquals("Claude", plan.string("generated_by"))
        assertEquals("2026-10-18T21:14:09+01:00", root.string("since"))
        assertEquals("true", root["settings"]!!.jsonObject.string(LogExport.SETTING_ALLOW_QUIET_OVERRIDE))
        assertEquals(
            EventType.DESCRIPTIONS,
            root["event_types"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(events, root["events"]!!.jsonArray.map { LogJson.json.decodeFromJsonElement(LogEvent.serializer(), it) })
    }

    @Test
    fun buildWithoutAPlanWritesNulls() {
        val meta = ExportMeta("2026-10-23T21:06:15+01:00", "1.0", "Europe/London", null, null, null, null, emptyMap())
        val root = Json.parseToJsonElement(LogExport.build(emptyList(), meta)).jsonObject
        val plan = root["plan"]!!.jsonObject
        assertEquals(setOf("week_of", "expires", "generated_by"), plan.keys)
        assertTrue(plan.values.all { it is kotlinx.serialization.json.JsonNull })
        assertTrue(root["since"] is kotlinx.serialization.json.JsonNull)
        assertEquals(JsonObject(emptyMap()), root["settings"])
        assertEquals(JsonObject(emptyMap()), root["summary"])
        assertTrue(root["events"]!!.jsonArray.isEmpty())
    }

    @Test
    fun buildKeepsEventsInTheGivenOrderAndOmitsNullFields() {
        val events = listOf(
            LogEvent("2026-10-20T10:12:44+01:00", EventType.QUIET_OFF),
            LogEvent("2026-10-20T08:47:30+01:00", EventType.QUIET_ON),
            LogEvent("2026-10-20T09:00:02+01:00", EventType.QUEUED_QUIET, directiveId = "deep-work", tier = 2, trigger = "time"),
        )
        val array = Json.parseToJsonElement(LogExport.build(events, meta())).jsonObject["events"]!!.jsonArray
        assertEquals(events.map { it.ts }, array.map { it.jsonObject.string("ts") })
        assertEquals(listOf("ts", "type"), array[0].jsonObject.keys.toList())
        assertEquals(listOf("ts", "type", "directive_id", "tier", "trigger"), array[2].jsonObject.keys.toList())
    }

    @Test
    fun summaryCountsOutcomesPerDirective() {
        fun e(type: String, id: String?, duration: Long? = null) = LogEvent("2026-10-19T12:00:01+01:00", type, directiveId = id, durationS = duration)
        val events = listOf(
            e(EventType.QUIET_ON, null),
            e(EventType.DIRECTIVE_FIRED, "water-am"),
            e(EventType.STEP_COMPLETED, "water-am", 12),
            e(EventType.DIRECTIVE_COMPLETED, "water-am", 10),
            e(EventType.DIRECTIVE_FIRED, "water-am"),
            e(EventType.DIRECTIVE_COMPLETED, "water-am", 15),
            e(EventType.DIRECTIVE_FIRED, "deep-work"),
            e(EventType.DIRECTIVE_DISMISSED, "deep-work", 40),
            e(EventType.QUEUED_QUIET, "deep-work"),
            e(EventType.SKIPPED_BAD_DAY, "deep-work"),
            e(EventType.MISSED, "sleep"),
            e(EventType.PLAN_IMPORTED, null),
        )
        val summary = LogExport.summary(events)
        assertEquals(listOf("deep-work", "sleep", "water-am"), summary.keys.toList())

        val water = summary["water-am"]!!.jsonObject
        assertEquals(
            listOf(
                "directive_fired", "directive_completed", "directive_dismissed", "directive_timed_out", "bypassed_bad_day",
                "dropped", "skipped_bad_day", "queued_quiet", "skipped_quiet", "missed", "repeated",
                "rated_easy", "rated_ok", "rated_hard", "rating_skipped", "skipped_minimum", "bypassed_minimum",
                "avg_completion_s",
            ),
            water.keys.toList(),
        )
        assertEquals(2, water.int("directive_fired"))
        assertEquals(2, water.int("directive_completed"))
        assertEquals(0, water.int("directive_dismissed"))
        assertEquals("12.5 rounds to 13, step durations are not counted", 13L, water["avg_completion_s"]!!.jsonPrimitive.long)

        val deep = summary["deep-work"]!!.jsonObject
        assertEquals(1, deep.int("directive_fired"))
        assertEquals(1, deep.int("directive_dismissed"))
        assertEquals(1, deep.int("queued_quiet"))
        assertEquals(1, deep.int("skipped_bad_day"))
        assertNull("no completions, no average", deep["avg_completion_s"])

        assertEquals(1, summary["sleep"]!!.jsonObject.int("missed"))
        assertEquals(0, summary["sleep"]!!.jsonObject.int("directive_fired"))
    }

    @Test
    fun testFiresAreLeftOutOfTheSummary() {
        fun e(type: String, trigger: String?, takeover: String?) =
            LogEvent("2026-10-19T12:00:01+01:00", type, directiveId = "isha", trigger = trigger, takeoverId = takeover)
        val events = listOf(
            e(EventType.DIRECTIVE_FIRED, "adhan_test", "t1"),
            e(EventType.DIRECTIVE_DISMISSED, "adhan_test", "t1"),
            e(EventType.DIRECTIVE_FIRED, "debug", "t2"),
            e(EventType.STEP_COMPLETED, null, "t2"),
            e(EventType.DIRECTIVE_COMPLETED, "debug", "t2"),
            e(EventType.DIRECTIVE_FIRED, "adhan", "t3"),
            e(EventType.DIRECTIVE_COMPLETED, "adhan", "t3"),
        )
        val isha = LogExport.summary(events)["isha"]!!.jsonObject
        assertEquals(1, isha.int("directive_fired"))
        assertEquals(1, isha.int("directive_completed"))
        assertEquals(0, isha.int("directive_dismissed"))
    }

    @Test
    fun summaryLeavesOutBuiltinsAndIgnoresTypesItDoesNotCount() {
        fun e(type: String, id: String?, trigger: String? = null, takeover: String? = null) =
            LogEvent("2026-10-22T07:00:01+01:00", type, directiveId = id, trigger = trigger, takeoverId = takeover)
        val events = listOf(
            e(EventType.DAY_MODE, null),
            e(EventType.DIRECTIVE_FIRED, Engine.CHECK_IN_ID, Engine.TRIGGER_CHECK_IN, "c1"),
            e(EventType.CHECK_IN, null, Engine.TRIGGER_CHECK_IN, "c1"),
            e(EventType.DIRECTIVE_FIRED, "sleep", "time", "t1"),
            e(EventType.DIRECTIVE_COMPLETED, "sleep", "time", "t1"),
            e(EventType.RATED, "sleep", "time", "t1"),
            e(EventType.STEPPED_DOWN, "sleep"),
            e(EventType.SKIPPED_MINIMUM, "study-block", "time"),
            e(EventType.RATING_SKIPPED, "water-am", "time", "t2"),
        )
        val summary = LogExport.summary(events)
        assertEquals(listOf("sleep", "study-block", "water-am"), summary.keys.toList())
        assertEquals(1, summary["sleep"]!!.jsonObject.int("directive_fired"))
        assertEquals(1, summary["sleep"]!!.jsonObject.int("directive_completed"))
        assertEquals(0, summary["water-am"]!!.jsonObject.int("directive_fired"))
        assertEquals(1, summary["study-block"]!!.jsonObject.int("skipped_minimum"))
        assertEquals(1, summary["water-am"]!!.jsonObject.int("rating_skipped"))
    }

    @Test
    fun summaryCountsRatingsAndMinimumDays() {
        fun e(type: String, id: String, takeover: String? = null, rating: String? = null, variant: String? = null) = LogEvent(
            "2026-10-22T07:00:01+01:00", type, directiveId = id, trigger = "time", takeoverId = takeover, rating = rating, variant = variant,
        )
        val events = listOf(
            e(EventType.RATED, "morning", "t1", Rating.HARD.json),
            e(EventType.RATED, "morning", "t2", Rating.HARD.json),
            e(EventType.STEPPED_DOWN, "morning", variant = "easier"),
            e(EventType.RATED, "morning", "t3", Rating.OK.json, "easier"),
            e(EventType.RATED, "morning", "t4", Rating.EASY.json, "easier"),
            e(EventType.RATING_SKIPPED, "morning", "t5"),
            e(EventType.RATING_SKIPPED, "morning", "t6", variant = "easier"),
            e(EventType.SKIPPED_MINIMUM, "morning"),
            e(EventType.BYPASSED_MINIMUM, "morning", "t7"),
            e(EventType.BYPASSED_MINIMUM, "morning", "t8"),
            // A rating without a known answer is not counted under any of the three.
            e(EventType.RATED, "morning", "t9", "terrible"),
        )
        val morning = LogExport.summary(events)["morning"]!!.jsonObject
        assertEquals(1, morning.int("rated_easy"))
        assertEquals(1, morning.int("rated_ok"))
        assertEquals("both versions count: variant is in ratings, not here", 2, morning.int("rated_hard"))
        assertEquals(2, morning.int("rating_skipped"))
        assertEquals(1, morning.int("skipped_minimum"))
        assertEquals(2, morning.int("bypassed_minimum"))
        assertEquals(0, morning.int("directive_fired"))
    }

    @Test
    fun summaryColumnsAreTheSameForEveryDirective() {
        val events = listOf(
            LogEvent("2026-10-19T05:47:52+01:00", EventType.DIRECTIVE_FIRED, directiveId = "fajr", trigger = "adhan", takeoverId = "a"),
            LogEvent("2026-10-21T11:24:51+01:00", EventType.MISSED, directiveId = "water-am", trigger = "time"),
        )
        val summary = LogExport.summary(events)
        val columns = summary.values.map { it.jsonObject.keys.toList() }
        assertEquals(columns.first(), columns.last())
        assertTrue("every column is a whole number", summary.values.all { o -> o.jsonObject.values.all { it.jsonPrimitive.content.toLongOrNull() != null } })
    }

    @Test
    fun readmeIsOneCleanLineThatExplainsTheBehaviour() {
        val readme = LogExport.README
        assertFalse(readme.contains('\n'))
        assertFalse("double space", readme.contains("  "))
        assertFalse("unexpanded template", readme.contains("\${"))
        assertEquals(readme.trim(), readme)
        val mentions = listOf(
            "plan.json", "min_seconds", "bad_day.keep", "fallback_plan", "takeover_id", "duration_s", "scheduled_for",
            "${Engine.MAX_TAKEOVER_MINUTES} minutes", "${TimeSchedule.DEFAULT_GRACE_MINUTES} minutes",
            Engine.TRIGGER_QUIET_QUEUE, Engine.DEMO_ID, "settings.${LogExport.SETTING_ALLOW_QUIET_OVERRIDE}",
            EventType.DIRECTIVE_COMPLETED, EventType.DIRECTIVE_DISMISSED, EventType.DIRECTIVE_TIMED_OUT, EventType.MISSED,
            EventType.QUEUED_QUIET, EventType.SKIPPED_QUIET, EventType.SKIPPED_BAD_DAY, EventType.BYPASSED_BAD_DAY,
            EventType.PLAN_EXPIRED, "NOT failures", EventType.BAD_DAY_OFF, EventType.DAY_MODE, EventType.CHECK_IN,
            EventType.SKIPPED_MINIMUM, EventType.BYPASSED_MINIMUM, EventType.RATED, EventType.RATING_SKIPPED,
            EventType.STEPPED_DOWN, "after_bad_day", "minimum.keep", "variants.easier", Engine.CHECK_IN_ID,
            Engine.BUILTIN_ID_PREFIX,
        )
        for (m in mentions) assertTrue("readme should mention $m", readme.contains(m))
        for (p in QuietPolicy.entries) assertTrue(p.json, readme.contains("${p.json} ="))
    }

    private fun meta() = ExportMeta(
        exportedAt = "2026-10-23T21:06:15+01:00",
        appVersion = "1.0",
        timezone = "Europe/London",
        planWeekOf = "2026-10-19",
        planExpires = "2026-10-26T04:00",
        planGeneratedBy = "Claude",
        since = "2026-10-18T21:14:09+01:00",
        settings = mapOf(LogExport.SETTING_ALLOW_QUIET_OVERRIDE to "true"),
    )

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.int(key: String) = this[key]!!.jsonPrimitive.int
}
