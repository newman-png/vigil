package dev.vigil.core.engine

import dev.vigil.core.log.EventType
import dev.vigil.core.log.LogEvent
import dev.vigil.core.log.LogExport
import dev.vigil.core.log.LogJson
import dev.vigil.core.plan.CheckInAnswer
import dev.vigil.core.plan.DayMode
import dev.vigil.core.plan.Directive
import dev.vigil.core.plan.QuietPolicy
import dev.vigil.core.plan.Rating
import dev.vigil.core.plan.RatingPolicy
import dev.vigil.core.plan.Step
import dev.vigil.core.plan.Trigger
import dev.vigil.core.plan.Variant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** A directive that has fired and is showing, or waiting for the one before it to finish. */
@Serializable
data class Ticket(
    @SerialName("takeover_id") val takeoverId: String,
    @SerialName("directive_id") val directiveId: String,
    val trigger: String,
    val silent: Boolean,
    @SerialName("fired_at_ms") val firedAtMs: Long,
    @SerialName("scheduled_for") val scheduledFor: String? = null,
    /** Steps ticked so far. */
    @SerialName("steps_done") val stepsDone: Int = 0,
    /** When the current step appeared, for its min_seconds lock and duration. Null until shown. */
    @SerialName("step_shown_at_ms") val stepShownAtMs: Long? = null,
    /**
     * When it became the active takeover; null while pending. The timeout counts from here, so a takeover
     * that waited behind another still gets its full time on screen. Durations still count from [firedAtMs].
     */
    @SerialName("active_since_ms") val activeSinceMs: Long? = null,
    /** The variant this takeover runs ([Variant.EASIER] after a step-down), fixed when it fired. Null: as written. */
    val variant: String? = null,
)

/** A directive held during quiet mode (quiet: queue). */
@Serializable
data class Queued(
    @SerialName("directive_id") val directiveId: String,
    val trigger: String,
    @SerialName("queued_at_ms") val queuedAtMs: Long,
)

/** A day whose mode is not normal, and who chose that mode. */
@Serializable
data class DayModeState(
    /** The day key (yyyy-MM-dd) it applies to. */
    val day: String,
    /** The mode's [DayMode.json]: "bad_day" or "minimum". */
    val mode: String,
    /** [SOURCE_USER], [SOURCE_CHECK_IN] or [SOURCE_CHECK_IN_PENDING]. */
    val source: String,
) {
    companion object {
        /** The user switched on "I'm not okay today". */
        const val SOURCE_USER = "user"

        /** The answer to the morning check-in. */
        const val SOURCE_CHECK_IN = "check_in"

        /** The check-in's "same" answer, applied from the day boundary until the check-in is answered. */
        const val SOURCE_CHECK_IN_PENDING = "check_in_pending"
    }
}

/** The morning check-in after a bad or minimum day: due from the day boundary until it is answered or given up. */
@Serializable
data class CheckInState(
    /** The day key (yyyy-MM-dd) it is for. */
    val day: String,
    /** The [DayMode.json] of the day before ("bad_day" or "minimum"). */
    val after: String,
    /** True once its takeover has fired. It fires once, when the user is at the phone. */
    val fired: Boolean = false,
    /** The takeover that shows it, once fired. */
    @SerialName("takeover_id") val takeoverId: String? = null,
)

/** A completed takeover whose difficulty question (EASY / OK / HARD) is open. */
@Serializable
data class PendingRating(
    @SerialName("takeover_id") val takeoverId: String,
    @SerialName("directive_id") val directiveId: String,
    /** The variant the takeover ran; null when it ran the directive as written. */
    val variant: String? = null,
    val trigger: String,
    @SerialName("asked_at_ms") val askedAtMs: Long,
)

/** Everything the app remembers between triggers. Persisted as JSON. */
@Serializable
data class RuntimeState(
    val quiet: Boolean = false,
    /**
     * Only in state saved by v1: the day key on which "I'm not okay today" was switched on. [Engine.refresh] moves
     * it into [dayMode]; it is kept so that old state still loads.
     */
    @SerialName("bad_day") val badDay: String? = null,
    @SerialName("quiet_queue") val quietQueue: List<Queued> = emptyList(),
    /** The takeover on screen (or that should be on screen). */
    val active: Ticket? = null,
    /** Takeovers waiting for [active] to finish, oldest first. */
    val pending: List<Ticket> = emptyList(),
    /** Last moment the time schedule was processed up to (epoch ms). */
    @SerialName("schedule_cursor_ms") val scheduleCursorMs: Long? = null,
    /** The plan source last seen, so a switch to fallback/default is logged once. */
    @SerialName("last_source") val lastSource: String? = null,
    /** The day's mode when it is not normal. Read it with [Engine.dayMode]: a mode for an earlier day no longer applies. */
    @SerialName("day_mode") val dayMode: DayModeState? = null,
    /** The morning check-in, while it is due and unresolved. */
    @SerialName("check_in") val checkIn: CheckInState? = null,
    /** A completed takeover waiting for its difficulty rating (see [Engine.rate]). The UI asks while this matches it. */
    @SerialName("pending_rating") val pendingRating: PendingRating? = null,
    /** The [Env.planKey] the per-plan memory below belongs to. */
    @SerialName("plan_key") val planKey: String? = null,
    /** Real (non-test) completions per directive id in this plan; `rating: "sample"` counts them. */
    val completions: Map<String, Int> = emptyMap(),
    /**
     * The last [Engine.MAX_RECENT_RATINGS] ratings per directive id ([Rating.json]), for the step-down rule: this plan,
     * real (non-test) ratings of the directive as written only.
     */
    @SerialName("recent_ratings") val recentRatings: Map<String, List<String>> = emptyMap(),
    /** Directive ids that run their variants.easier for the rest of this plan. */
    @SerialName("stepped_down") val steppedDown: List<String> = emptyList(),
) {
    fun encode(): String = LogJson.json.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String?): RuntimeState =
            text?.let { runCatching { LogJson.json.decodeFromString(serializer(), it) }.getOrNull() } ?: RuntimeState()
    }
}

