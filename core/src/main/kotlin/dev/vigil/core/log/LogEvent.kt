package dev.vigil.core.log

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One line of the event log. Stored as JSON Lines on the phone and exported inside log.json.
 * Fields that do not apply to an event type are left out.
 */
@Serializable
data class LogEvent(
    /** Local time with offset, e.g. 2026-10-19T05:12:03+01:00. */
    val ts: String,
    val type: String,
    @SerialName("directive_id") val directiveId: String? = null,
    val tier: Int? = null,
    /** What fired it: time, adhan, app_usage, quiet_queue, debug. */
    val trigger: String? = null,
    /** Groups the events of one takeover (fired, steps, completed/dismissed). */
    @SerialName("takeover_id") val takeoverId: String? = null,
    /** For time triggers: the planned moment, which can differ from [ts] if the phone was late. */
    @SerialName("scheduled_for") val scheduledFor: String? = null,
    @SerialName("step_index") val stepIndex: Int? = null,
    @SerialName("step_text") val stepText: String? = null,
    /** Seconds: for steps, since the step appeared; for completed/dismissed, since the takeover fired. */
    @SerialName("duration_s") val durationS: Long? = null,
    @SerialName("steps_done") val stepsDone: Int? = null,
    @SerialName("steps_total") val stepsTotal: Int? = null,
    /** True when shown without sound or vibration (quiet mode). */
    val silent: Boolean? = null,
    val detail: String? = null,
)

/** Every event type the app writes. Listed in log.json's _readme so a planner can read the log cold. */
object EventType {
    const val PLAN_IMPORTED = "plan_imported"
    const val PLAN_IMPORT_FAILED = "plan_import_failed"
    const val PLAN_EXPIRED = "plan_expired"
    const val DIRECTIVE_FIRED = "directive_fired"
    const val STEP_COMPLETED = "step_completed"
    const val DIRECTIVE_COMPLETED = "directive_completed"
    const val DIRECTIVE_DISMISSED = "directive_dismissed"
    const val DIRECTIVE_TIMED_OUT = "directive_timed_out"
    const val BYPASSED_BAD_DAY = "bypassed_bad_day"
    const val SKIPPED_BAD_DAY = "skipped_bad_day"
    const val QUEUED_QUIET = "queued_quiet"
    const val SKIPPED_QUIET = "skipped_quiet"
    const val MISSED = "missed"
    const val DROPPED = "dropped"
    const val QUIET_ON = "quiet_on"
    const val QUIET_OFF = "quiet_off"
    const val BAD_DAY_ON = "bad_day_on"
    const val BAD_DAY_OFF = "bad_day_off"

    val DESCRIPTIONS: Map<String, String> = linkedMapOf(
        PLAN_IMPORTED to "A plan was imported and became active (detail: week_of and generated_by).",
        PLAN_IMPORT_FAILED to "An import was rejected; the previous plan stayed active (detail: first error).",
        PLAN_EXPIRED to "The plan's expires time passed; the app switched to fallback_plan or the built-in minimal plan (detail).",
        DIRECTIVE_FIRED to "A takeover appeared (trigger, scheduled_for, silent).",
        STEP_COMPLETED to "A checklist step was ticked (step_index from 0, duration_s since the step appeared).",
        DIRECTIVE_COMPLETED to "Every step was ticked (duration_s since it fired).",
        DIRECTIVE_DISMISSED to "Abandoned by holding the dismiss button ~10 s (steps_done of steps_total).",
        DIRECTIVE_TIMED_OUT to "Still open after the hard maximum duration, so it closed itself.",
        BYPASSED_BAD_DAY to "Closed because the user switched on \"I'm not okay today\" while it was showing. Not a failure.",
        SKIPPED_BAD_DAY to "Did not fire because of \"I'm not okay today\" mode (not in bad_day.keep). Not a failure.",
        QUEUED_QUIET to "Held during quiet mode (quiet: queue); it fires when quiet mode ends.",
        SKIPPED_QUIET to "Did not fire because quiet mode was on (quiet: ignore).",
        MISSED to "A time trigger passed while the phone was off or asleep for too long, so it was not shown.",
        DROPPED to "Removed before showing, e.g. a newer plan no longer contains it (detail).",
        QUIET_ON to "Quiet mode switched on.",
        QUIET_OFF to "Quiet mode switched off.",
        BAD_DAY_ON to "\"I'm not okay today\" switched on until the end of the day.",
        BAD_DAY_OFF to "\"I'm not okay today\" switched off (by the user or because the day ended).",
    )
}

object LogJson {
    val json = Json {
        explicitNulls = false
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    fun encodeLine(event: LogEvent): String = json.encodeToString(LogEvent.serializer(), event)

    /** Parses JSON Lines, skipping lines that are damaged (e.g. a write cut short by a crash). */
    fun decodeLines(text: String): List<LogEvent> = text.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line -> runCatching { json.decodeFromString(LogEvent.serializer(), line) }.getOrNull() }
        .toList()
}
