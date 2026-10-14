package dev.vigil.core.engine

import dev.vigil.core.log.EventType
import dev.vigil.core.log.LogEvent
import dev.vigil.core.log.LogJson
import dev.vigil.core.plan.Directive
import dev.vigil.core.plan.QuietPolicy
import dev.vigil.core.plan.Trigger
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
)

/** A directive held during quiet mode (quiet: queue). */
@Serializable
data class Queued(
    @SerialName("directive_id") val directiveId: String,
    val trigger: String,
    @SerialName("queued_at_ms") val queuedAtMs: Long,
)

/** Everything the app remembers between triggers. Persisted as JSON. */
@Serializable
data class RuntimeState(
    val quiet: Boolean = false,
    /** The day key (yyyy-MM-dd) on which "I'm not okay today" was switched on, if any. */
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

/** How a fired directive is treated, given quiet mode and bad-day mode. */
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

    fun decide(directive: Directive, quiet: Boolean, badDay: Boolean, keep: Set<String>, allowOverride: Boolean): Decision {
        if (badDay && directive.id !in keep) return Decision.Skip(EventType.SKIPPED_BAD_DAY)
        if (!quiet) return Decision.Fire(silent = false)
        return when (directive.quiet) {
            QuietPolicy.QUEUE -> Decision.Queue
            QuietPolicy.SILENT -> Decision.Fire(silent = true)
            QuietPolicy.IGNORE -> Decision.Skip(EventType.SKIPPED_QUIET)
            QuietPolicy.OVERRIDE -> Decision.Fire(silent = !(allowOverride && directive.tier == 3))
        }
    }

    fun isBadDay(state: RuntimeState, env: Env): Boolean = state.badDay != null && state.badDay == env.today

    /**
     * Housekeeping to run before anything else: ends bad-day mode when its day is over, notices a switch
     * to the fallback/default plan, drops tickets and queued directives that no longer exist (or whose
     * directive changed so the ticked steps no longer fit), and times out a takeover that has been open too long.
     */
    fun refresh(state: RuntimeState, env: Env): Outcome {
        val events = mutableListOf<LogEvent>()
        var s = state
        if (s.badDay != null && s.badDay != env.today) {
            events += LogEvent(env.timestamp(), EventType.BAD_DAY_OFF, detail = "day ended")
            s = s.copy(badDay = null)
        }
        val source = env.active.source.json
        if (s.lastSource != source) {
            if (s.lastSource == PlanSource.PLAN.json && env.active.source != PlanSource.PLAN) {
                events += LogEvent(env.timestamp(), EventType.PLAN_EXPIRED, detail = "now running $source")
            }
            s = s.copy(lastSource = source)
        }
        val known = env.active.directives.map { it.id }.toSet() + DEMO_ID
        fun dropped(directiveId: String, takeoverId: String?, detail: String = GONE) =
            LogEvent(env.timestamp(), EventType.DROPPED, directiveId = directiveId, takeoverId = takeoverId, detail = detail)
        val active = s.active
        val d = active?.let { directiveFor(env, it) }
        if (active != null && (d == null || active.stepsDone >= d.steps.size)) {
            events += dropped(active.directiveId, active.takeoverId, if (d == null) GONE else "changed in the active plan")
            s = s.copy(active = null)
        } else if (active != null && env.nowMs - (active.activeSinceMs ?: active.firedAtMs) >= MAX_TAKEOVER_MINUTES * 60_000) {
            events += LogEvent(
                env.timestamp(), EventType.DIRECTIVE_TIMED_OUT, directiveId = active.directiveId, tier = d?.tier,
                takeoverId = active.takeoverId, durationS = secondsSince(active.firedAtMs, env),
                stepsDone = active.stepsDone, stepsTotal = d?.steps?.size,
            )
            s = s.copy(active = null)
        }
        s.pending.filter { it.directiveId !in known }.forEach { events += dropped(it.directiveId, it.takeoverId) }
        s.quietQueue.filter { it.directiveId !in known }.forEach { events += dropped(it.directiveId, null) }
        s = s.copy(
            pending = s.pending.filter { it.directiveId in known },
            quietQueue = s.quietQueue.filter { it.directiveId in known },
        )
        return Outcome(promote(s, env), events)
    }

    /** One or more directives were triggered. Unknown ids and unsupported triggers are ignored. */
    fun onTrigger(state: RuntimeState, env: Env, directiveIds: List<String>, trigger: String, scheduledFor: String? = null): Outcome {
        val refreshed = refresh(state, env)
        var s = refreshed.state
        val events = refreshed.events.toMutableList()
        val badDay = isBadDay(s, env)
        for (id in directiveIds.distinct()) {
            val d = env.active.find(id) ?: continue
            if (d.trigger is Trigger.Unsupported) continue
            when (val decision = decide(d, s.quiet, badDay, env.active.badDayKeep, env.allowQuietOverride)) {
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

    /** Debug button: show a directive now, ignoring quiet and bad-day mode. */
    fun fireNow(state: RuntimeState, env: Env, directiveId: String): Outcome {
        val refreshed = refresh(state, env)
        val d = env.active.find(directiveId) ?: if (directiveId == DEMO_ID) DEMO else return refreshed
        val r = fire(refreshed.state, env, d, "debug", silent = false, scheduledFor = null)
        return Outcome(r.state, refreshed.events + r.events)
    }

    /** Quiet mode switched on or off. Switching it off releases the queue (re-checked against bad-day mode). */
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
            val badDay = isBadDay(s, env)
            for (q in queue) {
                val d = env.active.find(q.directiveId) ?: continue
                if (badDay && d.id !in env.active.badDayKeep) {
                    events += LogEvent(env.timestamp(), EventType.SKIPPED_BAD_DAY, directiveId = d.id, tier = d.tier, trigger = TRIGGER_QUIET_QUEUE)
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
     */
    private fun applyQuietToFired(state: RuntimeState, env: Env): Outcome {
        val events = mutableListOf<LogEvent>()
        var queue = state.quietQueue
        val pending = mutableListOf<Ticket>()
        for (t in state.pending) {
            val d = env.active.find(t.directiveId)
            when (val decision = if (d == null) null else decide(d, quiet = true, badDay = false, keep = emptySet(), allowOverride = env.allowQuietOverride)) {
                Decision.Queue -> {
                    events += LogEvent(env.timestamp(), EventType.DROPPED, directiveId = t.directiveId, tier = d?.tier, takeoverId = t.takeoverId, trigger = t.trigger, detail = DETAIL_HELD_FOR_QUIET)
                    events += LogEvent(env.timestamp(), EventType.QUEUED_QUIET, directiveId = t.directiveId, tier = d?.tier, trigger = t.trigger, scheduledFor = t.scheduledFor)
                    if (queue.none { it.directiveId == t.directiveId }) queue = queue + Queued(t.directiveId, t.trigger, env.nowMs)
                }
                is Decision.Skip -> events += LogEvent(
                    env.timestamp(), EventType.DROPPED, directiveId = t.directiveId, tier = d?.tier, takeoverId = t.takeoverId, trigger = t.trigger, detail = DETAIL_SKIPPED_FOR_QUIET,
                )
                is Decision.Fire -> pending += if (decision.silent) t.copy(silent = true) else t
                null -> pending += t
            }
        }
        val active = state.active?.let { t ->
            val d = env.active.find(t.directiveId)
            val keepsSound = d != null && decide(d, quiet = true, badDay = false, keep = emptySet(), allowOverride = env.allowQuietOverride) == Decision.Fire(silent = false)
            if (keepsSound) t else t.copy(silent = true)
        }
        return Outcome(state.copy(active = active, pending = pending, quietQueue = queue), events)
    }

    /**
     * "I'm not okay today". On: until the day boundary only bad_day.keep directives fire; a showing or
     * waiting takeover that is not kept is closed as bypassed_bad_day (never as a failure), and a directive
     * held by quiet mode that is not kept is logged as skipped_bad_day.
     */
    fun setBadDay(state: RuntimeState, env: Env, on: Boolean): Outcome {
        val refreshed = refresh(state, env)
        var s = refreshed.state
        val events = refreshed.events.toMutableList()
        if (!on) {
            if (s.badDay == null) return refreshed
            events += LogEvent(env.timestamp(), EventType.BAD_DAY_OFF, detail = "switched off")
            return Outcome(s.copy(badDay = null), events)
        }
        if (isBadDay(s, env)) return refreshed
        val until = LocalDate.parse(env.today).plusDays(1).atTime(env.active.dayBoundary)
        events += LogEvent(env.timestamp(), EventType.BAD_DAY_ON, detail = "until $until")
        s = s.copy(badDay = env.today)
        val keep = env.active.badDayKeep
        fun bypass(t: Ticket) {
            val d = env.active.find(t.directiveId)
            events += LogEvent(
                env.timestamp(), EventType.BYPASSED_BAD_DAY, directiveId = t.directiveId, tier = d?.tier, takeoverId = t.takeoverId,
                durationS = secondsSince(t.firedAtMs, env), stepsDone = t.stepsDone, stepsTotal = d?.steps?.size,
            )
        }
        val active = s.active
        if (active != null && active.directiveId !in keep) bypass(active)
        s.pending.filter { it.directiveId !in keep }.forEach(::bypass)
        s.quietQueue.filter { it.directiveId !in keep }.forEach {
            events += LogEvent(
                env.timestamp(), EventType.SKIPPED_BAD_DAY, directiveId = it.directiveId, tier = env.active.find(it.directiveId)?.tier,
                trigger = TRIGGER_QUIET_QUEUE,
            )
        }
        s = s.copy(
            active = active?.takeIf { it.directiveId in keep },
            pending = s.pending.filter { it.directiveId in keep },
            quietQueue = s.quietQueue.filter { it.directiveId in keep },
        )
        return Outcome(promote(s, env), events)
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
     * The current step was ticked. Ignored if its lock has not run out (the UI should prevent this anyway).
     * Ticking the last step completes the directive.
     */
    fun stepDone(state: RuntimeState, env: Env, takeoverId: String, stepIndex: Int): Outcome {
        val active = state.active ?: return Outcome(state, emptyList())
        if (active.takeoverId != takeoverId || active.stepsDone != stepIndex) return Outcome(state, emptyList())
        val d = directiveFor(env, active) ?: return Outcome(state, emptyList())
        // getOrNull: a newer plan may have given the directive fewer steps.
        val step = d.steps.getOrNull(stepIndex) ?: return Outcome(state, emptyList())
        if (active.stepShownAtMs == null || lockRemainingSeconds(active, d, env.nowMs) > 0) return Outcome(state, emptyList())
        val events = mutableListOf(
            LogEvent(
                env.timestamp(), EventType.STEP_COMPLETED, directiveId = d.id, tier = d.tier, takeoverId = takeoverId,
                stepIndex = stepIndex, stepText = step.text, durationS = secondsSince(active.stepShownAtMs, env),
            ),
        )
        val done = stepIndex + 1
        if (done < d.steps.size) {
            return Outcome(state.copy(active = active.copy(stepsDone = done, stepShownAtMs = null)), events)
        }
        events += LogEvent(
            env.timestamp(), EventType.DIRECTIVE_COMPLETED, directiveId = d.id, tier = d.tier, takeoverId = takeoverId,
            trigger = active.trigger, durationS = secondsSince(active.firedAtMs, env), stepsDone = done, stepsTotal = d.steps.size,
        )
        return Outcome(promote(state.copy(active = null), env), events)
    }

    /** The dismiss button was held to the end. Logged as dismissed, never dropped silently. */
    fun dismiss(state: RuntimeState, env: Env, takeoverId: String): Outcome {
        val active = state.active ?: return Outcome(state, emptyList())
        if (active.takeoverId != takeoverId) return Outcome(state, emptyList())
        val d = directiveFor(env, active)
        val event = LogEvent(
            env.timestamp(), EventType.DIRECTIVE_DISMISSED, directiveId = active.directiveId, tier = d?.tier, takeoverId = takeoverId,
            trigger = active.trigger, durationS = secondsSince(active.firedAtMs, env), stepsDone = active.stepsDone, stepsTotal = d?.steps?.size,
        )
        return Outcome(promote(state.copy(active = null), env), listOf(event))
    }

    /** Looks up the directive a ticket refers to (including the debug demo). */
    fun directiveFor(env: Env, ticket: Ticket): Directive? =
        env.active.find(ticket.directiveId) ?: if (ticket.directiveId == DEMO_ID) DEMO else null

    private fun fire(s: RuntimeState, env: Env, d: Directive, trigger: String, silent: Boolean, scheduledFor: String?): Outcome {
        // The same directive already showing or waiting: don't stack a second copy, but log the repeat.
        if (s.active?.directiveId == d.id || s.pending.any { it.directiveId == d.id }) {
            val repeat = LogEvent(
                env.timestamp(), EventType.REPEATED, directiveId = d.id, tier = d.tier, trigger = trigger, scheduledFor = scheduledFor,
            )
            return Outcome(s, listOf(repeat))
        }
        val ticket = Ticket(env.newId(), d.id, trigger, silent, env.nowMs, scheduledFor)
        val event = LogEvent(
            env.timestamp(), EventType.DIRECTIVE_FIRED, directiveId = d.id, tier = d.tier, trigger = trigger,
            takeoverId = ticket.takeoverId, scheduledFor = scheduledFor, silent = silent, stepsTotal = d.steps.size,
        )
        val next = if (s.active == null) s.copy(active = ticket.copy(activeSinceMs = env.nowMs)) else s.copy(pending = s.pending + ticket)
        return Outcome(next, listOf(event))
    }

    private fun promote(s: RuntimeState, env: Env): RuntimeState {
        if (s.active != null || s.pending.isEmpty()) return s
        return s.copy(active = s.pending.first().copy(activeSinceMs = env.nowMs), pending = s.pending.drop(1))
    }

    private fun secondsSince(ms: Long, env: Env): Long = ((env.nowMs - ms).coerceAtLeast(0)) / 1000

    const val TRIGGER_QUIET_QUEUE = "quiet_queue"

    /** Details of the `dropped` events for waiting takeovers when quiet mode is switched on. */
    const val DETAIL_HELD_FOR_QUIET = "quiet mode switched on: held until it ends (quiet: queue)"
    const val DETAIL_SKIPPED_FOR_QUIET = "quiet mode switched on: skipped (quiet: ignore)"
    private const val GONE = "no longer in the active plan"

    /** A built-in directive for the debug "demo takeover" button, so the takeover can be tried without a plan. */
    const val DEMO_ID = "vigil-demo"
    val DEMO = Directive(
        id = DEMO_ID,
        tier = 3,
        title = "TEST DIRECTIVE",
        trigger = Trigger.Unsupported("debug"),
        steps = listOf(
            dev.vigil.core.plan.Step("Stand up.", 3),
            dev.vigil.core.plan.Step("Take one slow breath.", 5),
            dev.vigil.core.plan.Step("Tick this box.", 2),
        ),
        why = "This is what a directive feels like.",
        quiet = QuietPolicy.QUEUE,
    )
}