/** The world the engine runs in. */
data class Env(
    val now: Instant,
    val zone: ZoneId,
    val active: ActiveSet,
    /** App setting: may tier-3 directives marked quiet "override" ring during quiet mode? */
    val allowQuietOverride: Boolean,
    /**
     * Identifies the imported plan (the app passes its import time); null when there is none. When it changes, the
     * per-plan memory (completions, ratings, step-downs) starts again.
     */
    val planKey: String? = null,
    /** Makes takeover ids; injectable for tests. */
    val newId: () -> String,
) {
    val nowMs: Long get() = now.toEpochMilli()
    val localNow: LocalDateTime get() = LocalDateTime.ofInstant(now, zone)
    val today: String get() = ActivePlanResolver.dayKey(localNow, active.dayBoundary).toString()

    /** Always with seconds: OffsetDateTime.toString() would drop ":00" on the minute. */
    fun timestamp(): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.SECONDS))
}

/** The result of an engine step: the new state, what to log, and whether a takeover should be on screen. */
data class Outcome(val state: RuntimeState, val events: List<LogEvent>) {
    /** True when a takeover should be shown (the caller launches it if it is not already visible). */
    val showTakeover: Boolean get() = state.active != null
}

/** How a fired directive is treated, given quiet mode and the day's mode. */
sealed interface Decision {
    data class Fire(val silent: Boolean) : Decision
    data object Queue : Decision
    data class Skip(val eventType: String) : Decision
}

/**
 * All of the app's behaviour as pure functions over [RuntimeState]. The Android layer persists the state,
 * writes the events to the log and shows the takeover; it never decides anything itself.
 */
object Engine {
    /** A takeover closes itself after this long, so nothing can hold the phone indefinitely. */
    const val MAX_TAKEOVER_MINUTES = 90L

    /** The difficulty question stays open this long after a completion; then it counts as not answered. */
    const val RATING_WINDOW_MS = 30 * 60_000L

    /** How many ratings per directive are remembered for the step-down rule. */
    const val MAX_RECENT_RATINGS = 10

    /**
     * How a directive that just triggered is treated: on a bad day only bad_day.keep fires, on a minimum day only
     * minimum.keep (the rest is skipped, never a failure); then quiet mode applies the directive's quiet rule.
     */
    fun decide(directive: Directive, quiet: Boolean, mode: DayMode, active: ActiveSet, allowOverride: Boolean): Decision {
        val keep = keepFor(mode, active)
        if (keep != null && directive.id !in keep) return Decision.Skip(skippedType(mode))
        return quietDecision(directive, quiet, allowOverride)
    }

    /** The v1 form of [decide]: on a [badDay] only the directives in [keep] fire. */
    fun decide(directive: Directive, quiet: Boolean, badDay: Boolean, keep: Set<String>, allowOverride: Boolean): Decision {
        if (badDay && directive.id !in keep) return Decision.Skip(EventType.SKIPPED_BAD_DAY)
        return quietDecision(directive, quiet, allowOverride)
    }

    private fun quietDecision(directive: Directive, quiet: Boolean, allowOverride: Boolean): Decision {
        if (!quiet) return Decision.Fire(silent = false)
        return when (directive.quiet) {
            QuietPolicy.QUEUE -> Decision.Queue
            QuietPolicy.SILENT -> Decision.Fire(silent = true)
            QuietPolicy.IGNORE -> Decision.Skip(EventType.SKIPPED_QUIET)
            QuietPolicy.OVERRIDE -> Decision.Fire(silent = !(allowOverride && directive.tier == 3))
        }
    }

    /**
     * Today's mode: what "I'm not okay today" or the morning check-in chose for today, otherwise normal.
     * A mode saved for an earlier day no longer applies (the next [refresh] ends it).
     */
    fun dayMode(state: RuntimeState, env: Env): DayMode {
        // State saved by v1 keeps a bad day as a bare day key until the next refresh moves it.
        val m = state.dayMode ?: state.badDay?.let { DayModeState(it, DayMode.BAD_DAY.json, DayModeState.SOURCE_USER) }
        if (m == null || m.day != env.today) return DayMode.NORMAL
        return DayMode.fromJson(m.mode) ?: DayMode.NORMAL
    }

    /** True on a bad day: "I'm not okay today" is on, or the morning check-in chose bad_day. */
    fun isBadDay(state: RuntimeState, env: Env): Boolean = dayMode(state, env) == DayMode.BAD_DAY

