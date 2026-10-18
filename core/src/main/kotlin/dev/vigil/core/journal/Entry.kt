package dev.vigil.core.journal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One line of the v2 journal (design 2.3), stored as JSON Lines and exported as CSV and inside the report.
 * Fields that do not apply to an entry are left out.
 */
@Serializable
data class Entry(
    /** Local time with offset and seconds, e.g. 2026-10-19T05:12:03+02:00. Always the first field ([JournalJson]). */
    val ts: String,
    /** One of [EntryType]. */
    val type: String,
    /** The protocol id. */
    val protocol: String? = null,
    /** The run id: groups one run's entries (fired, steps, then how it ended). */
    val run: String? = null,
    /** Step index from 0. */
    val step: Int? = null,
    /** full, short, minimal or single. */
    val version: String? = null,
    /** 1 forced, 2 guided, 3 reminder. */
    val stage: Int? = null,
    /** A package name (pool entries). */
    val app: String? = null,
    val minutes: Int? = null,
    /** Exact length in seconds where minutes are rounded. */
    val seconds: Long? = null,
    /** A machine-readable reason: day end reason, block reason, trigger source, exit reason ... */
    val reason: String? = null,
    /** Plain-language detail. */
    val detail: String? = null,
    /** Text the user typed (text steps). */
    val text: String? = null,
    /** The day key (yyyy-MM-dd) for day_start and day_end. */
    val day: String? = null,
    /** fajr, dhuhr, asr, maghrib or isha. */
    val prayer: String? = null,
    /** When a lock, block or suspension ends (same format as [ts]). */
    val until: String? = null,
    /** The planned moment (fired, not_fired, alarm_late; same format as [ts]). */
    val scheduled: String? = null,
    /** True for entries of a TEST-screen preview run: kept in the journal, never counted in the report. */
    val test: Boolean? = null,
)

/** Every entry type the v2 core writes, with a description for the report's legend. */
object EntryType {
    const val DAY_START = "day_start"
    const val DAY_END = "day_end"
    const val FIRED = "fired"
    const val STEP_DONE = "step_done"
    const val STEPPED_DOWN = "stepped_down"
    const val COMPLETED = "completed"
    const val BAILED = "bailed"
    const val RELEASED = "released"
    const val LOCK_ENDED = "lock_ended"
    const val MISSED = "missed"
    const val NOT_FIRED = "not_fired"
    const val SKIPPED = "skipped"
    const val DROPPED = "dropped"
    const val CONFIRMED = "confirmed"
    const val STAGE_UP = "stage_up"
    const val STAGE_DOWN = "stage_down"
    const val POOL_WAIT = "pool_wait"
    const val POOL_WAIT_ABANDONED = "pool_wait_abandoned"
    const val POOL_OPEN = "pool_open"
    const val POOL_SESSION = "pool_session"
    const val POOL_BLOCKED = "pool_blocked"
    const val HEAVY_USE = "heavy_use"
    const val COOLDOWN = "cooldown"
    const val PENALTY = "penalty"
    const val CALL_PAUSE = "call_pause"
    const val CALL_RESUME = "call_resume"
    const val TOGGLE = "toggle"
    const val QUIET = "quiet"
    const val ADHAN = "adhan"
    const val HEADS_UP = "heads_up"
    const val CLASS_CARD = "class_card"
    const val WINDOW_WARNING = "window_warning"
    const val KILL_SWITCH = "kill_switch"
    const val SPEED_BUMP = "speed_bump"
    const val APP_EXIT = "app_exit"
    const val ALARM_LATE = "alarm_late"
    const val MY_TIMES = "my_times"
    const val PAUSED = "paused"
    const val RESUMED = "resumed"
    const val SLEEP = "sleep"
    const val NIGHT_MISSED = "night_missed"
    const val SLEEP_TARGET = "sleep_target"
    const val NIGHT_LOCK = "night_lock"
    const val WAKE_ALARM = "wake_alarm"
    const val WAKE = "wake"
    const val WAKE_ALARM_UNANSWERED = "wake_alarm_unanswered"
    const val TODO_SET = "todo_set"
    const val TODO_DONE = "todo_done"

