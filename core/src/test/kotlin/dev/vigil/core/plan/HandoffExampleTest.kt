package dev.vigil.core.plan

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import dev.vigil.core.engine.Engine
import dev.vigil.core.log.EventType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime

/** Like JUnit's fail, but typed Nothing, so it can end an elvis expression. */
private fun fail(message: String): Nothing = throw AssertionError(message)

/**
 * spec/HANDOFF.md is the prompt every planner gets, and planners copy its examples, so every ```json block in it is
 * checked here with the real parser (and spec/schema.json). A block holds one JSON value, or one per line.
 * - A whole plan (it has "directives") must import without a single warning.
 * - A trigger, a directive, some directive fields or some top-level fields are put into a small valid plan, which
 *   must then import without a single warning.
 * - A bundle excerpt (only keys of the weekly bundle) must use the bundle's key names, values and key order.
 * A value that is none of these fails the test, so a new kind of example needs a rule here. No example is meant to
 * cause a warning; one that should would need its expected warning listed here explicitly.
 */
class HandoffExampleTest {
    private val repoRoot = File(System.getProperty("vigil.repoRoot") ?: error("vigil.repoRoot is not set"))
    private val handoff = File(repoRoot, "spec/HANDOFF.md").readText()

    private val schema: JsonSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(
        ObjectMapper().readTree(File(repoRoot, "spec/schema.json")),
        SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build(),
    )