    /**
     * Housekeeping to run before anything else, in this order: moves v1 bad-day state into the day mode; starts the
     * per-plan memory again for a new plan; gives up a check-in left from an earlier day; ends the day's mode at the
     * day boundary (making the morning check-in due after a bad or minimum day when the plan has after_bad_day);
     * notices a switch to the fallback/default plan; drops tickets and queued directives that no longer exist (or
     * whose directive changed so the ticked steps no longer fit); times out a takeover that has been open too long;
     * and gives up a difficulty question that has been open too long.
     */
    fun refresh(state: RuntimeState, env: Env): Outcome {
        val events = mutableListOf<LogEvent>()
        var s = state

        // 1. v1 kept "I'm not okay today" as a bare day key.
        val v1BadDay = s.badDay
        if (v1BadDay != null) {
            s = s.copy(badDay = null, dayMode = s.dayMode ?: DayModeState(v1BadDay, DayMode.BAD_DAY.json, DayModeState.SOURCE_USER))
        }

        // 2. A new plan: its sampling, ratings and step-downs start from nothing.
        if (s.planKey != env.planKey) {
            s = s.copy(
                planKey = env.planKey, completions = emptyMap(), recentRatings = emptyMap(), steppedDown = emptyList(),
                pendingRating = null,
            )
        }

        // 3. The check-in of an earlier day was never answered: its "same" answer stood for that day.
        // Only an earlier day ends ("<" on yyyy-MM-dd keys): when the clocks go back over a day_boundary inside the
        // repeated hour, today briefly reads as yesterday again, and that must not end the day that has just begun.
        val checkIn = s.checkIn
        if (checkIn != null && checkIn.day < env.today) {
            events += checkInEvent(env, ANSWER_NONE, modeOn(s, checkIn.day), s.ticket(checkIn.takeoverId), DETAIL_NOT_ANSWERED)
            s = withoutCheckIn(s)
        }

        // 4. The day boundary passed: a bad or minimum day is over.
        val ended = s.dayMode
        if (ended != null && ended.day < env.today) {
            val prev = DayMode.fromJson(ended.mode)
            val afterBadDay = env.active.afterBadDay
            val yesterday = LocalDate.parse(env.today).minusDays(1).toString()
            if ((prev == DayMode.BAD_DAY || prev == DayMode.MINIMUM) && afterBadDay != null && ended.day == yesterday) {
                // The morning check-in is due; until it is answered the "same" answer applies.
                val same = afterBadDay.same
                s = s.copy(
                    checkIn = CheckInState(env.today, ended.mode),
                    dayMode = if (same == DayMode.NORMAL) null else DayModeState(env.today, same.json, DayModeState.SOURCE_CHECK_IN_PENDING),
                )
                events += LogEvent(
                    env.timestamp(), EventType.DAY_MODE, mode = same.json,
                    detail = "check-in due after a ${dayWord(prev)} day; until it is answered the \"same\" answer applies",
                )
            } else {
                s = s.copy(dayMode = null)
                events += LogEvent(env.timestamp(), EventType.DAY_MODE, mode = DayMode.NORMAL.json, detail = "day ended")
            }
        }

        // 5. Plan source, tickets that no longer fit, and the hard maximum on screen.
        val source = env.active.source.json
        if (s.lastSource != source) {
            if (s.lastSource == PlanSource.PLAN.json && env.active.source != PlanSource.PLAN) {
                events += LogEvent(env.timestamp(), EventType.PLAN_EXPIRED, detail = "now running $source")
            }
            s = s.copy(lastSource = source)
        }
        val known = env.active.directives.map { it.id }.toSet() + BUILTIN_IDS
        fun dropped(directiveId: String, takeoverId: String?, variant: String?, detail: String = GONE) = LogEvent(
            env.timestamp(), EventType.DROPPED, directiveId = directiveId, takeoverId = takeoverId, variant = variant, detail = detail,
        )
        val active = s.active
        val d = active?.let { directiveFor(env, it) }
        if (active != null && (d == null || active.stepsDone >= d.steps.size)) {
            events += dropped(active.directiveId, active.takeoverId, active.variant, if (d == null) GONE else "changed in the active plan")
            s = s.copy(active = null)
        } else if (active != null && env.nowMs - (active.activeSinceMs ?: active.firedAtMs) >= MAX_TAKEOVER_MINUTES * 60_000) {
            events += if (active.directiveId == CHECK_IN_ID) {
                // A check-in left on screen is not an answer: the "same" mode stays.
                checkInEvent(env, ANSWER_NONE, dayMode(s, env).json, active, "timed out")
            } else {
                LogEvent(
                    env.timestamp(), EventType.DIRECTIVE_TIMED_OUT, directiveId = active.directiveId, tier = d?.tier,
                    takeoverId = active.takeoverId, durationS = secondsSince(active.firedAtMs, env),
                    stepsDone = active.stepsDone, stepsTotal = d?.steps?.size, variant = active.variant,
                )
            }
            s = s.copy(active = null, checkIn = s.checkIn?.takeIf { it.takeoverId != active.takeoverId })
        }
        s.pending.filter { it.directiveId !in known }.forEach { events += dropped(it.directiveId, it.takeoverId, it.variant) }
        s.quietQueue.filter { it.directiveId !in known }.forEach { events += dropped(it.directiveId, null, null) }
        s = s.copy(
            pending = s.pending.filter { it.directiveId in known },
            quietQueue = s.quietQueue.filter { it.directiveId in known },
        )

        // 6. A difficulty question nobody answered.
        val rating = s.pendingRating
        if (rating != null && ratingExpired(rating, env)) {
            events += ratingEvent(env, EventType.RATING_SKIPPED, rating, detail = DETAIL_RATING_NOT_ANSWERED)
            s = s.copy(pendingRating = null)
        }
        return Outcome(promote(s, env), events)
    }