    val DESCRIPTIONS: Map<String, String> = linkedMapOf(
        DAY_START to "A day started (day = its key; reason = how the previous day ended, or first_start).",
        DAY_END to "A day ended (reason sleep_tap, idle, wake_alarm or fallback). Counters reset.",
        FIRED to "A protocol started (reason = what triggered it, scheduled = the planned moment, stage, version). " +
            "Detail says when it waits behind another takeover or shows only a card.",
        STEP_DONE to "A step was finished (step from 0, detail = its text, text = what was typed).",
        STEPPED_DOWN to "\"I'M NOT OKAY\": a shorter version (version), or a prayer lock shortened to its minimum. Not a bail-out.",
        COMPLETED to "A run finished (minutes since it fired; reason clean or done = not clean for promotion).",
        BAILED to "A lock or run was quit early (10 s hold on LEAVE). The pool penalty follows (see penalty).",
        RELEASED to "A prayer lock was left after its minimum: no penalty, the prayer stays open for confirmation.",
        LOCK_ENDED to "A run's lock ended before it was finished: the screen is free, the run waits (prayer: CONFIRM on home).",
        MISSED to "A run ended unfinished (reason day_end, replaced = its protocol started again, prayer: window_closed or " +
            "next_adhan, unconfirmed; an unconfirmed stage-3 reminder too). Counts for stages.",
        NOT_FIRED to "Daily self-check: a planned start that never fired (scheduled = when it was due); reason wake_alarm = " +
            "a wake alarm that never rang (the phone was off).",
        SKIPPED to "A trigger happened but nothing started (reason: suspended, already_running, day_end = set off while the " +
            "day ended; sleep_tap = GOING TO SLEEP before the day's wake time, the day goes on; wake_alarm = a dismissal with no " +
            "alarm ringing).",
        DROPPED to "A run was closed without an outcome (reason: kill_switch, day_end for a test run, protocol_removed).",
        CONFIRMED to "A prayer was confirmed (Done, or CONFIRM on home / the card).",
        STAGE_UP to "Promoted one stage after enough clean completions in a row (stage = the new stage).",
        STAGE_DOWN to "Moved back one stage after too many misses or bail-outs in the recent outcomes (stage = the new stage).",
        POOL_WAIT to "A pool wait screen started (minutes = its length).",
        POOL_WAIT_ABANDONED to "The wait screen was left before the end; the same wait applies next time.",
        POOL_OPEN to "A wait completed and a pool session started (minutes = the wait; detail = open number today).",
        POOL_SESSION to "A pool session ended (minutes/seconds used, app = the last pool app).",
        POOL_BLOCKED to "A pool app was refused or closed (reason = why, until = when that reason ends).",
        HEAVY_USE to "Heavy use: reason continuous (a long session) or opens (too many opens in the span). The app was closed.",
        COOLDOWN to "A cooldown started (minutes, until; reason session or heavy_use).",
        PENALTY to "Bail-out penalty added to the pool lock (minutes added, until; 0 when the daily cap was reached).",
        CALL_PAUSE to "A call connected: protocol timers and locks are paused.",
        CALL_RESUME to "The call ended: timers and locks were extended by its length (minutes/seconds). Detail when no end " +
            "was reported and the pause ended by itself after 3 hours.",
        TOGGLE to "A home switch changed (reason = walking or friends, detail on/off; friends also goes off at its cap).",
        QUIET to "QUIET mode switched (detail on/off): takeovers are silent while it is on.",
        ADHAN to "The adhan notification arrived for prayer.",
        HEADS_UP to "The subtle heads-up before a prayer (scheduled = the expected adhan).",
        CLASS_CARD to "A prayer's adhan fell in a class: silent card; the takeover starts when the class ends.",
        WINDOW_WARNING to "A prayer window is about to close unconfirmed: the takeover came back.",
        KILL_SWITCH to "VIGIL suspended (minutes, until) or resumed (detail).",
        SPEED_BUMP to "The speed bump covered a settings page (app/detail = which).",
        APP_EXIT to "The app was killed or crashed since the last start (reason from ApplicationExitInfo).",
        ALARM_LATE to "An alarm fired late (scheduled = planned, seconds late).",
        MY_TIMES to "MY TIMES were edited (detail: applies from tomorrow, or now on first setup).",
        PAUSED to "A prayer took the screen from a running protocol: its timers stop until the prayer's lock is over.",
        RESUMED to "A paused protocol went on where it was (minutes = how long it was paused).",
        SLEEP to "Gone to sleep (reason sleep_tap = GOING TO SLEEP, idle = the moment the phone was put down; " +
            "scheduled = tonight's target bedtime; minutes = minutes after it, negative = earlier).",
        NIGHT_MISSED to "A missed night for sleep shifting (reason bailed = the wind-down was left, no_sleep_tap = the day " +
            "ended without GOING TO SLEEP, late = more than late_min after the target).",
        SLEEP_TARGET to "Tonight's target bedtime (scheduled; minutes = how much earlier than last night's; reason start, " +
            "shifted, held = after missed nights in a row, goal = already at the goal, clamped = moved into new MY TIMES).",
        NIGHT_LOCK to "The night lock started (reason sleep_tap, idle or fallback; until = its latest end) or ended (reason " +
            "wake, unlock, timeout = wake + night_lock_grace_min, left = LEAVE, kill_switch, day_end; minutes = its length).",
        WAKE_ALARM to "The wake alarm rang (scheduled = MY TIMES wake; detail = which ring).",
        WAKE to "Woke up: the wake alarm was dismissed (reason dismissed), or the phone was unlocked after it was never " +
            "answered (reason unlock). Scheduled = the alarm time; minutes = minutes after it.",
        WAKE_ALARM_UNANSWERED to "The wake alarm rang max_rings times without an answer and gave up; the morning starts at " +
            "the next unlock.",
        TODO_SET to "Tomorrow's to-dos were written in the wind-down (day = the day they are for; text = one per line).",
        TODO_DONE to "A to-do was ticked off (reason done) or unticked (reason undone); text = the to-do.",
    )
}

