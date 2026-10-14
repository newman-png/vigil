package dev.vigil.core.log

import dev.vigil.core.engine.Engine
import dev.vigil.core.plan.Plan
import dev.vigil.core.plan.PlanParser
import dev.vigil.core.plan.Variant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Facts about the weekly bundle that are not in the log: what it covers, the plan that ran, app settings. */
data class BundleMeta(
    /** When the bundle was made: local time with offset, like [LogEvent.ts]. */
    val exportedAt: String,
    val appVersion: String,
    /** The phone's time zone id, e.g. Europe/London. */
    val timezone: String,
    /** Where the bundle's events start (local time with offset); null = the whole log. */
    val since: String?,
    /** Where they end, normally the moment of the export. */
    val until: String,
    /** plan.json exactly as imported (the bundle embeds it as JSON); null when there is no plan. */
    val planText: String?,
    /** [BundleExport.STATUS_COMPLETE] (it expired), [BundleExport.STATUS_RUNNING] or [BundleExport.STATUS_NONE]. */
    val planStatus: String,
    /** Directive ids that run their variants.easier for the rest of the plan (the engine's stepped_down list). */
    val steppedDown: List<String>,
    /** App settings that change how the log reads, e.g. [LogExport.SETTING_ALLOW_QUIET_OVERRIDE]. */
    val settings: Map<String, String>,
)

/**
 * Builds the weekly bundle, the app's only export: the plan that ran, its log, and the ratings and check-ins pulled
 * out of the log, in one self-describing file the planner reads cold to write next week's plan.
 */
object BundleExport {
    /** The bundle's "format" value, so a planner (or a tool) can tell it from a plan or a v1 log.json. */
    const val FORMAT = "vigil-bundle"

    /** The plan.json version this app reads: the version next week's plan should be written in. */
    const val SCHEMA_VERSION = Plan.SUPPORTED_SCHEMA_VERSION

    /** [BundleMeta.planStatus]: the plan expired; the fallback plan or the built-in minimal plan runs now. */
    const val STATUS_COMPLETE = "complete"

    /** [BundleMeta.planStatus]: the plan is still running (exported before it expired). */
    const val STATUS_RUNNING = "running"

    /** [BundleMeta.planStatus]: no plan was ever imported; the built-in minimal plan runs. */
    const val STATUS_NONE = "none"

    /** The "rating" of an entry in "ratings" when the question was asked but not answered. */
    const val RATING_SKIPPED = "skipped"

    private val TEST_TRIGGER_LIST = LogExport.TEST_TRIGGERS.sorted().joinToString(" or ")

    /** The first thing a planner reads: what the file is and what matters in it. Kept well under 300 words. */
    val README: String = """
        This is the weekly bundle from VIGIL, an Android app that runs a weekly plan.json written by a planner AI and
        records what happened. It never plans or judges: it only executes the plan and picks between options the plan
        wrote in advance.
        THE PARTS. format, exported_at, app_version: this file. schema_version: the plan.json version to write next.
        timezone: the phone's zone (every ts is local time with offset). period: what is covered (since null = whole
        log). plan_status: $STATUS_COMPLETE (expired), $STATUS_RUNNING or $STATUS_NONE. plan: the plan.json that ran.
        stepped_down: directives switched to variants.easier by adapt.step_down_after_hard. settings: app settings.
        summary: outcome counts per directive, not a judgement. ratings: EASY, OK or HARD after a completion
        ($RATING_SKIPPED = not answered; variant ${Variant.EASIER} = the easier version ran). check_ins: the morning
        check-in after a bad or minimum day (answer better, same, worse or ${Engine.ANSWER_NONE} = not answered; mode = the
        day's mode after it). event_types: what each event means. events: everything in time order; takeover_id groups
        one takeover. Test fires (trigger $TEST_TRIGGER_LIST) are try-outs: ignore them, as summary, ratings and
        check_ins do. summary also leaves out the app's own directives (ids starting with ${Engine.BUILTIN_ID_PREFIX}).
        WHAT MATTERS. Ratings and check-ins are the main signal for next week's plan. Bad days, minimum days and
        check-ins are never failures: the user was honest about a hard day. Read them with compassion; never add load to
        make up for them. Write next week's plan.json from this bundle and vision.md as HANDOFF.md (VIGIL repository)
        describes.
    """.trimIndent().replace('\n', ' ')