    /** One or more directives were triggered. Unknown ids and unsupported triggers are ignored. */
    fun onTrigger(state: RuntimeState, env: Env, directiveIds: List<String>, trigger: String, scheduledFor: String? = null): Outcome {
        val refreshed = refresh(state, env)
        var s = refreshed.state
        val events = refreshed.events.toMutableList()
        val mode = dayMode(s, env)
        for (id in directiveIds.distinct()) {
            val d = env.active.find(id) ?: continue
            if (d.trigger is Trigger.Unsupported) continue
            when (val decision = decide(d, s.quiet, mode, env.active, env.allowQuietOverride)) {
                is Decision.Fire -> {
                    val r = fire(s, env, d, trigger, decision.silent, scheduledFor)
                    s = r.state
                    events += r.events
                }
                Decision.Queue -> {
                    if (s.quietQueue.none { it.directiveId == id }) {
                        s = s.copy(quietQueue = s.quietQueue + Queued(id, trigger, env.nowMs))
                    }
                    events += LogEvent(env.timestamp(), EventType.QUEUED_QUIET, directiveId = id, tier = d.tier, trigger = trigger, scheduledFor = scheduledFor)
                }
                is Decision.Skip -> events += LogEvent(env.timestamp(), decision.eventType, directiveId = id, tier = d.tier, trigger = trigger, scheduledFor = scheduledFor)
            }
        }
        return Outcome(s, events)
    }

    /**
     * Test button: show a directive now, ignoring quiet mode and the day's mode. Also fires the built-ins: the tier
     * previews ([DEMO_1_ID], [DEMO_2_ID], [DEMO_3_ID]) and the check-in preview ([CHECK_IN_ID]), which changes nothing
     * when answered and leaves a real check-in alone.
     */
    fun fireNow(state: RuntimeState, env: Env, directiveId: String): Outcome {
        val refreshed = refresh(state, env)
        val d = env.active.find(directiveId) ?: builtin(directiveId) ?: return refreshed
        val r = fire(refreshed.state, env, d, "debug", silent = false, scheduledFor = null)
        return Outcome(r.state, refreshed.events + r.events)
    }

    /**
     * The user is at the phone: it was unlocked, VIGIL was opened, or the phone is awake and unlocked. Fires the morning
     * check-in when it is due, once; it waits behind a takeover on screen like any other. The engine never wakes the
     * screen for it.
     */
    fun onUserPresent(state: RuntimeState, env: Env): Outcome {
        val refreshed = refresh(state, env)
        val s = refreshed.state
        val checkIn = s.checkIn
        if (checkIn == null || checkIn.day != env.today || checkIn.fired) return refreshed
        // A check-in preview from the TEST screen is still open: the real one comes once it has closed.
        if (s.active?.directiveId == CHECK_IN_ID || s.pending.any { it.directiveId == CHECK_IN_ID }) return refreshed
        val r = fire(s, env, CHECK_IN, TRIGGER_CHECK_IN, silent = s.quiet, scheduledFor = null)
        val takeoverId = r.events.firstOrNull { it.type == EventType.DIRECTIVE_FIRED }?.takeoverId ?: return refreshed
        return Outcome(r.state.copy(checkIn = checkIn.copy(fired = true, takeoverId = takeoverId)), refreshed.events + r.events)
    }

    /**
     * The morning check-in was answered. The plan's after_bad_day picks today's mode for the answer (normal without
     * it); a stricter mode also applies to takeovers that already fired. A preview from the TEST screen only logs the
     * answer. Ignored unless [takeoverId] is the check-in on screen.
     */
    fun answerCheckIn(state: RuntimeState, env: Env, takeoverId: String, answer: CheckInAnswer): Outcome {
        val refreshed = refresh(state, env)
        var s = refreshed.state
        val active = s.active
        if (active == null || active.takeoverId != takeoverId || active.directiveId != CHECK_IN_ID) return refreshed
        val events = refreshed.events.toMutableList()
        val mode = env.active.afterBadDay?.modeFor(answer) ?: DayMode.NORMAL
        val checkIn = s.checkIn
        if (active.trigger in LogExport.TEST_TRIGGERS || checkIn == null || checkIn.takeoverId != takeoverId) {
            val detail = if (active.trigger in LogExport.TEST_TRIGGERS) "test: nothing changed" else "no check-in was due: nothing changed"
            events += checkInEvent(env, answer.json, mode.json, active, detail)
            return Outcome(promote(s.copy(active = null), env), events)
        }
        // The check-in's own day: the same as today, except in the hour the clocks go back over the day boundary.
        events += checkInEvent(env, answer.json, mode.json, active, "for ${checkIn.day}, after a ${dayWord(DayMode.fromJson(checkIn.after))} day")
        s = s.copy(
            active = null,
            checkIn = null,
            dayMode = if (mode == DayMode.NORMAL) null else DayModeState(checkIn.day, mode.json, DayModeState.SOURCE_CHECK_IN),
        )
        val applied = applyModeToFired(s, env, mode)
        return Outcome(promote(applied.state, env), events + applied.events)
    }

    /** Quiet mode switched on or off. Switching it off releases the queue (re-checked against the day's mode). */
    fun setQuiet(state: RuntimeState, env: Env, on: Boolean): Outcome {
        val refreshed = refresh(state, env)
        var s = refreshed.state
        if (s.quiet == on) return refreshed
        val events = refreshed.events.toMutableList()
        events += LogEvent(env.timestamp(), if (on) EventType.QUIET_ON else EventType.QUIET_OFF)
        s = s.copy(quiet = on)
        if (on) {
            val r = applyQuietToFired(s, env)
            s = r.state
            events += r.events
        }
        if (!on && s.quietQueue.isNotEmpty()) {
            val queue = s.quietQueue
            s = s.copy(quietQueue = emptyList())
            val mode = dayMode(s, env)
            val keep = keepFor(mode, env.active)
            for (q in queue) {
                val d = env.active.find(q.directiveId) ?: continue
                if (keep != null && d.id !in keep) {
                    events += LogEvent(env.timestamp(), skippedType(mode), directiveId = d.id, tier = d.tier, trigger = TRIGGER_QUIET_QUEUE)
                    continue
                }
                val r = fire(s, env, d, TRIGGER_QUIET_QUEUE, silent = false, scheduledFor = null)
                s = r.state
                events += r.events
            }
        }
        return Outcome(s, events)
    }