/** JSON Lines encoding of the journal, tolerant of damaged lines (a write cut short by a crash). */
object JournalJson {
    val json = Json {
        explicitNulls = false
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    /** Every encoded line starts with this, because [Entry.ts] is the first field. */
    private const val LINE_START = "{\"ts\""

    fun encodeLine(entry: Entry): String = json.encodeToString(Entry.serializer(), entry)

    /** Several entries, one per line, with a trailing newline (ready to append). */
    fun encodeLines(entries: List<Entry>): String = entries.joinToString("") { encodeLine(it) + "\n" }

    /** Parses JSON Lines, skipping damaged lines but keeping any entry that follows junk on the same line. */
    fun decodeLines(text: String): List<Entry> = text.lineSequence()
        .filter { it.isNotBlank() }
        .flatMap(::decodeLine)
        .toList()

    private fun decodeLine(line: String): List<Entry> {
        decodeOrNull(line)?.let { return listOf(it) }
        val starts = Regex.fromLiteral(LINE_START).findAll(line).map { it.range.first }.toList()
        return starts.mapIndexedNotNull { i, start ->
            decodeOrNull(line.substring(start, starts.getOrElse(i + 1) { line.length }))
        }
    }

    private fun decodeOrNull(text: String): Entry? = runCatching { json.decodeFromString(Entry.serializer(), text) }.getOrNull()
}
