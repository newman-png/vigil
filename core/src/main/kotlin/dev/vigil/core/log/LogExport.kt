package dev.vigil.core.log

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Facts about the export itself, written next to the events. */
data class ExportMeta(
    val exportedAt: String,
    val appVersion: String,
    val timezone: String,
    val planWeekOf: String?,
    val planExpires: String?,
    val planGeneratedBy: String?,
    /** Where the exported events start (e.g. when the current plan was imported); null = whole log. */
    val since: String?,
    /** App settings a planner may need to read the log correctly (quiet override allowed, etc.). */
    val settings: Map<String, String>,
)

/** Builds log.json: a self-describing file a planner AI can read with no other context. */
object LogExport {
    const val SCHEMA_VERSION = 1

    val README: String = """
        This is log.json, exported by VIGIL, an Android app that executes a weekly plan (plan.json) and records what happened.
        The app never plans or decides anything: it fires the plan's directives when their triggers happen (time of day,
        the adhan app's prayer notification, or opening certain apps too often), shows a full-screen takeover with the
        directive's checklist, and logs the outcome. Each step can only be ticked after its min_seconds; the takeover
        stays until every step is ticked, or until the user holds the dismiss button for about 10 seconds (logged as
        directive_dismissed, never dropped). A takeover closes itself after ${dev.vigil.core.engine.Engine.MAX_TAKEOVER_MINUTES} minutes (directive_timed_out).
        Quiet mode (a manual switch) holds "queue" directives until it ends, shows "silent" ones without sound, skips
        "ignore" ones, and lets tier-3 "override" ones ring if the app setting allows it.
        "I'm not okay today" mode keeps only the plan's bad_day.keep directives until the day boundary. Events caused by it
        (skipped_bad_day, bypassed_bad_day) are NOT failures and should be read with compassion, not as weakness.
        When the plan's expires time passes, the app runs the plan's fallback_plan, or a built-in minimal plan
        (prayer, water, sleep) if there is none.
        Use this log together with vision.md and the last plan.json to write next week's plan.json (see HANDOFF.md in the
        VIGIL repository). "events" is in time order; "summary" counts outcomes per directive id; "event_types" explains every type.
    """.trimIndent().replace("\n", " ")

    fun build(events: List<LogEvent>, meta: ExportMeta): String {
        val root = buildJsonObject {
            put("_readme", README)
            put("schema_version", SCHEMA_VERSION)
            put("exported_at", meta.exportedAt)
            put("app_version", meta.appVersion)
            put("timezone", meta.timezone)
            putJsonObject("plan") {
                put("week_of", meta.planWeekOf)
                put("expires", meta.planExpires)
                put("generated_by", meta.planGeneratedBy)
            }
            put("since", meta.since)
            putJsonObject("settings") { meta.settings.forEach { (k, v) -> put(k, v) } }
            put("event_types", JsonObject(EventType.DESCRIPTIONS.mapValues { JsonPrimitive(it.value) }))
            put("summary", summary(events))
            put("events", JsonArray(events.map { LogJson.json.encodeToJsonElement(LogEvent.serializer(), it) }))
        }
        return PrettyJson.encode(root)
    }

    /** Per-directive counts of each outcome, plus the average completion time. Counting only, no judgement. */
    fun summary(events: List<LogEvent>): JsonObject {
        val byId = events.filter { it.directiveId != null }.groupBy { it.directiveId!! }
        return JsonObject(
            byId.toSortedMap().mapValues { (_, list) ->
                val counts = list.groupingBy { it.type }.eachCount()
                val completed = list.filter { it.type == EventType.DIRECTIVE_COMPLETED }.mapNotNull { it.durationS }
                buildJsonObject {
                    for (type in OUTCOME_TYPES) put(type, counts[type] ?: 0)
                    if (completed.isNotEmpty()) put("avg_completion_s", completed.average().toLong())
                }
            },
        )
    }

    private val OUTCOME_TYPES = listOf(
        EventType.DIRECTIVE_FIRED,
        EventType.DIRECTIVE_COMPLETED,
        EventType.DIRECTIVE_DISMISSED,
        EventType.DIRECTIVE_TIMED_OUT,
        EventType.BYPASSED_BAD_DAY,
        EventType.SKIPPED_BAD_DAY,
        EventType.QUEUED_QUIET,
        EventType.SKIPPED_QUIET,
        EventType.MISSED,
    )
}

/** Pretty printing keeps the file readable for humans and AIs. */
internal object PrettyJson {
    private val json = kotlinx.serialization.json.Json { prettyPrint = true }

    fun encode(element: JsonObject): String = json.encodeToString(JsonObject.serializer(), element) + "\n"
}