    /**
     * Quiet mode switched on while takeovers had already fired: the one on screen goes silent unless it may
     * override quiet mode; waiting ones follow their quiet rule (held until quiet ends, skipped, or silent).
     * A built-in (the check-in, a demo) is never held or skipped; it just goes silent.
     */
    private fun applyQuietToFired(state: RuntimeState, env: Env): Outcome {
        val events = mutableListOf<LogEvent>()
        var queue = state.quietQueue
        val pending = mutableListOf<Ticket>()
        for (t in state.pending) {
            val d = env.active.find(t.directiveId)
            when (val decision = if (d == null) null else decide(d, quiet = true, mode = DayMode.NORMAL, active = env.active, allowOverride = env.allowQuietOverride)) {
                Decision.Queue -> {
                    events += LogEvent(
                        env.timestamp(), EventType.DROPPED, directiveId = t.directiveId, tier = d?.tier, takeoverId = t.takeoverId, trigger = t.trigger,
                        variant = t.variant, detail = DETAIL_HELD_FOR_QUIET,
                    )
                    events += LogEvent(env.timestamp(), EventType.QUEUED_QUIET, directiveId = t.directiveId, tier = d?.tier, trigger = t.trigger, scheduledFor = t.scheduledFor)
                    if (queue.none { it.directiveId == t.directiveId }) queue = queue + Queued(t.directiveId, t.trigger, env.nowMs)
                }
                is Decision.Skip -> events += LogEvent(
                    env.timestamp(), EventType.DROPPED, directiveId = t.directiveId, tier = d?.tier, takeoverId = t.takeoverId, trigger = t.trigger,
                    variant = t.variant, detail = DETAIL_SKIPPED_FOR_QUIET,
                )
                is Decision.Fire -> pending += if (decision.silent) t.copy(silent = true) else t
                null -> pending += t.copy(silent = true)
            }
        }
        val active = state.active?.let { t ->
            val d = env.active.find(t.directiveId)
            val keepsSound = d != null &&
                decide(d, quiet = true, mode = DayMode.NORMAL, active = env.active, allowOverride = env.allowQuietOverride) == Decision.Fire(silent = false)
            if (keepsSound) t else t.copy(silent = true)
        }
        return Outcome(state.copy(active = active, pending = pending, quietQueue = queue), events)
    }

    /**
     * "I'm not okay today" ([on]) and "I'm okay again" (off).
     *
     * On: until the day boundary only bad_day.keep directives fire; a showing or waiting takeover that is not kept is
     * closed as bypassed_bad_day (never as a failure), and a directive held by quiet mode that is not kept is logged as
     * skipped_bad_day. It replaces a morning check-in that is still open. It changes nothing more on a day that is
     * already a bad or minimum day.
     *
     * Off: ends today's bad or minimum day, whoever chose it; everything fires again.
     */
    fun setBadDay(state: RuntimeState, env: Env, on: Boolean): Outcome {
        val refreshed = refresh(state, env)
        var s = refreshed.state
        val events = refreshed.events.toMutableList()
        if (!on) {
            if (dayMode(s, env) == DayMode.NORMAL) return refreshed
            events += LogEvent(env.timestamp(), EventType.BAD_DAY_OFF, detail = "switched off")
            return Outcome(s.copy(dayMode = null), events)
        }
        val checkIn = s.checkIn
        if (checkIn != null && checkIn.day == env.today) {
            events += checkInEvent(env, ANSWER_NONE, dayMode(s, env).json, s.ticket(checkIn.takeoverId), "replaced by \"I'm not okay today\"")
            s = withoutCheckIn(s)
        }
        if (dayMode(s, env) != DayMode.NORMAL) return Outcome(promote(s, env), events)
        val until = LocalDate.parse(env.today).plusDays(1).atTime(env.active.dayBoundary)
        events += LogEvent(env.timestamp(), EventType.BAD_DAY_ON, detail = "until $until")
        s = s.copy(dayMode = DayModeState(env.today, DayMode.BAD_DAY.json, DayModeState.SOURCE_USER))
        val applied = applyModeToFired(s, env, DayMode.BAD_DAY)
        return Outcome(promote(applied.state, env), events + applied.events)
    }

