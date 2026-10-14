package dev.vigil.core.log

import dev.vigil.core.engine.ActivePlanResolver
import dev.vigil.core.engine.BuiltinPlan
import dev.vigil.core.engine.Engine
import dev.vigil.core.engine.Env
import dev.vigil.core.engine.Outcome
import dev.vigil.core.engine.RuntimeState
import dev.vigil.core.engine.TimeSchedule
import dev.vigil.core.plan.BadDay
import dev.vigil.core.plan.Directive
import dev.vigil.core.plan.ParseResult
import dev.vigil.core.plan.Plan
import dev.vigil.core.plan.PlanParser
import dev.vigil.core.plan.Prayer
import dev.vigil.core.plan.QuietPolicy
import dev.vigil.core.plan.Step
import dev.vigil.core.plan.Trigger
import dev.vigil.core.plan.UsageLimit
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
import java.io.File
import java.lang.reflect.Modifier
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
                "dropped", "skipped_bad_day", "queued_quiet", "skipped_quiet", "missed", "repeated", "avg_completion_s",
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
            EventType.PLAN_EXPIRED, "NOT failures",
        )
        for (m in mentions) assertTrue("readme should mention $m", readme.contains(m))
        for (p in QuietPolicy.entries) assertTrue(p.json, readme.contains("${p.json} ="))
    }

    // ---------------------------------------------------------------- spec/sample-log.json

    @Test
    fun sampleLogCoversTheInterestingCases() {
        val root = Json.parseToJsonElement(SampleWeek.export()).jsonObject
        val events = root["events"]!!.jsonArray.map { LogJson.json.decodeFromJsonElement(LogEvent.serializer(), it) }
        fun has(type: String, predicate: (LogEvent) -> Boolean = { true }) =
            assertTrue("sample should contain $type", events.any { it.type == type && predicate(it) })

        has(EventType.PLAN_IMPORTED)
        has(EventType.DIRECTIVE_COMPLETED) { it.directiveId == "fajr" && it.trigger == "adhan" }
        has(EventType.STEP_COMPLETED) { it.directiveId == "fajr" && it.durationS != null && it.stepText != null }
        has(EventType.DIRECTIVE_FIRED) { it.trigger == "app_usage" }
        has(EventType.DIRECTIVE_DISMISSED) { it.tier == 2 && it.stepsDone!! < it.stepsTotal!! }
        has(EventType.QUEUED_QUIET)
        has(EventType.DIRECTIVE_FIRED) { it.trigger == Engine.TRIGGER_QUIET_QUEUE }
        has(EventType.BAD_DAY_ON)
        has(EventType.BYPASSED_BAD_DAY)
        has(EventType.SKIPPED_BAD_DAY)
        has(EventType.BAD_DAY_OFF) { it.detail == "day ended" }
        has(EventType.MISSED) { it.scheduledFor != null }
        has(EventType.DIRECTIVE_FIRED) { it.directiveId == "sleep" && it.ts.startsWith("2026-10-22") } // kept on the bad day

        assertEquals("events are in time order", events.sortedBy { java.time.OffsetDateTime.parse(it.ts) }, events)
        assertTrue(events.all { it.directiveId == null || it.directiveId in SampleWeek.plan.directives.map { d -> d.id } })
        assertTrue("every takeover closed", events.groupBy { it.takeoverId }.filterKeys { it != null }.values.all { it.size >= 2 })
        for ((id, counts) in root["summary"]!!.jsonObject) {
            val c = counts.jsonObject
            val closed = listOf("directive_completed", "directive_dismissed", "directive_timed_out", "bypassed_bad_day", "dropped").sumOf { c.int(it) }
            assertEquals("$id: every fired takeover closed one way", c.int("directive_fired"), closed)
        }
    }

    /**
     * spec/sample-log.json must be exactly what [LogExport.build] produces. To regenerate it after a deliberate change:
     * VIGIL_UPDATE_SAMPLES=1 ./gradlew -p core cleanTest test --tests dev.vigil.core.log.LogExportTest
     */
    @Test
    fun sampleLogMatchesTheExport() {
        val file = File(System.getProperty("vigil.repoRoot"), "spec/sample-log.json")
        val expected = SampleWeek.export()
        if (System.getenv("VIGIL_UPDATE_SAMPLES") == "1") {
            file.parentFile.mkdirs()
            file.writeText(expected)
        }
        assertTrue("${file.path} is missing; see the KDoc of this test to generate it", file.isFile)
        assertEquals("${file.path} is out of date; see the KDoc of this test to regenerate it", expected, file.readText())
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

/** The week of 2026-10-19, played through the real engine so the sample shows exactly what the app writes. */
private object SampleWeek {
    val zone: ZoneId = ZoneId.of("Europe/London")
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val everyDay = DayOfWeek.entries.toSet()

    /** The same week as spec/sample-plan.json, so the two samples tell one story. */
    val plan: Plan = run {
        val text = java.io.File(System.getProperty("vigil.repoRoot"), "spec/sample-plan.json").readText()
        (PlanParser.parse(text) as ParseResult.Success).plan
    }

    fun export(): String {
        val phone = Phone()
        phone.write(
            "2026-10-18T21:14:09", EventType.PLAN_IMPORTED,
            detail = "week_of ${plan.weekOf}, expires ${plan.expires}, generated_by ${plan.generatedBy}, ${plan.directives.size} directives, 0 warnings",
        )

        // Monday: Fajr, all four steps.
        phone.trigger("2026-10-19T05:47:52", "fajr", "adhan")
        phone.steps("2026-10-19T05:47:53", 11, 31, 95, 412)
        // Monday night: TikTok/Instagram opened six times in half an hour; one step ticked, then abandoned.
        phone.trigger("2026-10-19T21:37:26", "scroll-check", "app_usage")
        phone.steps("2026-10-19T21:37:27", 12)
        phone.run("2026-10-19T21:38:19") { s, e -> Engine.dismiss(s, e, phone.takeoverId) }

        // Tuesday: quiet mode in a lecture holds Dhuhr until it ends.
        phone.run("2026-10-20T12:40:30") { s, e -> Engine.setQuiet(s, e, true) }
        phone.trigger("2026-10-20T12:52:02", "dhuhr", "adhan")
        phone.run("2026-10-20T13:55:44") { s, e -> Engine.setQuiet(s, e, false) }
        phone.steps("2026-10-20T13:55:45", 7, 75, 301)

        // Wednesday: the battery died at 10:05; back on at 11:24, too late for water-am.
        phone.catchUp(from = "2026-10-21T10:05:00", now = "2026-10-21T11:24:51")

        // Thursday: a hard day. Only the prayers, sleep and water-am are kept.
        phone.trigger("2026-10-22T07:30:03", "morning", "time", scheduledFor = "2026-10-22T07:30")
        phone.shown("2026-10-22T07:30:04")
        phone.run("2026-10-22T07:33:41") { s, e -> Engine.setBadDay(s, e, true) }
        phone.trigger("2026-10-22T09:00:01", "study-block", "time", scheduledFor = "2026-10-22T09:00")
        phone.trigger("2026-10-22T10:30:02", "water-am", "time", scheduledFor = "2026-10-22T10:30")
        phone.steps("2026-10-22T10:30:03", 18)
        phone.trigger("2026-10-22T23:00:02", "sleep", "time", scheduledFor = "2026-10-22T23:00")
        phone.steps("2026-10-22T23:00:03", 14, 131, 66, 41, 9)

        // Friday: the day boundary passed overnight, so bad-day mode ends with the first event of the day.
        phone.trigger("2026-10-23T05:53:10", "fajr", "adhan")
        phone.steps("2026-10-23T05:53:11", 8, 22, 70, 352)

        // The phone stores events as JSON Lines; the export reads them back.
        val stored = phone.log.joinToString("\n") { LogJson.encodeLine(it) } + "\n"
        val meta = ExportMeta(
            exportedAt = phone.timestamp("2026-10-23T21:06:15"),
            appVersion = "1.0",
            timezone = zone.id,
            planWeekOf = plan.weekOf.toString(),
            planExpires = plan.expires.toString(),
            planGeneratedBy = plan.generatedBy,
            since = phone.log.first().ts,
            settings = mapOf(LogExport.SETTING_ALLOW_QUIET_OVERRIDE to "true"),
        )
        return LogExport.build(LogJson.decodeLines(stored), meta)
    }

    private class Phone {
        var state = RuntimeState()
        val log = mutableListOf<LogEvent>()
        private val ids = java.util.Random(20261019)

        val takeoverId: String get() = state.active!!.takeoverId

        fun timestamp(local: String): String =
            LocalDateTime.parse(local).atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        fun run(local: String, action: (RuntimeState, Env) -> Outcome) {
            val now = LocalDateTime.parse(local).atZone(zone).toInstant()
            val active = ActivePlanResolver.resolve(plan, BuiltinPlan.plan, LocalDateTime.ofInstant(now, zone))
            val outcome = action(state, Env(now, zone, active, allowQuietOverride = true) { "%08x".format(ids.nextInt()) })
            state = outcome.state
            log += outcome.events
        }

        fun trigger(local: String, id: String, trigger: String, scheduledFor: String? = null) =
            run(local) { s, e -> Engine.onTrigger(s, e, listOf(id), trigger, scheduledFor?.let(::timestamp)) }

        fun shown(local: String) = run(local) { s, e -> Engine.stepShown(s, e, takeoverId) }

        /** From the moment the next step appears, ticks it after each number of seconds (the next step appears at once). */
        fun steps(firstShown: String, vararg seconds: Long) {
            var at = LocalDateTime.parse(firstShown)
            for (s in seconds) {
                shown(at.toString())
                at = at.plusSeconds(s)
                val before = log.size
                run(at.toString()) { st, e -> Engine.stepDone(st, e, takeoverId, st.active!!.stepsDone) }
                check(log.size > before) { "step at $at was not accepted" }
            }
        }

        /** What the Android layer does after the phone was off: time triggers too far in the past are logged as missed. */
        fun catchUp(from: String, now: String) {
            val pass = TimeSchedule.pass(
                plan.directives,
                LocalDateTime.parse(from).atZone(zone).toInstant(),
                LocalDateTime.parse(now).atZone(zone).toInstant(),
                zone,
            )
            check(pass.due.isEmpty())
            for (fire in pass.missed) {
                for (id in fire.directiveIds) {
                    val d = plan.directive(id)!!
                    log += LogEvent(
                        timestamp(now), EventType.MISSED, directiveId = id, tier = d.tier, trigger = d.trigger.type,
                        scheduledFor = fire.at.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    )
                }
            }
        }

        fun write(local: String, type: String, detail: String) {
            log += LogEvent(timestamp(local), type, detail = detail)
        }
    }
}
