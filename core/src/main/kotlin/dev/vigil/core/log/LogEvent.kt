package dev.vigil.core.log

import dev.vigil.core.engine.Engine
import dev.vigil.core.engine.TimeSchedule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One line of the event log. Stored as JSON Lines on the phone and exported inside log.json.
 * Fields that do not apply to an event type are left out.
 */
@Serializable
data class LogEvent(
    /** Local time with offset, e.g. 2026-10-19T05:12:03+01:00. Always the first field (see [LogJson.decodeLines]). */
    val ts: String,
    val type: String,
    @SerialName("directive_id") val directiveId: String? = null,
    val tier: Int? = null,
    /** What fired it: time, adhan, app_usage, quiet_queue (released when quiet mode ended) or debug (test button). */
    val trigger: String? = null,
    /** Groups the events of one takeover (fired, steps, then how it closed). */
    @SerialName("takeover_id") val takeoverId: String? = null,
    /** For time triggers: the planned moment (same format as [ts]), which can be earlier than [ts] if the phone was late. */
    @SerialName("scheduled_for") val scheduledFor: String? = null,
    @SerialName("step_index") val stepIndex: Int? = null,
    @SerialName("step_text") val stepText: String? = null,
    /** Seconds: for steps, since the step appeared; for events that close a takeover, since it fired. */
    @SerialName("duration_s") val durationS: Long? = null,
    @SerialName("steps_done") val stepsDone: Int? = null,
    @SerialName("steps_total") val stepsTotal: Int? = null,
    /** True when shown without sound or vibration (quiet mode). */
    val silent: Boolean? = null,
    val detail: String? = null,
)

/** Every event type the app writes. Listed in log.json's event_types so a planner can read the log cold. */
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
    const val REPEATED = "repeated"
    const val QUIET_ON = "quiet_on"
    const val QUIET_OFF = "quiet_off"
    const val BAD_DAY_ON = "bad_day_on"
    const val BAD_DAY_OFF = "bad_day_off"

    val DESCRIPTIONS: Map<String, String> = linkedMapOf(
        PLAN_IMPORTED to "A plan was imported and became active (detail: week_of and generated_by).",
        PLAN_IMPORT_FAILED to "An import was rejected; the previous plan stayed active (detail: the first error).",
        PLAN_EXPIRED to "The plan's expires time passed; the app now runs its fallback_plan or, without one, " +
            "the built-in minimal plan (detail says which).",
        DIRECTIVE_FIRED to "A trigger happened and a takeover opened (trigger, scheduled_for, silent, steps_total). " +
            "If another takeover was on screen, this one waited for it to close.",
        STEP_COMPLETED to "A checklist step was ticked (step_index from 0, step_text, duration_s since the step appeared).",
        DIRECTIVE_COMPLETED to "Every step was ticked (duration_s since it fired).",
        DIRECTIVE_DISMISSED to "Closed early by holding the dismiss button for about 10 seconds " +
            "(steps_done of steps_total, duration_s since it fired).",
        DIRECTIVE_TIMED_OUT to "Still open ${Engine.MAX_TAKEOVER_MINUTES} minutes after it fired, so it closed itself " +
            "(steps_done of steps_total).",
        BYPASSED_BAD_DAY to "Closed while showing or waiting because the user switched on \"I'm not okay today\" " +
            "and it is not in bad_day.keep. Not a failure.",
        SKIPPED_BAD_DAY to "Not shown because \"I'm not okay today\" was on and it is not in bad_day.keep. Not a failure.",
        QUEUED_QUIET to "Triggered during quiet mode (quiet: queue): held, and fired when quiet mode ends.",
        SKIPPED_QUIET to "Not shown because quiet mode was on (quiet: ignore).",
        MISSED to "A time trigger's moment passed more than ${TimeSchedule.DEFAULT_GRACE_MINUTES} minutes before the " +
            "app could act (phone off or asleep), so it was not shown late (scheduled_for is the planned moment).",
        DROPPED to "A takeover (showing or waiting) closed without a result, with the reason in detail: its directive is no " +
            "longer in the running plan (after a new import or the switch to the fallback plan), quiet mode was switched " +
            "on while it waited (quiet: queue or ignore), or the developer kill switch was used.",
        REPEATED to "The directive fired again while it was already showing or waiting, so it was not opened a second time.",
        QUIET_ON to "Quiet mode switched on.",
        QUIET_OFF to "Quiet mode switched off; directives held by it fire now.",
        BAD_DAY_ON to "\"I'm not okay today\" switched on; it lasts until the day boundary (detail).",
        BAD_DAY_OFF to "\"I'm not okay today\" ended (detail: switched off by the user, or the day ended).",
    )
}

object LogJson {
    val json = Json {
        explicitNulls = false
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    /** Every encoded line starts with this, because [LogEvent.ts] is the first field. */
    private const val LINE_START = "{\"ts\""

    fun encodeLine(event: LogEvent): String = json.encodeToString(LogEvent.serializer(), event)

    /** Parses JSON Lines, skipping lines that are damaged (e.g. a write cut short by a crash). */
    fun decodeLines(text: String): List<LogEvent> = text.lineSequence()
        .filter { it.isNotBlank() }
        .flatMap(::decodeLine)
        .toList()

    /**
     * Usually one event. When the line is damaged, each piece that starts like an event is tried on its own,
     * so an event appended after a half-written one (or after junk left by a power cut) is not lost with it.
     */
    private fun decodeLine(line: String): List<LogEvent> {
        decodeOrNull(line)?.let { return listOf(it) }
        val starts = Regex.fromLiteral(LINE_START).findAll(line).map { it.range.first }.toList()
        return starts.mapIndexedNotNull { i, start ->
            decodeOrNull(line.substring(start, starts.getOrElse(i + 1) { line.length }))
        }
    }

    private fun decodeOrNull(text: String): LogEvent? =
        runCatching { json.decodeFromString(LogEvent.serializer(), text) }.getOrNull()
}