    /**
     * A stricter [mode] started while takeovers had already fired: a showing or waiting one that the mode does not keep
     * closes as bypassed (never a failure), and one held by quiet mode is logged as skipped. Built-ins (the check-in,
     * the demos) are never bypassed. The caller promotes.
     */
    private fun applyModeToFired(state: RuntimeState, env: Env, mode: DayMode): Outcome {
        val keep = keepFor(mode, env.active) ?: return Outcome(state, emptyList())
        fun kept(t: Ticket) = t.directiveId in keep || builtin(t.directiveId) != null
        val events = mutableListOf<LogEvent>()
        for (t in listOfNotNull(state.active) + state.pending) {
            if (kept(t)) continue
            val d = directiveFor(env, t)
            events += LogEvent(
                env.timestamp(), bypassedType(mode), directiveId = t.directiveId, tier = d?.tier, takeoverId = t.takeoverId,
                durationS = secondsSince(t.firedAtMs, env), stepsDone = t.stepsDone, stepsTotal = d?.steps?.size, variant = t.variant,
            )
        }
        state.quietQueue.filter { it.directiveId !in keep }.forEach {
            events += LogEvent(
                env.timestamp(), skippedType(mode), directiveId = it.directiveId, tier = env.active.find(it.directiveId)?.tier,
                trigger = TRIGGER_QUIET_QUEUE,
            )
        }
        val next = state.copy(
            active = state.active?.takeIf(::kept),
            pending = state.pending.filter(::kept),
            quietQueue = state.quietQueue.filter { it.directiveId in keep },
        )
        return Outcome(next, events)
    }

    /** The current step appeared on screen; its min_seconds lock starts now. */
    fun stepShown(state: RuntimeState, env: Env, takeoverId: String): Outcome {
        val active = state.active
        if (active == null || active.takeoverId != takeoverId || active.stepShownAtMs != null) return Outcome(state, emptyList())
        return Outcome(state.copy(active = active.copy(stepShownAtMs = env.nowMs)), emptyList())
    }

    /** Seconds until the current step can be ticked (0 when it already can). */
    fun lockRemainingSeconds(ticket: Ticket, directive: Directive, nowMs: Long): Long {
        val step = directive.steps.getOrNull(ticket.stepsDone) ?: return 0
        val shown = ticket.stepShownAtMs ?: return step.minSeconds.toLong()
        val elapsed = (nowMs - shown).coerceAtLeast(0) / 1000
        return (step.minSeconds - elapsed).coerceAtLeast(0)
    }

    /**
     * The current step was ticked. Ignored if its lock has not run out (the UI should prevent this anyway), and for
     * the check-in, which is answered ([answerCheckIn]) rather than ticked.
     *
     * Ticking the last step completes the directive. A real (non-test) completion is counted for this plan. When the
     * directive's rating policy asks for it, the difficulty question opens ([RuntimeState.pendingRating]); an older
     * open one is then logged as skipped.
     */
    fun stepDone(state: RuntimeState, env: Env, takeoverId: String, stepIndex: Int): Outcome {
        val active = state.active ?: return Outcome(state, emptyList())
        if (active.takeoverId != takeoverId || active.stepsDone != stepIndex) return Outcome(state, emptyList())
        if (active.directiveId == CHECK_IN_ID) return Outcome(state, emptyList())
        val d = directiveFor(env, active) ?: return Outcome(state, emptyList())
        // getOrNull: a newer plan may have given the directive fewer steps.
        val step = d.steps.getOrNull(stepIndex) ?: return Outcome(state, emptyList())
        if (active.stepShownAtMs == null || lockRemainingSeconds(active, d, env.nowMs) > 0) return Outcome(state, emptyList())
        val events = mutableListOf(
            LogEvent(
                env.timestamp(), EventType.STEP_COMPLETED, directiveId = d.id, tier = d.tier, takeoverId = takeoverId,
                stepIndex = stepIndex, stepText = step.text, durationS = secondsSince(active.stepShownAtMs, env), variant = active.variant,
            ),
        )
        val done = stepIndex + 1
        if (done < d.steps.size) {
            return Outcome(state.copy(active = active.copy(stepsDone = done, stepShownAtMs = null)), events)
        }
        events += LogEvent(
            env.timestamp(), EventType.DIRECTIVE_COMPLETED, directiveId = d.id, tier = d.tier, takeoverId = takeoverId,
            trigger = active.trigger, durationS = secondsSince(active.firedAtMs, env), stepsDone = done, stepsTotal = d.steps.size,
            variant = active.variant,
        )
        var s = state.copy(active = null)
        val real = active.trigger !in LogExport.TEST_TRIGGERS
        val count = (s.completions[d.id] ?: 0) + if (real) 1 else 0
        if (real) s = s.copy(completions = s.completions + (d.id to count))
        if (asksForRating(d, real, count)) {
            s.pendingRating?.let { old ->
                events += ratingEvent(env, EventType.RATING_SKIPPED, old, detail = if (ratingExpired(old, env)) DETAIL_RATING_NOT_ANSWERED else "replaced")
            }
            s = s.copy(pendingRating = PendingRating(takeoverId, d.id, active.variant, active.trigger, env.nowMs))
        }
        return Outcome(promote(s, env), events)
    }

    /**
     * Whether a completion asks EASY / OK / HARD: never, always, or for "sample" the 1st, 4th, 7th ... real completion
     * in this plan (every test completion, so the TEST screen shows the question).
     */
    private fun asksForRating(d: Directive, real: Boolean, completions: Int): Boolean = when (d.rating) {
        RatingPolicy.NEVER -> false
        RatingPolicy.ALWAYS -> true
        RatingPolicy.SAMPLE -> !real || (completions - 1) % RatingPolicy.SAMPLE_EVERY == 0
    }

