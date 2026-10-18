package dev.vigil.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalTime

class ProtocolParserTest {
    private val base: JsonObject = Json.parseToJsonElement(BundledProtocols.text).jsonObject

    // ------------------------------------------------------------------ helpers

    private fun parse(json: JsonElement): ProtocolParseResult = ProtocolParser.parse(json.toString())

    private fun errors(json: JsonElement): List<ProtocolIssue> = when (val r = parse(json)) {
        is ProtocolParseResult.Failure -> r.errors
        is ProtocolParseResult.Success -> emptyList()
    }

    private fun warnings(json: JsonElement): List<ProtocolIssue> = parse(json).warnings

    private fun assertError(json: JsonElement, pathPart: String, messagePart: String = "") {
        val errs = errors(json)
        if (errs.none { it.path.contains(pathPart) && it.message.contains(messagePart) }) {
            fail("Expected an error at '$pathPart' containing '$messagePart', got: $errs")
        }
    }

    private fun assertOk(json: JsonElement): ProtocolFile = when (val r = parse(json)) {
        is ProtocolParseResult.Failure -> fail("Expected success, got ${r.errors}").let { throw AssertionError() }
        is ProtocolParseResult.Success -> r.file
    }

    private fun with(key: String, value: JsonElement?, obj: JsonObject = base): JsonObject =
        JsonObject(if (value == null) obj - key else obj + (key to value))