    /** The bundle as pretty-printed JSON, top-level keys in a fixed order (see spec/sample-bundle.json). */
    fun build(events: List<LogEvent>, meta: BundleMeta): String {
        val root = buildJsonObject {
            put("_readme", README)
            put("format", FORMAT)
            put("schema_version", SCHEMA_VERSION)
            put("exported_at", meta.exportedAt)
            put("app_version", meta.appVersion)
            put("timezone", meta.timezone)
            putJsonObject("period") {
                put("since", meta.since)
                put("until", meta.until)
            }
            put("plan_status", meta.planStatus)
            put("plan", embedPlan(meta.planText))
            putJsonArray("stepped_down") { meta.steppedDown.forEach { add(it) } }
            putJsonObject("settings") { meta.settings.forEach { (k, v) -> put(k, v) } }
            put("summary", LogExport.summary(events))
            put("ratings", ratings(events))
            put("check_ins", checkIns(events))
            put("event_types", JsonObject(EventType.DESCRIPTIONS.mapValues { JsonPrimitive(it.value) }))
            put("events", JsonArray(events.map { LogJson.json.encodeToJsonElement(LogEvent.serializer(), it) }))
        }
        return PrettyJson.encode(root)
    }

    /**
     * Every answer to the difficulty question, in log order, from the rated and rating_skipped events:
     * ts, directive_id, takeover_id, rating (easy, ok, hard, or [RATING_SKIPPED]) and variant when the easier one ran.
     * Test fires and the app's own directives are left out, as in [LogExport.summary].
     */
    fun ratings(events: List<LogEvent>): JsonArray = JsonArray(
        LogExport.withoutTests(events)
            .filter { it.type == EventType.RATED || it.type == EventType.RATING_SKIPPED }
            .filter { it.directiveId?.startsWith(Engine.BUILTIN_ID_PREFIX) == false }
            .map { e ->
                buildJsonObject {
                    put("ts", e.ts)
                    put("directive_id", e.directiveId)
                    put("takeover_id", e.takeoverId)
                    put("rating", if (e.type == EventType.RATED) e.rating else RATING_SKIPPED)
                    e.variant?.let { put("variant", it) }
                }
            },
    )

    /**
     * Every morning check-in, in log order, from the check_in events: ts, answer (better, same, worse, or none when it
     * was not answered), the day's mode it led to, and the detail when there is one. Test previews are left out.
     */
    fun checkIns(events: List<LogEvent>): JsonArray = JsonArray(
        LogExport.withoutTests(events)
            .filter { it.type == EventType.CHECK_IN }
            .map { e ->
                buildJsonObject {
                    put("ts", e.ts)
                    put("answer", e.answer)
                    put("mode", e.mode)
                    e.detail?.let { put("detail", it) }
                }
            },
    )

    /**
     * The plan as a JSON object, keys in the order they were written (numbers are rewritten in their plain form, e.g.
     * 2.50 as 2.5). Text that is somehow not a JSON object the bundle can hold is kept as a string rather than lost:
     * the bundle must always be written.
     */
    private fun embedPlan(text: String?): JsonElement {
        if (text == null) return JsonNull
        // A byte order mark is not part of the JSON (the plan parser drops it too).
        val parsed = runCatching { Json.parseToJsonElement(text.removePrefix("\uFEFF")) }.getOrNull()
        // Absurd nesting could overflow the stack while the bundle is printed.
        if (parsed !is JsonObject || nestedDeeperThan(parsed, PlanParser.MAX_NESTING)) return JsonPrimitive(text)
        // A number too large for a double (1e999, say in a "_note") parses, but cannot be written back.
        val printable = runCatching { Json.encodeToString(JsonObject.serializer(), parsed) }.isSuccess
        return if (printable) parsed else JsonPrimitive(text)
    }

    /** True when [root] has objects or lists more than [limit] levels deep. Iterative, so any depth is safe to check. */
    private fun nestedDeeperThan(root: JsonElement, limit: Int): Boolean {
        val todo = ArrayDeque(listOf(root to 1))
        while (todo.isNotEmpty()) {
            val (element, depth) = todo.removeLast()
            val children = when (element) {
                is JsonObject -> element.values
                is JsonArray -> element
                else -> continue
            }
            if (depth > limit) return true
            children.forEach { todo.addLast(it to depth + 1) }
        }
        return false
    }
}