    /**
     * The answer to the difficulty question after [takeoverId] completed; null = skipped. Ignored unless that question
     * is open. A real HARD rating of the directive as written counts towards adapt.step_down_after_hard: when the
     * last that-many ratings were all HARD, variants.easier runs for the rest of this plan. The app never steps back up.
     */
    fun rate(state: RuntimeState, env: Env, takeoverId: String, rating: Rating?): Outcome {
        val p = state.pendingRating
        if (p == null || p.takeoverId != takeoverId) return Outcome(state, emptyList())
        var s = state.copy(pendingRating = null)
        if (rating == null) return Outcome(s, listOf(ratingEvent(env, EventType.RATING_SKIPPED, p, detail = "skipped")))
        val events = mutableListOf(ratingEvent(env, EventType.RATED, p, rating = rating.json))
        if (p.trigger in LogExport.TEST_TRIGGERS || p.variant != null) return Outcome(s, events)
        val recent = ((s.recentRatings[p.directiveId] ?: emptyList()) + rating.json).takeLast(MAX_RECENT_RATINGS)
        s = s.copy(recentRatings = s.recentRatings + (p.directiveId to recent))
        val d = env.active.find(p.directiveId)
        val n = d?.stepDownAfterHard
        if (d != null && n != null && n >= 1 && d.easier != null && d.id !in s.steppedDown &&
            recent.size >= n && recent.takeLast(n).all { it == Rating.HARD.json }
        ) {
            s = s.copy(steppedDown = s.steppedDown + d.id)
            val last = if (n == 1) "the last rating was HARD" else "the last $n ratings were HARD"
            events += LogEvent(
                env.timestamp(), EventType.STEPPED_DOWN, directiveId = d.id, tier = d.tier, variant = Variant.EASIER,
                detail = "$last: variants.easier runs for the rest of this plan",
            )
        }
        return Outcome(s, events)
    }

    /**
     * The dismiss button was held to the end. Logged as dismissed, never dropped silently. Leaving the check-in is not
     * an answer: it is logged as check_in "none" and its "same" mode stays.
     */
    fun dismiss(state: RuntimeState, env: Env, takeoverId: String): Outcome {
        val active = state.active ?: return Outcome(state, emptyList())
        if (active.takeoverId != takeoverId) return Outcome(state, emptyList())
        if (active.directiveId == CHECK_IN_ID) {
            // dismiss does not refresh: a check-in left from before the day boundary logs the mode of its own day.
            val own = state.checkIn?.takeIf { it.takeoverId == takeoverId }
            val mode = if (own != null) modeOn(state, own.day) else dayMode(state, env).json
            val event = checkInEvent(env, ANSWER_NONE, mode, active, "dismissed")
            return Outcome(promote(state.copy(active = null, checkIn = if (own != null) null else state.checkIn), env), listOf(event))
        }
        val d = directiveFor(env, active)
        val event = LogEvent(
            env.timestamp(), EventType.DIRECTIVE_DISMISSED, directiveId = active.directiveId, tier = d?.tier, takeoverId = takeoverId,
            trigger = active.trigger, durationS = secondsSince(active.firedAtMs, env), stepsDone = active.stepsDone, stepsTotal = d?.steps?.size,
            variant = active.variant,
        )
        return Outcome(promote(state.copy(active = null), env), listOf(event))
    }

    /** The directive a ticket runs (from the active plan, or a built-in), in the variant it fired with. */
    fun directiveFor(env: Env, ticket: Ticket): Directive? =
        (env.active.find(ticket.directiveId) ?: builtin(ticket.directiveId))?.withVariant(ticket.variant)

    private fun fire(s: RuntimeState, env: Env, d: Directive, trigger: String, silent: Boolean, scheduledFor: String?): Outcome {
        // The same directive already showing or waiting: don't stack a second copy, but log the repeat.
        if (s.active?.directiveId == d.id || s.pending.any { it.directiveId == d.id }) {
            val repeat = LogEvent(
                env.timestamp(), EventType.REPEATED, directiveId = d.id, tier = d.tier, trigger = trigger, scheduledFor = scheduledFor,
            )
            return Outcome(s, listOf(repeat))
        }
        // After a step-down the easier version runs; the ticket keeps it even if more ratings come in meanwhile.
        val variant = if (d.id in s.steppedDown && d.easier != null) Variant.EASIER else null
        val ticket = Ticket(env.newId(), d.id, trigger, silent, env.nowMs, scheduledFor, variant = variant)
        val event = LogEvent(
            env.timestamp(), EventType.DIRECTIVE_FIRED, directiveId = d.id, tier = d.tier, trigger = trigger,
            takeoverId = ticket.takeoverId, scheduledFor = scheduledFor, silent = silent, stepsTotal = d.withVariant(variant).steps.size,
            variant = variant,
        )
        val next = if (s.active == null) s.copy(active = ticket.copy(activeSinceMs = env.nowMs)) else s.copy(pending = s.pending + ticket)
        return Outcome(next, listOf(event))
    }

    private fun promote(s: RuntimeState, env: Env): RuntimeState {
        if (s.active != null || s.pending.isEmpty()) return s
        return s.copy(active = s.pending.first().copy(activeSinceMs = env.nowMs), pending = s.pending.drop(1))
    }

    private fun secondsSince(ms: Long, env: Env): Long = ((env.nowMs - ms).coerceAtLeast(0)) / 1000

    /** The directives that still fire in [mode]; null when everything does. */
    private fun keepFor(mode: DayMode, active: ActiveSet): Set<String>? = when (mode) {
        DayMode.NORMAL -> null
        DayMode.BAD_DAY -> active.badDayKeep
        DayMode.MINIMUM -> active.minimumKeep
    }

    private fun skippedType(mode: DayMode): String =
        if (mode == DayMode.MINIMUM) EventType.SKIPPED_MINIMUM else EventType.SKIPPED_BAD_DAY

    private fun bypassedType(mode: DayMode): String =
        if (mode == DayMode.MINIMUM) EventType.BYPASSED_MINIMUM else EventType.BYPASSED_BAD_DAY