    private fun pool(key: String, value: JsonElement?): JsonObject = with("pool", with(key, value, base["pool"]!!.jsonObject))

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to el(v) })

    private fun el(v: Any?): JsonElement = when (v) {
        null -> kotlinx.serialization.json.JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { el(it) })
        else -> error("unsupported $v")
    }

    private fun step(text: String = "Do it.", type: String = "check", min: Int? = null, lines: Int? = null) =
        obj("text" to text, "type" to type, "min" to min, "lines" to lines).let { o -> JsonObject(o.filterValues { it !is kotlinx.serialization.json.JsonNull }) }

    private fun protocol(
        id: String = "test",
        kind: String = "reset",
        trigger: String = "manual",
        lock: JsonObject = obj("type" to "screen", "minutes" to 20),
        steps: List<JsonObject> = listOf(step(), step("Done.", "done")),
        extra: Map<String, JsonElement> = emptyMap(),
    ) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id), "kind" to JsonPrimitive(kind), "title" to JsonPrimitive(id.uppercase()),
            "trigger" to JsonPrimitive(trigger), "lock" to lock, "steps" to JsonArray(steps),
        ) + extra,
    )

    /** The bundled file with [extra] protocols added. */
    private fun withProtocols(vararg extra: JsonObject): JsonObject =
        with("protocols", JsonArray(base["protocols"]!! as JsonArray + extra.toList()))

    // ------------------------------------------------------------------ whole file

    @Test
    fun bundledParses() {
        assertOk(base)
    }

    @Test
    fun notJson() {
        val r = ProtocolParser.parse("{ nope")
        assertTrue(r is ProtocolParseResult.Failure)
        assertTrue((r as ProtocolParseResult.Failure).errors.single().message.startsWith("This is not valid JSON"))
    }

    @Test
    fun emptyAndNotAnObject() {
        assertEquals("The file is empty.", (ProtocolParser.parse("  ") as ProtocolParseResult.Failure).errors.single().message)
        assertTrue((ProtocolParser.parse("[1]") as ProtocolParseResult.Failure).errors.single().message.contains("one JSON object"))
    }

    @Test
    fun byteOrderMarkIsIgnored() {
        assertTrue(ProtocolParser.parse("﻿" + BundledProtocols.text) is ProtocolParseResult.Success)
    }

    @Test
    fun requiredTopLevelFields() {
        assertError(with("version", null), "version", "missing")
        assertError(with("pool", null), "pool", "missing")
        assertError(with("protocols", null), "protocols", "missing")
    }

    @Test
    fun versionMustBeTwo() {
        assertError(with("version", JsonPrimitive(1)), "version", "must be 2")
        assertError(with("version", JsonPrimitive("2")), "version", "whole number")
    }

    @Test
    fun unknownFieldsAreWarningsAndUnderscoreKeysAreComments() {
        val json = with("colour", JsonPrimitive("red")) + ("_note" to JsonPrimitive("hi"))
        val ws = warnings(JsonObject(json))
        assertTrue(ws.any { it.path == "colour" && it.message.contains("unknown field") })
        assertTrue(ws.none { it.path.contains("_note") })
        assertOk(JsonObject(json))
    }

    @Test
    fun wrongTypesArePlainErrors() {
        assertError(pool("wait_step_min", JsonPrimitive("1")), "pool.wait_step_min", "whole number")
        assertError(pool("all_browsers", JsonPrimitive("yes")), "pool.all_browsers", "true or false")
        assertError(pool("apps", JsonPrimitive("com.x.y")), "pool.apps", "list")
        assertError(pool("cooldown_factor", JsonPrimitive("2")), "pool.cooldown_factor", "number")
        assertError(pool("wait_step_min", JsonPrimitive(1.5)), "pool.wait_step_min", "whole number")
    }

    // ------------------------------------------------------------------ pool

    @Test
    fun poolNumbersInRange() {
        assertError(pool("wait_step_min", JsonPrimitive(0)), "pool.wait_step_min", "from 1 to 60")
        assertError(pool("wait_cap_min", JsonPrimitive(121)), "pool.wait_cap_min", "from 1 to 120")
        assertError(
            with("pool", with("wait_cap_min", JsonPrimitive(2), with("wait_step_min", JsonPrimitive(5), base["pool"]!!.jsonObject))),
            "pool.wait_cap_min", "at least wait_step_min",
        )
        assertError(pool("cooldown_factor", JsonPrimitive(11)), "pool.cooldown_factor", "from 0 to 10")
        assertError(pool("cooldown_cap_min", JsonPrimitive(-1)), "pool.cooldown_cap_min")
        assertError(pool("session_gap_s", JsonPrimitive(601)), "pool.session_gap_s")
        assertError(pool("heavy_use", obj("continuous_min" to 0)), "pool.heavy_use.continuous_min")
        assertError(pool("heavy_use", obj("opens" to 1)), "pool.heavy_use.opens", "from 2 to 20")
        assertError(pool("heavy_use", obj("within_min" to 999)), "pool.heavy_use.within_min")
        assertError(pool("bail_out", obj("extra_lock_min" to 721)), "pool.bail_out.extra_lock_min")
        assertError(pool("bail_out", obj("daily_cap_min" to 2000)), "pool.bail_out.daily_cap_min")
    }

    @Test
    fun poolPackagesLookLikePackages() {
        assertError(pool("apps", el(listOf("tiktok"))), "pool.apps[0]", "package name")
        assertError(pool("apps", el(listOf("1com.x"))), "pool.apps[0]", "package name")
        val ws = warnings(pool("apps", el(listOf("com.a.b", "com.a.b"))))
        assertTrue(ws.any { it.path == "pool.apps[1]" && it.message.contains("twice") })
        assertTrue(ProtocolParser.looksLikePackage("com.ss.android.ugc.trill"))
        assertTrue(!ProtocolParser.looksLikePackage("com"))
        assertTrue(!ProtocolParser.looksLikePackage("com..x"))
        assertTrue(!ProtocolParser.looksLikePackage("a." + "b.".repeat(200) + "c"))
    }

    @Test
    fun windows() {
        fun win(opens: String, closes: String, id: String = "w", cap: Int = 30) = el(listOf(obj("id" to id, "opens" to opens, "closes" to closes, "cap_min" to cap)))
        assertOk(pool("windows", win("12:00", "13:00")))
        assertOk(pool("windows", win("class_end+15", "next")))
        assertError(pool("windows", win("next", "13:00")), "opens", "only close")
        assertError(pool("windows", win("noon", "13:00")), "opens", "not a time")
        assertError(pool("windows", win("done:morning", "13:00")), "opens", "fallback time after |")
        assertError(pool("windows", win("done:Morning|wake", "13:00")), "opens", "not a protocol id")
        assertError(pool("windows", win("12:00", "meal:supper")), "closes", "not a meal")
        assertError(pool("windows", win("12:00", "13:00", cap = 0)), "cap_min", "from 1 to 600")
        assertError(pool("windows", win("12:00", "13:00", id = "Bad Id")), ".id", "not a valid id")
        val two = el(listOf(obj("id" to "a", "opens" to "12:00", "closes" to "13:00", "cap_min" to 5), obj("id" to "a", "opens" to "14:00", "closes" to "15:00", "cap_min" to 5)))
        assertError(pool("windows", two), "pool.windows[1]", "more than one window")
        assertError(pool("windows", el(listOf(obj("id" to "a", "opens" to "12:00")))), "closes", "missing")
        assertTrue(warnings(pool("windows", el(emptyList<Any>()))).any { it.message.contains("never be opened") })
        // A reference to a protocol not (yet) in the file is fine but noted.
        assertTrue(warnings(base).any { it.path.contains("morning") && it.message.contains("no protocol \"morning\"") })
    }

    // ------------------------------------------------------------------ globals

    @Test
    fun appsButtonEntries() {
        assertError(with("apps_button", el(listOf(obj("role" to "dialer", "package" to "com.x.y")))), "apps_button[0]", "exactly one")
        assertError(with("apps_button", el(listOf(obj()))), "apps_button[0]", "exactly one")
        assertError(with("apps_button", el(listOf(obj("role" to "camera")))), "apps_button[0].role", "one of dialer, adhan, clock")
        assertError(with("apps_button", el(listOf(obj("package" to "maps")))), "apps_button[0].package", "package name")
        assertError(with("apps_button", el(listOf(obj("label_contains" to " ")))), "label_contains", "blank")
        assertTrue(warnings(with("apps_button", el(emptyList<Any>()))).any { it.path == "apps_button" })
    }

    @Test
    fun dayTakeoverStages() {
        assertError(with("day", obj("idle_end_min" to 5)), "day.idle_end_min")
        assertError(with("day", obj("fallback_end" to "6:00")), "day.fallback_end", "HH:MM")
        assertError(with("day", obj("night_edge" to "25:00")), "day.night_edge", "HH:MM")
        assertTrue(warnings(with("day", obj("fallback_end" to "13:00"))).any { it.path == "day.fallback_end" })
        assertError(with("takeover", obj("full_seconds" to 601)), "takeover.full_seconds")
        assertError(with("takeover", obj("glyph_off_after_s" to -1)), "takeover.glyph_off_after_s")
        assertError(with("stages", obj("promote_after_clean" to 0)), "stages.promote_after_clean")
        assertError(with("stages", obj("window" to 5, "demote_after_misses" to 6)), "stages.demote_after_misses", "from 1 to 5")
    }

    // ------------------------------------------------------------------ protocols

    @Test
    fun everyKindIsAccepted() {
        val protos = ProtocolKind.entries.mapIndexed { i, k ->
            val trigger = if (k == ProtocolKind.PRAYER) "event:adhan:isha" else "manual"
            protocol(id = "k$i", kind = k.json, trigger = trigger)
        }
        // The bundled Isha is replaced so the prayer kind does not clash with it.
        val json = with("protocols", JsonArray(protos))
        val file = assertOk(json)
        assertEquals(ProtocolKind.entries.toList(), file.protocols.map { it.kind })
    }

    @Test
    fun unknownKindAndStepType() {
        assertError(withProtocols(protocol(kind = "nap")), "protocols[5] (test).kind", "must be one of")
        assertError(withProtocols(protocol(steps = listOf(step(type = "tap")))), "steps[0].type", "must be one of")
        assertError(withProtocols(protocol(lock = obj("type" to "phone"))), "lock.type", "must be one of")
        assertError(withProtocols(protocol(extra = mapOf("intensity" to JsonPrimitive("loud")))), "intensity", "must be one of")
    }

    @Test
    fun idsAreValidAndUnique() {
        assertError(withProtocols(protocol(id = "Bad")), ".id", "not a valid id")
        assertError(withProtocols(protocol(id = "prayer-fajr")), ".id", "more than one protocol")
        assertError(withProtocols(protocol(id = "-x")), ".id", "not a valid id")
    }

    @Test
    fun requiredProtocolFields() {
        val p = JsonObject(protocol() - "steps")
        assertError(withProtocols(p), "steps", "missing")
        assertError(withProtocols(JsonObject(protocol() - "trigger")), "trigger", "missing")
        assertError(withProtocols(protocol(steps = emptyList())), "steps", "at least one step")
        assertError(withProtocols(protocol(extra = mapOf("title" to JsonPrimitive(" ")))), "title", "blank")
        assertTrue(warnings(withProtocols(protocol(extra = mapOf("title" to JsonPrimitive("X".repeat(31)))))).any { it.path.endsWith("title") })
    }

    @Test
    fun everyTriggerForm() {
        val ok = listOf(
            "manual", "event:wake_alarm", "event:after:prayer-fajr", "event:misses:2", "slot:study", "slot:meal", "slot:gym",
            "slot:call", "slot:commute", "slot:jummah", "time:21:30", "time:wake+60", "time:bedtime-60", "time:meal:dinner+45",
            "time:class_start-75", "time:class_end", "toggle:walking", "toggle:friends",
        )
        ok.forEachIndexed { i, t -> assertOk(withProtocols(protocol(id = "t$i", trigger = t))) }
        assertError(withProtocols(protocol(trigger = "event:adhan:duhr")), "trigger", "not a prayer")
        assertError(withProtocols(protocol(trigger = "event:adhan:fajr")), "trigger", "prayer protocols only")
        assertError(withProtocols(protocol(trigger = "event:after:nothing")), "trigger", "no protocol \"nothing\"")
        assertError(withProtocols(protocol(trigger = "event:after:test")), "trigger", "after itself")
        assertError(withProtocols(protocol(trigger = "event:misses:0")), "trigger", "from 1 to 10")
        assertError(withProtocols(protocol(trigger = "event:misses:x")), "trigger", "from 1 to 10")
        assertError(withProtocols(protocol(trigger = "slot:nap")), "trigger", "not a slot")
        assertError(withProtocols(protocol(trigger = "time:7am")), "trigger", "not a time")
        assertError(withProtocols(protocol(trigger = "time:wake+01")), "trigger", "leading zeros")
        assertError(withProtocols(protocol(trigger = "time:wake+2000")), "trigger", "more than 1440")
        assertError(withProtocols(protocol(trigger = "toggle:quiet")), "trigger", "cannot start a protocol")
        assertError(withProtocols(protocol(trigger = "event:sunrise")), "trigger", "not a known event")
        assertError(withProtocols(protocol(trigger = "cron:5")), "trigger", "not a trigger")
        assertError(withProtocols(protocol(trigger = " manual")), "trigger", "no spaces")
    }

    @Test
    fun prayerRules() {
        val prayer = protocol(id = "p2", kind = "prayer", trigger = "manual", lock = obj("type" to "screen", "minutes" to 20, "done_after_min" to 8))
        assertError(withProtocols(prayer), "trigger", "starts at its adhan")
        val twice = protocol(id = "p2", kind = "prayer", trigger = "event:adhan:fajr", lock = obj("type" to "screen", "minutes" to 20, "done_after_min" to 8))
        assertError(withProtocols(twice), "trigger", "one protocol per prayer")
        val noMinutes = JsonObject(base) .let {
            val list = (it["protocols"] as JsonArray).toMutableList()
            list[0] = JsonObject(list[0].jsonObject + ("lock" to obj("type" to "screen")))
            JsonObject(it + ("protocols" to JsonArray(list)))
        }
        assertError(noMinutes, "lock.minutes", "needs minutes")
        assertTrue(warnings(noMinutes).any { it.path.endsWith("done_after_min") })
    }

    @Test
    fun lockRules() {
        assertError(withProtocols(protocol(lock = obj("type" to "screen", "minutes" to 0))), "lock.minutes", "from 1 to 600")
        assertError(withProtocols(protocol(lock = obj("type" to "screen", "minutes" to 10, "done_after_min" to 11))), "done_after_min", "not be more than")
        assertTrue(warnings(withProtocols(protocol(lock = obj("type" to "none", "minutes" to 10)))).any { it.path.endsWith("lock.minutes") })
    }

    @Test
    fun stepRules() {
        assertError(withProtocols(protocol(steps = listOf(step(" ")))), "steps[0].text", "blank")
        assertError(withProtocols(protocol(steps = listOf(step(min = 0)))), "steps[0].min", "from 1 to 240")
        assertError(withProtocols(protocol(steps = listOf(step(type = "timer", min = 241)))), "steps[0].min")
        assertError(withProtocols(protocol(steps = listOf(step(type = "text", lines = 11)))), "steps[0].lines", "from 1 to 10")
        assertError(withProtocols(protocol(steps = listOf(step("Done.", "done"), step("Done again.", "done")))), "steps", "more than one done")
        assertError(
            withProtocols(protocol(lock = obj("type" to "pool"), steps = listOf(step(type = "timer")))),
            "steps[0].min", "lock has no minutes",
        )
        assertOk(withProtocols(protocol(lock = obj("type" to "screen", "minutes" to 5), steps = listOf(step(type = "timer")))))
        val ws = warnings(withProtocols(protocol(steps = listOf(step(type = "text", min = 3), step(type = "check", lines = 2)))))
        assertTrue(ws.any { it.path.endsWith("steps[0].min") })
        assertTrue(ws.any { it.path.endsWith("steps[1].lines") })
        assertTrue(warnings(withProtocols(protocol(steps = listOf(step("x".repeat(161)))))).any { it.path.endsWith("steps[0].text") })
    }

    @Test
    fun ladderOnlyWhereTheKindAllowsIt() {
        val ladder = mapOf("short" to JsonArray(listOf(step())), "minimal" to JsonArray(listOf(step())), "single" to JsonArray(listOf(step())))
        assertOk(withProtocols(protocol(kind = "morning", extra = ladder)))
        for (kind in listOf("jummah", "walk", "friends", "promise")) {
            assertError(withProtocols(protocol(kind = kind, extra = mapOf("short" to JsonArray(listOf(step()))))), "short", "no step-down ladder")
        }
        assertOk(withProtocols(protocol(kind = "call", extra = mapOf("short" to JsonArray(listOf(step()))))))
        assertError(withProtocols(protocol(kind = "call", extra = mapOf("minimal" to JsonArray(listOf(step()))))), "minimal", "may only define short")
        assertError(withProtocols(protocol(kind = "reset", extra = mapOf("single" to JsonArray(listOf(step(), step("b")))))), "single", "exactly one step")
        assertError(withProtocols(protocol(kind = "reset", extra = mapOf("short" to JsonArray(emptyList())))), "short", "at least one step")
        val prayerShort = base.let {
            val list = (it["protocols"] as JsonArray).toMutableList()
            list[1] = JsonObject(list[1].jsonObject + ("short" to JsonArray(listOf(step()))))
            JsonObject(it + ("protocols" to JsonArray(list)))
        }
        assertError(prayerShort, "protocols[1] (prayer-dhuhr).short", "prayer protocols have no step-down ladder")
    }

    @Test
    fun weekdays() {
        val thu = mapOf("weekdays" to obj("thu" to obj("steps" to JsonArray(listOf(step("Quick.")))), "fri" to obj("short" to JsonArray(listOf(step())))))
        val file = assertOk(withProtocols(protocol(kind = "morning", extra = thu)))
        val p = file.find("test")!!
        assertEquals("Quick.", p.stepsFor(Version.FULL, "thu")!!.single().text)
        assertEquals("Do it.", p.stepsFor(Version.FULL, "mon")!!.first().text)
        assertNull(p.stepsFor(Version.SHORT, "mon"))
        assertEquals(1, p.stepsFor(Version.SHORT, "fri")!!.size)
        assertError(withProtocols(protocol(extra = mapOf("weekdays" to obj("thursday" to obj())))), "weekdays.thursday", "not allowed")
        assertError(withProtocols(protocol(kind = "walk", extra = mapOf("weekdays" to obj("mon" to obj("short" to JsonArray(listOf(step()))))))), "weekdays.mon.short", "no step-down ladder")
        assertTrue(warnings(withProtocols(protocol(extra = mapOf("weekdays" to obj("mon" to obj()))))).any { it.path.endsWith("weekdays.mon") })
    }

    @Test
    fun params() {
        fun prayerWith(params: JsonObject) = base.let {
            val list = (it["protocols"] as JsonArray).toMutableList()
            list[2] = JsonObject(list[2].jsonObject + ("params" to params))
            JsonObject(it + ("protocols" to JsonArray(list)))
        }
        assertError(prayerWith(obj("heads_up_min" to 61)), "params.heads_up_min", "from 0 to 60")
        assertError(prayerWith(obj("window_warn_min" to "15")), "params.window_warn_min", "whole number")
        assertTrue(warnings(prayerWith(obj("snooze" to 3))).any { it.path.endsWith("params.snooze") && it.message.contains("known: heads_up_min, window_warn_min") })
        assertError(withProtocols(protocol(kind = "sleep", extra = mapOf("params" to obj("white_noise" to "yes")))), "white_noise", "true or false")
        assertError(withProtocols(protocol(kind = "meal", extra = mapOf("params" to obj("fallback" to listOf("Toast", 3))))), "fallback", "list of texts")
        assertError(withProtocols(protocol(kind = "meal", extra = mapOf("params" to obj("dishes" to obj("lunch" to 3))))), "dishes", "label: text")
        assertError(withProtocols(protocol(kind = "promise", extra = mapOf("params" to obj("delays" to listOf(5, 0))))), "delays[1]", "from 1 to 240")
        assertError(withProtocols(protocol(kind = "promise", extra = mapOf("params" to obj("delays" to emptyList<Int>())))), "delays", "list of whole numbers")
        val file = assertOk(withProtocols(protocol(kind = "study", extra = mapOf("params" to obj("work_min" to 40)))))
        assertEquals(40, file.find("test")!!.intParam("work_min"))
        assertEquals(10, file.find("test")!!.intParam("break_min"))
        assertError(withProtocols(protocol(extra = mapOf("params" to JsonPrimitive(3)))), "params", "object")
    }

    @Test
    fun noProtocolsIsAWarning() {
        assertTrue(warnings(with("protocols", JsonArray(emptyList()))).any { it.path == "protocols" })
    }

    // ------------------------------------------------------------------ expressions

    @Test
    fun timeExpressions() {
        assertEquals(TimeExpr(TimeBase.Wake, 60), (TimeExpr.parse("wake+60") as Parsed.Ok).value)
        assertEquals(TimeExpr(TimeBase.Bedtime, -60), (TimeExpr.parse("bedtime-60") as Parsed.Ok).value)
        assertEquals(TimeExpr(TimeBase.Meal("dinner"), 45), (TimeExpr.parse("meal:dinner+45") as Parsed.Ok).value)
        assertEquals(TimeExpr(TimeBase.ClassStart, 0), (TimeExpr.parse("class_start") as Parsed.Ok).value)
        assertEquals(TimeExpr(TimeBase.ClassEnd, 0), (TimeExpr.parse("class_end+0") as Parsed.Ok).value)
        assertEquals(TimeExpr(TimeBase.Clock(LocalTime.of(5, 30)), 0), (TimeExpr.parse("05:30") as Parsed.Ok).value)
        assertEquals("wake+60", TimeExpr(TimeBase.Wake, 60).toString())
        assertEquals("21:05-10", TimeExpr(TimeBase.Clock(LocalTime.of(21, 5)), -10).toString())
        for (bad in listOf("", "wake +60", "wake+", "24:00", "7:30", "meal:", "meal:brunch", "class", "wake*2")) {
            assertTrue(bad, TimeExpr.parse(bad) is Parsed.Error)
        }
    }

    @Test
    fun anchors() {
        assertEquals(Anchor.Next, (Anchor.parse("next", true) as Parsed.Ok).value)
        assertTrue(Anchor.parse("next", false) is Parsed.Error)
        assertEquals(Anchor.Done("morning", TimeExpr(TimeBase.Wake, 60)), (Anchor.parse("done:morning|wake+60", false) as Parsed.Ok).value)
        assertEquals(Anchor.Start("sleep", TimeExpr(TimeBase.Bedtime, -60)), (Anchor.parse("start:sleep|bedtime-60", true) as Parsed.Ok).value)
        assertEquals(Anchor.At(TimeExpr(TimeBase.Clock(LocalTime.NOON), 0)), (Anchor.parse("12:00", false) as Parsed.Ok).value)
        assertTrue(Anchor.parse("done:|wake", false) is Parsed.Error)
        assertTrue(Anchor.parse("start:sleep|", false) is Parsed.Error)
    }

    @Test
    fun triggers() {
        assertEquals(Trigger.Adhan(Prayer.MAGHRIB), (Trigger.parse("event:adhan:maghrib") as Parsed.Ok).value)
        assertEquals(Trigger.Misses(2), (Trigger.parse("event:misses:2") as Parsed.Ok).value)
        assertEquals(Trigger.Slot(SlotType.JUMMAH), (Trigger.parse("slot:jummah") as Parsed.Ok).value)
        assertEquals(Trigger.Toggle(ToggleName.FRIENDS), (Trigger.parse("toggle:friends") as Parsed.Ok).value)
        assertEquals(Trigger.Time(TimeExpr(TimeBase.Bedtime, -60)), (Trigger.parse("time:bedtime-60") as Parsed.Ok).value)
        assertTrue((Trigger.parse("time:bedtime-60") as Parsed.Ok).value.planned)
        assertTrue(!(Trigger.parse("manual") as Parsed.Ok).value.planned)
    }

    @Test
    fun ladderOrder() {
        val p = Protocol(
            id = "m", kind = ProtocolKind.MORNING, title = "M", trigger = "manual", steps = listOf(Step("a")),
            minimal = listOf(Step("b")), single = listOf(Step("c")),
        )
        assertEquals(Version.MINIMAL, p.nextVersion(Version.FULL, null))
        assertEquals(Version.SINGLE, p.nextVersion(Version.MINIMAL, null))
        assertNull(p.nextVersion(Version.SINGLE, null))
    }
}
