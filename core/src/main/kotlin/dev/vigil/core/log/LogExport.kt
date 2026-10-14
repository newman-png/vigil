package dev.vigil.core.log

import dev.vigil.core.engine.Engine
import dev.vigil.core.engine.TimeSchedule
import dev.vigil.core.plan.Plan
import dev.vigil.core.plan.Rating
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToLong

/** Facts about a v1 log.json export, written next to the events. The weekly bundle uses [BundleMeta]. */
data class ExportMeta(
    val exportedAt: String,
    val appVersion: String,
    val timezone: String,
    val planWeekOf: String?,
    val planExpires: String?,
    val planGeneratedBy: String?,
    /** Where the exported events start (e.g. when the current plan was imported); null = whole log. */
    val since: String?,
    /** App settings a planner may need to read the log correctly, e.g. [LogExport.SETTING_ALLOW_QUIET_OVERRIDE]. */
    val settings: Map<String, String>,
)

/**
 * What every export shares ([summary], [TEST_TRIGGERS], [SETTING_ALLOW_QUIET_OVERRIDE]), and the v1 export, log.json
 * ([build]). The weekly bundle ([BundleExport]) is the app's only export now; [build], [ExportMeta] and [README] stay
 * only until the app's old log screen is gone.
 */
object LogExport {
    const val SCHEMA_VERSION = 1

    /** Settings key the readme refers to: "true" when tier-3 "override" directives may ring in quiet mode. */
    const val SETTING_ALLOW_QUIET_OVERRIDE = "allow_quiet_override"

    val README: String = """
        This is log.json, exported by VIGIL, an Android app that executes a weekly plan (plan.json) written by a
        planner AI, and records what happened. The app never plans, judges or decides anything; all intelligence
        lives in the plan.
        HOW THE APP BEHAVES. Each directive has a trigger: a time of day in the phone's local time ("time"), the
        user's adhan app posting its prayer notification ("adhan"; the app does not compute prayer times), or
        listed apps being opened too often or used too long in one go ("app_usage"). When it triggers, the phone
        shows a full-screen takeover with the directive's checklist. Steps appear one at a time, and each can only
        be ticked once its min_seconds have passed. The takeover closes when every step is ticked
        (directive_completed), when the user holds the dismiss button for about 10 seconds (directive_dismissed),
        or by itself after ${Engine.MAX_TAKEOVER_MINUTES} minutes as the active takeover (directive_timed_out). One
        takeover shows at a time; others wait their turn, and a directive that is already showing or waiting is not
        opened twice (the repeat is logged as repeated).
        A time trigger the phone could not act on within ${TimeSchedule.DEFAULT_GRACE_MINUTES} minutes of its moment
        (phone off or asleep) is logged as missed instead of being shown late.
        Quiet mode is a manual switch. While it is on, each directive follows its "quiet" field: queue = held
        (queued_quiet) and fired once when quiet mode ends, with trigger "quiet_queue"; silent = shown without sound or
        vibration ("silent": true); ignore = not shown (skipped_quiet); override = tier 3 only, rings anyway if
        settings.$SETTING_ALLOW_QUIET_OVERRIDE is "true", otherwise shown silently. Switching quiet mode on also
        applies to takeovers that already fired: the one on screen goes silent, and waiting ones follow their rule
        (a held or skipped one closes as dropped, and a held one is logged queued_quiet too).
        "I'm not okay today" mode is switched on by the user and lasts until the next day boundary (the plan's
        day_boundary, ${Plan.DEFAULT_DAY_BOUNDARY} by default). Meanwhile only the directives in bad_day.keep fire: the others are logged
        as skipped_bad_day, and a takeover showing or waiting when it was switched on closes as bypassed_bad_day.
        These are NOT failures; the user was honest about a hard day. Read them with compassion, never as weakness.
        A bad day ends when the user taps "I'm okay again" (bad_day_off) or by itself at the day boundary (day_mode). If the
        plan has after_bad_day, the morning after a bad or minimum day the user answers a check-in (check_in: better,
        same or worse) that picks the day's mode: normal, bad_day, or minimum, where only minimum.keep fires
        (skipped_minimum, bypassed_minimum: NOT failures either). Until it is answered its "same" answer applies.
        After a completion the user may rate the directive EASY, OK or HARD (rated, rating_skipped), as its rating field
        says. With adapt.step_down_after_hard, that many HARD ratings in a row switch it to its variants.easier for the
        rest of the plan (stepped_down; later events of it carry variant "easier").
        When the plan's expires time passes, the app logs plan_expired and runs the plan's fallback_plan, or, if
        there is none, a built-in minimal plan (prayers, water, sleep; its directive ids start with "default-").
        On a bad or minimum day the fallback_plan keeps all its directives; the built-in plan keeps only its own essentials
        (prayers and sleep).
        HOW TO READ THIS FILE. "events" lists what happened in time order, starting at "since" (null = the whole
        log). ts is the phone's local time with its UTC offset ("timezone" names the zone). Fields that do not
        apply to an event are left out. takeover_id groups one takeover's events: directive_fired, its
        step_completed events, then the event that closed it. step_index counts from 0. duration_s is in seconds:
        for step_completed, since that step appeared; for the closing event, since the directive fired, including
        any time it waited behind another takeover. scheduled_for is the planned moment of a time trigger; ts can
        be later. tier is 1 (nudge), 2 (short checklist) or 3 (full sequence). Triggers "debug" and "adhan_test"
        and the demo directives (ids starting with "${Engine.DEMO_ID}") are the user testing the app: ignore them.
        "summary" counts outcomes per directive id and gives avg_completion_s; it counts, it does not judge. It leaves out
        test triggers and the app's own directives (ids starting with "${Engine.BUILTIN_ID_PREFIX}", such as the check-in
        "${Engine.CHECK_IN_ID}"). "event_types" explains every event type. "plan"
        identifies the last imported plan. "settings" lists app settings that change behaviour.
        Use this log together with vision.md and the last plan.json to write next week's plan.json (see HANDOFF.md
        in the VIGIL repository). Treat every outcome as information for a better plan, not as grounds for blame.
    """.trimIndent().replace('\n', ' ')