    /** "a bad day", "a minimum day": how the log names a day's mode in plain words. */
    private fun dayWord(mode: DayMode?): String = when (mode) {
        DayMode.BAD_DAY -> "bad"
        DayMode.MINIMUM -> "minimum"
        DayMode.NORMAL, null -> "normal"
    }

    /** The [DayMode.json] the state holds for [day] (today, or a day the next refresh has not ended yet); normal if none. */
    private fun modeOn(state: RuntimeState, day: String): String = state.dayMode?.takeIf { it.day == day }?.mode ?: DayMode.NORMAL.json

    private fun RuntimeState.ticket(takeoverId: String?): Ticket? =
        takeoverId?.let { id -> (listOfNotNull(active) + pending).firstOrNull { it.takeoverId == id } }

    /** Forgets the check-in and takes its takeover, if it fired, off the screen and out of the line (no event). */
    private fun withoutCheckIn(s: RuntimeState): RuntimeState {
        val id = s.checkIn?.takeoverId
        return s.copy(checkIn = null, active = s.active?.takeIf { it.takeoverId != id }, pending = s.pending.filter { it.takeoverId != id })
    }

    private fun checkInEvent(env: Env, answer: String, mode: String, ticket: Ticket?, detail: String) = LogEvent(
        env.timestamp(), EventType.CHECK_IN, trigger = ticket?.trigger, takeoverId = ticket?.takeoverId, answer = answer, mode = mode,
        detail = detail,
    )

    private fun ratingExpired(p: PendingRating, env: Env): Boolean = env.nowMs - p.askedAtMs >= RATING_WINDOW_MS

    private fun ratingEvent(env: Env, type: String, p: PendingRating, rating: String? = null, detail: String? = null) = LogEvent(
        env.timestamp(), type, directiveId = p.directiveId, tier = (env.active.find(p.directiveId) ?: builtin(p.directiveId))?.tier,
        trigger = p.trigger, takeoverId = p.takeoverId, variant = p.variant, rating = rating, detail = detail,
    )

    const val TRIGGER_QUIET_QUEUE = "quiet_queue"

    /** The trigger of the morning check-in's takeover. */
    const val TRIGGER_CHECK_IN = "check_in"

    /** The check_in event's answer when the check-in was not answered (the "same" mode stayed). */
    const val ANSWER_NONE = "none"

    /** Details of the `dropped` events for waiting takeovers when quiet mode is switched on. */
    const val DETAIL_HELD_FOR_QUIET = "quiet mode switched on: held until it ends (quiet: queue)"
    const val DETAIL_SKIPPED_FOR_QUIET = "quiet mode switched on: skipped (quiet: ignore)"
    private const val GONE = "no longer in the active plan"
    private const val DETAIL_NOT_ANSWERED = "not answered; the \"same\" answer applied"
    private const val DETAIL_RATING_NOT_ANSWERED = "not answered"

    /** Directive ids starting with this belong to the app's built-ins; plans may not use them. */
    const val BUILTIN_ID_PREFIX = "vigil-"

    /** The TEST screen's tier previews, so a takeover can be tried without a plan. */
    const val DEMO_1_ID = "vigil-demo-1"
    const val DEMO_2_ID = "vigil-demo-2"
    const val DEMO_3_ID = "vigil-demo-3"

    /** The v1 demo id, kept for old callers: the tier-3 preview under its old id. */
    const val DEMO_ID = "vigil-demo"

    /** The morning check-in after a bad or minimum day (also previewed from the TEST screen). */
    const val CHECK_IN_ID = "vigil-check-in"

    val DEMO_1: Directive = demo(DEMO_1_ID, tier = 1)
    val DEMO_2: Directive = demo(DEMO_2_ID, tier = 2)
    val DEMO_3: Directive = demo(DEMO_3_ID, tier = 3)

    /** The tier-3 preview under the v1 id [DEMO_ID]. */
    val DEMO: Directive = DEMO_3.copy(id = DEMO_ID)

    /** One tier-1 step, answered with BETTER / SAME / WORSE instead of a hold ([answerCheckIn]). Never rated. */
    val CHECK_IN: Directive = Directive(
        id = CHECK_IN_ID,
        tier = 1,
        title = "CHECK-IN",
        trigger = Trigger.Unsupported(TRIGGER_CHECK_IN),
        steps = listOf(Step("HOW IS TODAY?", 0)),
        why = "YESTERDAY WAS HARD. ANSWER HONESTLY.",
        quiet = QuietPolicy.SILENT,
        rating = RatingPolicy.NEVER,
    )

    private val BUILTINS: Map<String, Directive> = listOf(DEMO_1, DEMO_2, DEMO_3, DEMO, CHECK_IN).associateBy { it.id }

    /** Every built-in directive id: housekeeping never drops their tickets. */
    val BUILTIN_IDS: Set<String> = BUILTINS.keys

    /** A built-in directive (the tier previews, the v1 demo, the check-in), or null. */
    fun builtin(id: String): Directive? = BUILTINS[id]

    private fun demo(id: String, tier: Int): Directive {
        val steps = listOf(Step("STAND UP.", 3), Step("TAKE ONE SLOW BREATH.", 5), Step("LOOK AT SOMETHING FAR AWAY.", 3))
        return Directive(
            id = id,
            tier = tier,
            title = "TIER $tier PREVIEW",
            trigger = Trigger.Unsupported("debug"),
            steps = steps.take(tier),
            why = "THIS IS WHAT A DIRECTIVE FEELS LIKE.",
            quiet = QuietPolicy.QUEUE,
        )
    }
}
