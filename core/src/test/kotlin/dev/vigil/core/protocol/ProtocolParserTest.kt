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

    /** The bundled file with [p] instead of its protocol of the same kind (the kinds the engine runs once). */
    private fun replacingKind(p: JsonObject): JsonObject = with(
        "protocols",
        JsonArray((base["protocols"]!! as JsonArray).filter { it.jsonObject["kind"] != p["kind"] } + p),
    )

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
        // A reference to a protocol not (yet) in the file is fine but noted (inside max(...) too).
        assertTrue(warnings(pool("windows", win("done:nap|wake+60", "next"))).any { it.message.contains("no protocol \"nap\"") })
        assertTrue(warnings(pool("windows", win("max(start:nap|wake,woke+60)", "next"))).any { it.message.contains("no protocol \"nap\"") })
        assertTrue(warnings(base).none { it.message.contains("no protocol \"morning\"") || it.message.contains("no protocol \"sleep\"") })
        // max(<anchor>,<anchor>): the later of two anchors; woke = when the wake alarm was dismissed.
        assertOk(pool("windows", win("max(done:morning|woke+60,woke+60)", "next")))
        assertOk(pool("windows", win("12:00", "max(meal:dinner+45,start:sleep|bedtime-60)")))
        assertError(pool("windows", win("max(12:00)", "13:00")), "opens", "two anchors")
        assertError(pool("windows", win("max(12:00,13:00", "14:00")), "opens", "two anchors")
        assertError(pool("windows", win("max(12:00,max(13:00,14:00))", "15:00")), "opens", "two anchors")
        assertError(pool("windows", win("12:00", "max(next,13:00)")), "closes", "two anchors")
        assertError(pool("windows", win("max(12:00,noon)", "13:00")), "opens", "not a time")
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
            when (k) {
                ProtocolKind.PRAYER -> protocol(id = "k$i", kind = k.json, trigger = "event:adhan:isha")
                // Jummah: slot:jummah, an arrival step, then a done step.
                ProtocolKind.JUMMAH -> protocol(
                    id = "k$i", kind = k.json, trigger = "slot:jummah",
                    steps = listOf(step(), obj("text" to "I'M AT UNI.", "arrival" to true), step("Done.", "done")),
                )
                // The switches, the call card and the nudges each need their own trigger and lock.
                ProtocolKind.WALK -> protocol(id = "k$i", kind = k.json, trigger = "toggle:walking", lock = obj("type" to "pool"))
                ProtocolKind.FRIENDS -> protocol(id = "k$i", kind = k.json, trigger = "toggle:friends", lock = obj("type" to "pool"))
                ProtocolKind.CALL -> protocol(id = "k$i", kind = k.json, trigger = "slot:call", lock = obj("type" to "none"))
                ProtocolKind.NUDGE -> protocol(id = "k$i", kind = k.json, trigger = "manual", lock = obj("type" to "screen"))
                else -> protocol(id = "k$i", kind = k.json, trigger = "manual")
            }
        }
        // The bundled Isha is replaced so the prayer kind does not clash with it.
        val json = with("protocols", JsonArray(protos))
        val file = assertOk(json)
        assertEquals(ProtocolKind.entries.toList(), file.protocols.map { it.kind })
    }

    @Test
    fun unknownKindAndStepType() {
        assertError(withProtocols(protocol(kind = "nap")), "(test).kind", "must be one of")
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
            "slot:call", "slot:commute", "time:21:30", "time:wake+60", "time:bedtime-60", "time:meal:dinner+45",
            "time:class_start-75", "time:class_end", "toggle:walking", "toggle:friends", "slot:meal:lunch", "slot:meal:breakfast",
        )
        ok.forEachIndexed { i, t -> assertOk(withProtocols(protocol(id = "t$i", trigger = t))) }
        assertEquals(Trigger.Slot(SlotType.MEAL, "dinner"), (Trigger.parse("slot:meal:dinner") as Parsed.Ok).value)
        assertEquals(Trigger.Slot(SlotType.MEAL), (Trigger.parse("slot:meal") as Parsed.Ok).value)
        assertError(withProtocols(protocol(trigger = "slot:meal:snack")), "trigger", "not a meal")
        assertError(withProtocols(protocol(trigger = "slot:study:math")), "trigger", "not a slot")
        assertError(withProtocols(protocol(trigger = "event:adhan:duhr")), "trigger", "not a prayer")
        assertError(withProtocols(protocol(trigger = "event:adhan:fajr")), "trigger", "prayer protocols only")
        assertError(withProtocols(protocol(trigger = "event:after:nothing")), "trigger", "no protocol \"nothing\"")
        assertError(withProtocols(protocol(trigger = "event:after:test")), "trigger", "after itself")
        assertError(withProtocols(protocol(trigger = "event:misses:0")), "trigger", "from 1 to 10")
        assertError(withProtocols(protocol(trigger = "event:misses:x")), "trigger", "from 1 to 10")
        assertError(withProtocols(protocol(trigger = "slot:nap")), "trigger", "not a slot")
        // slot:jummah is the Jummah sequence: jummah protocols only, and they start only from it.
        assertError(withProtocols(protocol(trigger = "slot:jummah")), "trigger", "jummah protocols only")
        assertError(withProtocols(protocol(id = "j2", kind = "jummah", trigger = "manual")), "trigger", "use slot:jummah")
        assertError(withProtocols(protocol(trigger = "time:7am")), "trigger", "not a time")
        assertError(withProtocols(protocol(trigger = "time:wake+01")), "trigger", "leading zeros")
        assertError(withProtocols(protocol(trigger = "time:wake+2000")), "trigger", "more than 1440")
        assertError(withProtocols(protocol(trigger = "toggle:quiet")), "trigger", "cannot start a protocol")
        assertError(withProtocols(protocol(trigger = "event:sunrise")), "trigger", "not a known event")
        assertError(withProtocols(protocol(trigger = "cron:5")), "trigger", "not a trigger")
        assertError(withProtocols(protocol(trigger = " manual")), "trigger", "no spaces")
    }

    @Test
    fun roundsWorkoutChimeAndUnlock() {
        fun rounds(min: Int? = 50, breakMin: Int? = 10) =
            JsonObject(obj("text" to "Work.", "type" to "rounds", "min" to min, "break_min" to breakMin).filterValues { it !is kotlinx.serialization.json.JsonNull })
        val study = protocol(kind = "study", lock = obj("type" to "screen"), steps = listOf(rounds(), step("Line.", "text")))
        assertOk(withProtocols(study))
        assertError(withProtocols(protocol(kind = "reset", steps = listOf(rounds()))), "steps[0].type", "only study protocols")
        assertError(withProtocols(protocol(kind = "study", steps = listOf(rounds(min = null)))), "steps[0].min", "minutes of work per round")
        assertError(withProtocols(protocol(kind = "study", steps = listOf(rounds(breakMin = 61)))), "steps[0].break_min", "from 0 to 60")
        assertError(withProtocols(protocol(kind = "study", steps = listOf(rounds(), rounds()))), "steps", "more than one rounds step")
        assertTrue(warnings(withProtocols(protocol(steps = listOf(obj("text" to "x", "break_min" to 5))))).any { it.path.endsWith("break_min") })
        val workout = obj("text" to "Workout.", "type" to "workout")
        assertOk(withProtocols(protocol(kind = "gym", lock = obj("type" to "pool"), steps = listOf(workout, step()))))
        assertError(withProtocols(protocol(kind = "study", steps = listOf(workout))), "steps[0].type", "only gym protocols")
        assertError(withProtocols(protocol(kind = "gym", steps = listOf(workout, workout))), "steps", "more than one workout step")
        // An attention timer without min lasts the ramp: no lock minutes needed.
        val look = obj("text" to "Look.", "type" to "timer", "chime" to true)
        assertOk(withProtocols(protocol(kind = "attention", lock = obj("type" to "screen"), steps = listOf(look))))
        assertError(withProtocols(protocol(kind = "reset", lock = obj("type" to "screen"), steps = listOf(look))), "steps[0].min", "lock has no minutes")
        assertTrue(warnings(withProtocols(protocol(steps = listOf(obj("text" to "x", "chime" to true))))).any { it.path.endsWith("chime") })
        assertTrue(warnings(withProtocols(protocol(lock = obj("type" to "none"), steps = listOf(obj("text" to "x", "unlock" to true))))).any { it.path.endsWith("unlock") })
        assertError(withProtocols(protocol(steps = listOf(obj("text" to "x", "unlock" to "yes")))), "unlock", "true or false")
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
        // Tapping done is what confirms a prayer: without one its steps would finish it unconfirmed.
        fun dhuhrWith(vararg fields: Pair<String, JsonElement>) = JsonObject(base).let {
            val list = (it["protocols"] as JsonArray).toMutableList()
            list[2] = JsonObject(list[2].jsonObject + fields)
            JsonObject(it + ("protocols" to JsonArray(list)))
        }
        val noDone = dhuhrWith("steps" to JsonArray(listOf(step("Pray."), step("Quiet.", "timer"))))
        assertError(noDone, "protocols[2] (prayer-dhuhr).steps", "needs a done step")
        assertError(dhuhrWith("weekdays" to obj("fri" to obj("steps" to listOf(step("Pray."))))), "weekdays.fri.steps", "needs a done step")
        assertOk(dhuhrWith("weekdays" to obj("fri" to obj("steps" to listOf(step("Pray."), step("Done.", "done"))))))
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
        fun call(extra: Map<String, JsonElement>) = protocol(kind = "call", trigger = "slot:call", lock = obj("type" to "none"), extra = extra)
        assertOk(replacingKind(call(mapOf("short" to JsonArray(listOf(step("Message.", "done")))))))
        assertError(replacingKind(call(mapOf("minimal" to JsonArray(listOf(step("Message.", "done")))))), "minimal", "may only define short")
        // Attention reset (spec): "short = 3 minutes ... There is no minimal version below that."
        assertOk(withProtocols(protocol(kind = "attention", extra = mapOf("short" to JsonArray(listOf(step()))))))
        assertError(withProtocols(protocol(kind = "attention", extra = mapOf("minimal" to JsonArray(listOf(step()))))), "minimal", "may only define short")
        assertError(withProtocols(protocol(kind = "attention", extra = mapOf("single" to JsonArray(listOf(step()))))), "single", "may only define short")
        assertEquals(setOf(Version.SHORT), ProtocolKind.ATTENTION.ladder)
        assertError(withProtocols(protocol(kind = "reset", extra = mapOf("single" to JsonArray(listOf(step(), step("b")))))), "single", "exactly one step")
        assertError(withProtocols(protocol(kind = "reset", extra = mapOf("short" to JsonArray(emptyList())))), "short", "at least one step")
        val prayerShort = base.let {
            val list = (it["protocols"] as JsonArray).toMutableList()
            list[2] = JsonObject(list[2].jsonObject + ("short" to JsonArray(listOf(step()))))
            JsonObject(it + ("protocols" to JsonArray(list)))
        }
        assertError(prayerShort, "protocols[2] (prayer-dhuhr).short", "prayer protocols have no step-down ladder")
    }

    @Test
    fun theM8KindsNeedTheirTriggerLockAndOneProtocol() {
        assertError(replacingKind(protocol(kind = "walk", lock = obj("type" to "pool"))), "trigger", "use toggle:walking")
        assertError(replacingKind(protocol(kind = "walk", trigger = "toggle:walking")), "lock.type", "use \"pool\"")
        assertError(replacingKind(protocol(kind = "friends", trigger = "toggle:walking", lock = obj("type" to "pool"))), "trigger", "use toggle:friends")
        assertError(replacingKind(protocol(kind = "call", lock = obj("type" to "none"))), "trigger", "use slot:call")
        assertError(replacingKind(protocol(kind = "call", trigger = "slot:call")), "lock.type", "use \"none\"")
        assertError(replacingKind(protocol(kind = "nudge", trigger = "time:10:00", lock = obj("type" to "screen"))), "trigger", "use manual")
        assertError(replacingKind(protocol(kind = "nudge", lock = obj("type" to "pool"))), "lock.type", "use \"screen\"")
        // A call's every version needs its DONE step (it confirms the call once it unlocks).
        val noDone = protocol(kind = "call", trigger = "slot:call", lock = obj("type" to "none"), extra = mapOf("short" to JsonArray(listOf(step()))))
        assertError(replacingKind(noDone), "(test).short", "a call needs a done step")
        // The engine runs each of these once: a second one is an error.
        assertError(withProtocols(protocol(kind = "friends", trigger = "toggle:friends", lock = obj("type" to "pool"))), "(test).kind", "one per file")
        // So are the wind-down (sleep shifting and the night lock follow one) and Jummah (Friday's Dhuhr gives way to one).
        assertError(withProtocols(protocol(kind = "sleep", trigger = "time:bedtime-60", lock = obj("type" to "screen"))), "(test).kind", "one per file")
        assertError(withProtocols(protocol(kind = "jummah", trigger = "slot:jummah")), "(test).kind", "one per file")
        // Nudges without lines show their first step: allowed, with a warning.
        val bare = protocol(kind = "nudge", lock = obj("type" to "screen"))
        assertOk(replacingKind(bare))
        assertTrue(warnings(replacingKind(bare)).any { it.path.endsWith("params.nudges") })
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
        // No dishes are planned (the user does not want them): an old "dishes" is only a warning.
        assertTrue(warnings(withProtocols(protocol(kind = "meal", extra = mapOf("params" to obj("dishes" to obj("lunch" to "x")))))).any { it.path.endsWith("params.dishes") })
        assertError(withProtocols(protocol(kind = "promise", extra = mapOf("params" to obj("delays" to listOf(5, 0))))), "delays[1]", "from 1 to 240")
        assertError(withProtocols(protocol(kind = "promise", extra = mapOf("params" to obj("delays" to emptyList<Int>())))), "delays", "list of whole numbers")
        val file = assertOk(withProtocols(protocol(kind = "study", extra = mapOf("params" to obj("default_min" to 120)))))
        assertEquals(120, file.find("test")!!.intParam("default_min"))
        assertEquals(90, assertOk(withProtocols(protocol(kind = "gym"))).find("test")!!.intParam("rest_s"))
        assertError(withProtocols(protocol(kind = "study", extra = mapOf("params" to obj("default_min" to 601)))), "default_min", "from 5 to 600")
        assertError(
            withProtocols(protocol(kind = "attention", extra = mapOf("params" to obj("start_min" to 5, "max_min" to 4)))),
            "params.max_min", "at least start_min",
        )
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

    @Test
    fun wokeAndMaxAnchors() {
        assertEquals(TimeExpr(TimeBase.Woke, 60), (TimeExpr.parse("woke+60") as Parsed.Ok).value)
        assertEquals("woke+60", TimeExpr(TimeBase.Woke, 60).toString())
        assertEquals(
            Anchor.Max(Anchor.Done("morning", TimeExpr(TimeBase.Woke, 60)), Anchor.At(TimeExpr(TimeBase.Woke, 60))),
            (Anchor.parse("max(done:morning|woke+60,woke+60)", false) as Parsed.Ok).value,
        )
        assertTrue(Anchor.parse("max(12:00, 13:00)", false) is Parsed.Error)
    }

    @Test
    fun stepFieldsForSleepAndMorning() {
        val todos = obj("text" to "Tomorrow.", "type" to "text", "lines" to 4, "todos" to true)
        val sleep = protocol(id = "s", kind = "sleep", lock = obj("type" to "screen"), steps = listOf(todos, step("Sleep.", "done")))
        val file = assertOk(replacingKind(sleep))
        assertTrue(file.find("s")!!.steps[0].todos)
        assertError(replacingKind(protocol(kind = "sleep", steps = listOf(obj("text" to "x", "todos" to true)))), "steps[0].todos", "only a text step")
        assertTrue(warnings(withProtocols(protocol(steps = listOf(todos, step("Done.", "done"))))).any { it.path.endsWith("steps[0].todos") })
        assertError(withProtocols(protocol(steps = listOf(obj("text" to "x", "todos" to "yes")))), "todos", "true or false")
        val walk = obj("text" to "Walk.", "type" to "timer", "min" to 15, "skip_on_class_days" to true)
        val morning = protocol(id = "m", kind = "morning", lock = obj("type" to "pool"), steps = listOf(step("Up."), walk))
        assertTrue(assertOk(withProtocols(morning)).find("m")!!.steps[1].skipOnClassDays)
        assertError(withProtocols(protocol(id = "m", kind = "morning", steps = listOf(walk))), "steps", "nothing would be left")
        val noise = obj("text" to "Lights low.", "noise_toggle" to true)
        assertTrue(assertOk(withProtocols(protocol(steps = listOf(noise, step("Done.", "done"))))).find("test")!!.steps[0].noiseToggle)
    }

    @Test
    fun stepFieldsForJummahResetAndPromise() {
        val arrival = obj("text" to "I'M AT UNI.", "arrival" to true)
        fun jummah(steps: List<JsonObject>, lock: JsonObject = obj("type" to "screen", "minutes" to 45, "done_after_min" to 15)) =
            protocol(id = "j2", kind = "jummah", trigger = "slot:jummah", lock = lock, steps = steps)
        val ok = assertOk(replacingKind(jummah(listOf(obj("text" to "Leave.", "walking_on" to true), arrival, step("Done.", "done")))))
        assertTrue(ok.find("j2")!!.steps[0].walkingOn)
        assertTrue(ok.find("j2")!!.steps[1].arrival)
        assertError(replacingKind(jummah(listOf(step(), step("Done.", "done")))), "(j2).steps", "exactly one arrival step")
        assertError(replacingKind(jummah(listOf(arrival, arrival, step("Done.", "done")))), "(j2).steps", "exactly one arrival step")
        assertError(replacingKind(jummah(listOf(step("Done.", "done"), arrival))), "(j2).steps", "done step after its arrival step")
        assertError(replacingKind(jummah(listOf(arrival, step("Done.", "done")), lock = obj("type" to "screen"))), "(j2).lock.minutes", "needs minutes")
        assertError(withProtocols(protocol(kind = "morning", steps = listOf(arrival, step("Done.", "done")))), "steps[0].arrival", "only a jummah")
        // Re-enter: reset protocols only, and the last step.
        val reenter = obj("text" to "Re-enter.", "type" to "done", "reenter" to true)
        assertTrue(assertOk(withProtocols(protocol(id = "r2", steps = listOf(step(), reenter)))).find("r2")!!.steps[1].reenter)
        assertError(withProtocols(protocol(id = "r2", steps = listOf(reenter, step()))), "steps[0].reenter", "last step")
        assertError(withProtocols(protocol(id = "r2", kind = "study", steps = listOf(step(), reenter))), "steps[1].reenter", "only reset")
        // Optional: text steps only (a warning elsewhere).
        val name = obj("text" to "Name it.", "type" to "text", "optional" to true)
        assertTrue(assertOk(withProtocols(protocol(id = "r2", steps = listOf(name, step("Done.", "done"))))).find("r2")!!.steps[0].optional)
        assertTrue(warnings(withProtocols(protocol(id = "r2", steps = listOf(obj("text" to "x", "optional" to true), step("Done.", "done"))))).any { it.path.endsWith("steps[0].optional") })
        assertError(withProtocols(protocol(steps = listOf(obj("text" to "x", "walking_on" to "yes")))), "walking_on", "true or false")
        // Jummah params: prep_todos is a list of texts; the reset's next_within_min is in range.
        assertError(replacingKind(jummah(listOf(arrival, step("Done.", "done"))).let { JsonObject(it + ("params" to obj("prep_todos" to listOf(3)))) }), "prep_todos", "list of texts")
        assertError(withProtocols(protocol(id = "r2", extra = mapOf("params" to obj("next_within_min" to 300)))), "next_within_min", "from 0 to 240")
    }
}