    /** log.json, the v1 export: a self-describing file a planner AI can read with no other context. */
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

    /**
     * Per directive id: the count of each outcome, then of each rating answer (rated_easy, rated_ok, rated_hard,
     * rating_skipped) and of the minimum-day skips and bypasses, plus the average completion time. Counting only,
     * no judgement. Once every takeover has closed, directive_fired equals the sum of the outcomes that close one
     * (completed, dismissed, timed out, bypassed on a bad or a minimum day, dropped).
     * Test fires (debug buttons, the adhan test) are left out, whole takeovers included, and so are the app's own
     * directives (the demos and the check-in, ids starting with [Engine.BUILTIN_ID_PREFIX]). Event types it does not
     * count (check-ins, step-downs, ...) are ignored.
     */
    fun summary(events: List<LogEvent>): JsonObject {
        val byId = withoutTests(events)
            .filter { it.directiveId?.startsWith(Engine.BUILTIN_ID_PREFIX) == false }
            .groupBy { it.directiveId!! }
        return JsonObject(
            byId.toSortedMap().mapValues { (_, list) ->
                val counts = list.groupingBy { it.type }.eachCount()
                val rated = list.filter { it.type == EventType.RATED }.groupingBy { it.rating }.eachCount()
                val completed = list.filter { it.type == EventType.DIRECTIVE_COMPLETED }.mapNotNull { it.durationS }
                buildJsonObject {
                    for (type in OUTCOME_TYPES) put(type, counts[type] ?: 0)
                    for (rating in Rating.entries) put("rated_${rating.json}", rated[rating.json] ?: 0)
                    for (type in LATER_TYPES) put(type, counts[type] ?: 0)
                    if (completed.isNotEmpty()) put("avg_completion_s", completed.average().roundToLong())
                }
            },
        )
    }

    /**
     * [events] without the user's try-outs: every event with a test trigger ([TEST_TRIGGERS]), and every other event of
     * the same takeover (its steps, its rating), so a test fire never reads as real use.
     */
    internal fun withoutTests(events: List<LogEvent>): List<LogEvent> {
        val testTakeovers = events.filter { it.trigger in TEST_TRIGGERS }.mapNotNull { it.takeoverId }.toSet()
        return events.filter { it.trigger !in TEST_TRIGGERS && it.takeoverId !in testTakeovers }
    }

    /** Counted after the rated_* columns (they came with v1.1). */
    private val LATER_TYPES = listOf(
        EventType.RATING_SKIPPED,
        EventType.SKIPPED_MINIMUM,
        EventType.BYPASSED_MINIMUM,
    )

    /** The v1 columns, in their v1 order. */
    private val OUTCOME_TYPES = listOf(
        EventType.DIRECTIVE_FIRED,
        EventType.DIRECTIVE_COMPLETED,
        EventType.DIRECTIVE_DISMISSED,
        EventType.DIRECTIVE_TIMED_OUT,
        EventType.BYPASSED_BAD_DAY,
        EventType.DROPPED,
        EventType.SKIPPED_BAD_DAY,
        EventType.QUEUED_QUIET,
        EventType.SKIPPED_QUIET,
        EventType.MISSED,
        EventType.REPEATED,
    )

    /** Triggers that mean the user was trying the app out, not living the plan. */
    val TEST_TRIGGERS = setOf("debug", "adhan_test")
}

/** Pretty printing keeps the file readable for humans and AIs. */
internal object PrettyJson {
    private val json = Json { prettyPrint = true }

    fun encode(element: JsonObject): String = json.encodeToString(JsonObject.serializer(), element) + "\n"
}