    private val blocks: List<String> = Regex("```json\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        .findAll(handoff).map { it.groupValues[1] }.toList()

    /** What an example in HANDOFF.md is. */
    private enum class Kind { PLAN, BUNDLE, TRIGGER, DIRECTIVE, DIRECTIVE_FIELDS, PLAN_FIELDS }

    private data class Example(val text: String, val value: JsonObject, val kind: Kind)

    private val examples: List<Example> by lazy {
        blocks.flatMap { block ->
            val whole = runCatching { Json.parseToJsonElement(block) }.getOrNull()
            val parts = if (whole != null) {
                listOf(block to whole)
            } else {
                block.lines().filter { it.isNotBlank() }.map { line ->
                    line to (runCatching { Json.parseToJsonElement(line) }.getOrNull() ?: fail("Not valid JSON in HANDOFF.md:\n$line"))
                }
            }
            parts.map { (text, element) ->
                val obj = element as? JsonObject ?: fail("A JSON example in HANDOFF.md is not an object:\n$text")
                Example(text, obj, kindOf(obj) ?: fail("HandoffExampleTest doesn't know what this example is:\n$text"))
            }
        }
    }

    private fun kindOf(obj: JsonObject): Kind? {
        val keys = obj.keys.filterNot { it.startsWith("_") }.toSet()
        return when {
            keys.isEmpty() -> null
            "directives" in keys -> Kind.PLAN
            BUNDLE_KEYS.containsAll(keys) -> Kind.BUNDLE
            "type" in keys -> Kind.TRIGGER
            "id" in keys -> Kind.DIRECTIVE
            DIRECTIVE_FIELDS.containsAll(keys) -> Kind.DIRECTIVE_FIELDS
            PLAN_FIELDS.containsAll(keys) -> Kind.PLAN_FIELDS
            else -> null
        }
    }

    @Test
    fun handoffHasJsonExamplesOfEveryKind() {
        assertTrue("No ```json blocks found in HANDOFF.md", blocks.isNotEmpty())
        val kinds = examples.map { it.kind }.toSet()
        for (kind in listOf(Kind.PLAN, Kind.BUNDLE, Kind.TRIGGER, Kind.DIRECTIVE, Kind.PLAN_FIELDS)) {
            assertTrue("HANDOFF.md has no $kind example any more", kind in kinds)
        }
    }

    @Test
    fun everyPlanExampleImportsWithoutWarnings() {
        for (example in examples) {
            val text = when (example.kind) {
                Kind.PLAN -> example.text
                Kind.TRIGGER -> plan(listOf(stub("example") + ("trigger" to example.value)))
                Kind.DIRECTIVE -> plan(listOf(example.value))
                Kind.DIRECTIVE_FIELDS -> plan(listOf(stub("example") + example.value))
                Kind.PLAN_FIELDS -> {
                    val ids = keepIds(example.value).ifEmpty { listOf("example") }
                    plan(ids.map(::stub), example.value)
                }
                Kind.BUNDLE -> continue
            }
            val label = "HANDOFF.md example (${example.kind}):\n${example.text}"
            val parsed = assertImportsCleanly(label, text)
            parsed.directives.forEach { d ->
                assertTrue("$label\nuses an unsupported trigger type", d.trigger !is Trigger.Unsupported)
            }
        }
    }

    @Test
    fun everyBundleExampleUsesTheBundleFormat() {
        val bundles = examples.filter { it.kind == Kind.BUNDLE }
        assertTrue("HANDOFF.md has no weekly bundle example", bundles.isNotEmpty())
        bundles.forEach { checkBundleExcerpt(it.value, it.text) }
    }

    @Test
    fun theExamplePlanIsAValidWeekOnePlan() {
        val plans = examples.filter { it.kind == Kind.PLAN }
        assertEquals("HANDOFF.md should hold exactly one whole example plan", 1, plans.size)
        val raw = plans.single()
        val plan = assertImportsCleanly("HANDOFF.md example plan", raw.text)
        val all = plan.directives + plan.fallbackPlan?.directives.orEmpty()

        assertEquals(Plan.SUPPORTED_SCHEMA_VERSION, plan.schemaVersion)
        assertEquals("_readme comes first", "_readme", raw.value.keys.first())
        assertTrue(
            "context starts with the week number and the stable-week count, as HANDOFF.md asks",
            plan.context.orEmpty().startsWith("Week 1 · stable weeks in a row: 0. "),
        )

        // Progression rule 1: the prayers, water and a sleep target only.
        val prayers = plan.directives.mapNotNull { (it.trigger as? Trigger.Adhan)?.prayer }.toSet()
        assertEquals("all five prayers", Prayer.entries.toSet(), prayers)
        val others = plan.directives.filter { it.trigger !is Trigger.Adhan }
        assertTrue(
            "week 1 has nothing but prayers, water and sleep: ${others.map { it.id }}",
            others.all { it.trigger is Trigger.Time && (it.id.startsWith("water") || it.id.startsWith("sleep")) },
        )
        assertTrue("week 1 has water", others.any { it.id.startsWith("water") })
        assertTrue("week 1 has a sleep target", others.any { it.id.startsWith("sleep") })

        // Rule 5: no tier 3 in week 1, so nothing overrides quiet mode either.
        assertTrue("week 1 is tiers 1-2 only", all.all { it.tier in 1..2 })

        // The v1.1 parts a planner should copy.
        assertTrue("bad_day.keep", plan.badDay.keep.isNotEmpty())
        assertNotNull("minimum", plan.minimum)
        assertNotNull("after_bad_day", plan.afterBadDay)
        assertTrue("fallback_plan with 3-5 directives", plan.fallbackPlan?.directives?.size in 3..5)
        val rated = raw.value["directives"]!!.jsonArray.count { "rating" in it.jsonObject }
        assertTrue("some directives choose their rating", rated > 0)
    }

    @Test
    fun theProgressionRulesAreThereAndProminent() {
        val heading = "### PLANNER PROGRESSION RULES"
        assertTrue("HANDOFF.md lost its $heading section", heading in handoff)
        assertTrue("the progression rules come before the format reference", handoff.indexOf(heading) < handoff.indexOf("## plan.json format"))
        val section = handoff.substringAfter(heading).substringBefore("\n### ")
        for (rule in listOf(
            "Week 1 is minimal:** the prayers, water and a sleep target only",
            "Add at most one new habit per week,** and only if the last log shows high completion",
            "Step up only when ratings are mostly EASY/OK",
            "A bad week or WORSE check-ins mean hold or shrink, never add",
            "Tier 3 and any lockdown only after a couple of stable weeks",
        )) {
            assertTrue("Progression rule missing or reworded: \"$rule\"", rule in section)
        }
    }

    @Test
    fun everyPlanFieldAndValueIsDocumented() {
        val names = PLAN_FIELDS + DIRECTIVE_FIELDS + listOf(
            "id", "directives", "keep", "easier", "step_down_after_hard", "text", "min_seconds",
            "at", "days", "prayer", "apps", "opens", "window_min", "continuous_min", "cooldown_min",
        )
        val values = RatingPolicy.entries.map { it.json } + DayMode.entries.map { it.json } +
            CheckInAnswer.entries.map { it.json } + QuietPolicy.entries.map { it.json } + Trigger.SUPPORTED_TYPES
        for (name in names + values) {
            assertTrue("HANDOFF.md never names `$name`", Regex("[`\"]${Regex.escape(name)}[`\"]").containsMatchIn(handoff))
        }
        assertTrue("reserved id prefix", "`${PlanParser.RESERVED_ID_PREFIX}`" in handoff)
        for (id in listOf(Engine.CHECK_IN_ID, Engine.DEMO_1_ID)) assertTrue("built-in id $id", "`$id`" in handoff)
        assertTrue("adapt range", "1–${PlanParser.MAX_STEP_DOWN_AFTER_HARD}" in handoff)
        assertTrue("schema version", "schema_version ${Plan.SUPPORTED_SCHEMA_VERSION}" in handoff)
        for (key in BUNDLE_KEYS + SUMMARY_KEYS + RATING_KEYS + CHECK_IN_KEYS) {
            assertTrue("HANDOFF.md's bundle section never names `$key`", "`$key`" in handoff)
        }
    }

    @Test
    fun theSamplePlanItPointsToShowsEveryField() {
        assertTrue("HANDOFF.md points to spec/sample-plan.json", "spec/sample-plan.json" in handoff)
        val text = File(repoRoot, "spec/sample-plan.json").readText()
        assertTrue("spec/sample-plan.json imports", PlanParser.parse(text) is ParseResult.Success)
        val root = Json.parseToJsonElement(text).jsonObject
        val directives = root["directives"]!!.jsonArray.map { it.jsonObject }
        for (key in PLAN_FIELDS) assertTrue("sample-plan.json has no `$key`", key in root)
        for (key in DIRECTIVE_FIELDS) assertTrue("no directive in sample-plan.json has `$key`", directives.any { key in it })
        val types = directives.map { it["trigger"]!!.jsonObject["type"]!!.jsonPrimitive.content }.toSet()
        assertEquals("sample-plan.json uses every trigger type", Trigger.SUPPORTED_TYPES.toSet(), types)
    }

    // ---------------------------------------------------------------- helpers

    /** Parses [text] with the app's parser and spec/schema.json; both must accept it, and the parser must not warn. */
    private fun assertImportsCleanly(label: String, text: String): Plan {
        val schemaErrors = schema.validate(ObjectMapper().readTree(text)).map { "${it.instanceLocation}: ${it.message}" }
        assertTrue("$label\nschema.json rejects it:\n${schemaErrors.joinToString("\n")}", schemaErrors.isEmpty())
        return when (val result = PlanParser.parse(text)) {
            is ParseResult.Success -> {
                assertEquals("$label\nwarnings", emptyList<Issue>(), result.warnings)
                result.plan
            }
            is ParseResult.Failure -> fail("$label\nis invalid: ${result.errors}")
        }
    }

    /** A small valid directive to build examples into. */
    private fun stub(id: String): JsonObject = buildJsonObject {
        put("id", id)
        put("tier", 2)
        put("title", id.uppercase().take(40))
        putJsonObject("trigger") {
            put("type", "time")
            put("at", "12:00")
        }
        putJsonArray("steps") {
            addJsonObject {
                put("text", "Stand up.")
                put("min_seconds", 5)
            }
            addJsonObject {
                put("text", "Sit down.")
                put("min_seconds", 5)
            }
        }
        put("why", "Checked by HandoffExampleTest.")
        put("quiet", "queue")
    }

    private operator fun JsonObject.plus(other: Map<String, JsonElement>) = JsonObject(this.toMap() + other)

    private operator fun JsonObject.plus(entry: Pair<String, JsonElement>) = JsonObject(this.toMap() + entry)

    /** A minimal valid plan around [directives], with [extra] top-level fields on top. */
    private fun plan(directives: List<JsonObject>, extra: Map<String, JsonElement> = emptyMap()): String {
        val base = buildJsonObject {
            put("_readme", "Built by HandoffExampleTest around one example from HANDOFF.md.")
            put("schema_version", Plan.SUPPORTED_SCHEMA_VERSION)
            put("week_of", "2026-10-19")
            put("expires", "2026-10-26T04:00")
            put("directives", JsonArray(directives))
            putJsonObject("bad_day") { put("keep", JsonArray(listOf(directives.first()["id"]!!))) }
        }
        return (base + extra).toString()
    }

    /** The directive ids an example of top-level fields refers to, so the plan around it can have them. */
    private fun keepIds(fields: JsonObject): List<String> = listOf("bad_day", "minimum")
        .flatMap { key -> (fields[key] as? JsonObject)?.get("keep")?.jsonArray.orEmpty() }
        .mapNotNull { it.jsonPrimitive.contentOrNull }
        .distinct()

    private fun checkBundleExcerpt(obj: JsonObject, text: String) {
        val label = "HANDOFF.md bundle example:\n$text\n"
        fun string(el: JsonElement?): String? = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun assertTimestamp(el: JsonElement?, what: String) {
            val ts = string(el) ?: fail("$label$what needs a ts")
            assertTrue("$label$what: ts \"$ts\" is not local time with offset", runCatching { OffsetDateTime.parse(ts) }.isSuccess)
        }
        fun assertKeys(o: JsonObject, required: Set<String>, optional: Set<String>, what: String) {
            assertTrue("$label$what lacks ${required - o.keys}", o.keys.containsAll(required))
            assertTrue("$label$what has unknown keys ${o.keys - required - optional}", (required + optional).containsAll(o.keys))
        }
        // Planners copy the shape they see, so an excerpt keeps the app's key order ("_" comments aside).
        fun assertOrder(o: JsonObject, order: Collection<String>, what: String) {
            val keys = o.keys.filterNot { it.startsWith("_") }
            assertEquals("$label$what: keys not in the order the app writes them", order.filter { it in keys }, keys)
        }

        assertOrder(obj, BUNDLE_KEYS, "the bundle")

        obj["format"]?.let { assertEquals(label, "vigil-bundle", string(it)) }
        obj["schema_version"]?.let { assertEquals(label, Plan.SUPPORTED_SCHEMA_VERSION, it.jsonPrimitive.intOrNull) }
        obj["plan_status"]?.let { assertTrue("$label plan_status", string(it) in setOf("complete", "running", "none")) }
        obj["period"]?.let { assertKeys(it.jsonObject, setOf("since", "until"), emptySet(), "period") }
        obj["stepped_down"]?.let { list -> list.jsonArray.forEach { assertNotNull("$label stepped_down holds ids", string(it)) } }
        obj["plan"]?.takeIf { it != JsonNull }?.let { assertImportsCleanly("${label}plan", it.toString()) }
        obj["ratings"]?.jsonArray?.forEach { el ->
            val r = el.jsonObject
            assertKeys(r, RATING_KEYS.toSet() - "variant", setOf("variant"), "a ratings entry")
            assertOrder(r, RATING_KEYS, "a ratings entry")
            assertTimestamp(r["ts"], "a ratings entry")
            assertTrue("$label rating", string(r["rating"]) in Rating.entries.map { it.json } + "skipped")
            r["variant"]?.let { assertEquals(label, Variant.EASIER, string(it)) }
        }
        obj["check_ins"]?.jsonArray?.forEach { el ->
            val c = el.jsonObject
            assertKeys(c, CHECK_IN_KEYS.toSet() - "detail", setOf("detail"), "a check_ins entry")
            assertOrder(c, CHECK_IN_KEYS, "a check_ins entry")
            assertTimestamp(c["ts"], "a check_ins entry")
            assertTrue("$label answer", string(c["answer"]) in CheckInAnswer.entries.map { it.json } + Engine.ANSWER_NONE)
            assertTrue("$label mode", string(c["mode"]) in DayMode.entries.map { it.json })
        }
        obj["summary"]?.jsonObject?.values?.forEach { counts ->
            val unknown = counts.jsonObject.keys - SUMMARY_KEYS
            assertTrue("$label summary has unknown counts $unknown", unknown.isEmpty())
        }
        obj["events"]?.jsonArray?.forEach { el ->
            val e = el.jsonObject
            assertTimestamp(e["ts"], "an event")
            assertTrue("$label unknown event type ${e["type"]}", string(e["type"]) in EventType.DESCRIPTIONS.keys)
        }
    }

    private companion object {
        /** Top-level plan fields other than "directives" (and "_" comments). */
        val PLAN_FIELDS = setOf(
            "schema_version", "week_of", "expires", "generated_by", "context", "day_boundary",
            "bad_day", "minimum", "after_bad_day", "fallback_plan",
        )

        /** Directive fields other than "id". */
        val DIRECTIVE_FIELDS = setOf("tier", "title", "trigger", "steps", "why", "quiet", "rating", "variants", "adapt")

        /** The weekly bundle's top-level keys (vigil-week-<week_of>.json), in the order the app writes them. */
        val BUNDLE_KEYS = setOf(
            "format", "schema_version", "exported_at", "app_version", "timezone", "period", "plan_status", "plan",
            "stepped_down", "settings", "summary", "ratings", "check_ins", "event_types", "events",
        )

        /** The keys of one entry in the bundle's "ratings", in order ("variant" only when the easier version ran). */
        val RATING_KEYS = listOf("ts", "directive_id", "takeover_id", "rating", "variant")

        /** The keys of one entry in the bundle's "check_ins", in order ("detail" only when there is one). */
        val CHECK_IN_KEYS = listOf("ts", "answer", "mode", "detail")

        /** The counts per directive in the bundle's summary. */
        val SUMMARY_KEYS = setOf(
            EventType.DIRECTIVE_FIRED, EventType.DIRECTIVE_COMPLETED, EventType.DIRECTIVE_DISMISSED,
            EventType.DIRECTIVE_TIMED_OUT, EventType.BYPASSED_BAD_DAY, EventType.BYPASSED_MINIMUM, EventType.DROPPED,
            EventType.SKIPPED_BAD_DAY, EventType.SKIPPED_MINIMUM, EventType.QUEUED_QUIET, EventType.SKIPPED_QUIET,
            EventType.MISSED, EventType.REPEATED, EventType.RATING_SKIPPED,
            "avg_completion_s", "rated_easy", "rated_ok", "rated_hard",
        )
    }
}
