package dev.vigil.engine

import android.content.Context
import dev.vigil.Graph
import dev.vigil.core.engine.Engine
import dev.vigil.core.engine.Env
import dev.vigil.core.engine.Outcome
import dev.vigil.core.engine.PlanSource
import dev.vigil.core.engine.RuntimeState
import dev.vigil.core.engine.Scheduling
import dev.vigil.core.engine.TimeSchedule
import dev.vigil.core.log.EventType
import dev.vigil.core.log.LogEvent
import dev.vigil.data.PlanRepository
import dev.vigil.service.VigilService
import dev.vigil.takeover.BadDayResult
import dev.vigil.takeover.StepResult
import dev.vigil.takeover.TakeoverLauncher
import dev.vigil.trigger.AlarmScheduler
import dev.vigil.ui.Format
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The only entry point for things that change what VIGIL does. Each call runs one pure [Engine] step
 * through the state store, then carries out its side effects (show the takeover, set the next alarm,
 * update the notification). No decisions are made here.
 */
object Vigil {
    fun onAppStart(context: Context) {
        Notifications.createChannels(context)
        onClock(catchUp = true)
        VigilService.start(context)
    }

    // ------------------------------------------------------------------ plan

    fun importPlan(text: String, fileName: String?): PlanRepository.ImportOutcome {
        val now = Instant.now()
        val outcome = Graph.plans.import(text, fileName, now.toEpochMilli())
        val ts = OffsetDateTime.ofInstant(now, Graph.zone).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        when (outcome) {
            is PlanRepository.ImportOutcome.Accepted -> {
                val p = outcome.plan
                Graph.eventLog.append(
                    LogEvent(
                        ts, EventType.PLAN_IMPORTED,
                        detail = "week_of ${p.weekOf}, expires ${p.expires}, generated_by ${p.generatedBy ?: "?"}, " +
                            "${p.directives.size} directives, ${outcome.warnings.size} warnings",
                    ),
                )
                // A new plan starts from now: nothing from before the import is replayed.
                onClock(catchUp = false)
            }
            is PlanRepository.ImportOutcome.Rejected -> Graph.eventLog.append(
                LogEvent(ts, EventType.PLAN_IMPORT_FAILED, detail = outcome.errors.firstOrNull()?.toString()),
            )
        }
        return outcome
    }

    // ------------------------------------------------------------------ triggers

    /** Time triggers: fire what is due, log what was missed, and set the next alarm. */
    fun onClock(catchUp: Boolean) {
        var next: Instant? = null
        exec { state, env ->
            val (outcome, wake) = Scheduling.onClock(state, env, catchUp)
            next = wake
            outcome
        }
        AlarmScheduler.schedule(Graph.app, next)
    }

    /** One or more directives were triggered by an adhan notification or app usage. */
    fun trigger(directiveIds: List<String>, trigger: String) {
        if (directiveIds.isEmpty()) return
        exec { state, env -> Engine.onTrigger(state, env, directiveIds, trigger) }
    }

    /** Debug: show a directive (or the built-in demo) right now, ignoring quiet and bad-day mode. */
    fun fireNow(directiveId: String) {
        exec { state, env -> Engine.fireNow(state, env, directiveId) }
    }

    // ------------------------------------------------------------------ takeover

    fun stepShown(takeoverId: String) {
        exec(launch = false) { state, env -> Engine.stepShown(state, env, takeoverId) }
    }

    fun stepDone(takeoverId: String, index: Int): StepResult {
        val outcome = exec { state, env -> Engine.stepDone(state, env, takeoverId, index) }
        return when {
            outcome.events.any { it.type == EventType.DIRECTIVE_COMPLETED } -> StepResult.COMPLETED
            outcome.events.any { it.type == EventType.STEP_COMPLETED } -> StepResult.NEXT
            else -> StepResult.IGNORED
        }
    }

    fun dismiss(takeoverId: String) {
        exec { state, env -> Engine.dismiss(state, env, takeoverId) }
    }

    /** "I'm not okay today" held on the takeover. Tells it whether it was closed by that. */
    fun notOkay(takeoverId: String): BadDayResult {
        val outcome = setBadDay(true)
        return if (outcome.events.any { it.type == EventType.BYPASSED_BAD_DAY && it.takeoverId == takeoverId }) {
            BadDayResult.BYPASSED
        } else {
            BadDayResult.KEPT
        }
    }

    // ------------------------------------------------------------------ modes

    fun setBadDay(on: Boolean): Outcome = exec { state, env -> Engine.setBadDay(state, env, on) }

    fun setQuiet(on: Boolean): Outcome = exec { state, env -> Engine.setQuiet(state, env, on) }

    /** Developer kill switch: no takeovers for [minutes]; whatever is showing or waiting is dropped. */
    fun suspend(minutes: Long) {
        Graph.settings.suspendedUntilMs = System.currentTimeMillis() + minutes * 60_000
        exec { state, env -> Scheduling.clearAll(state, env, "developer kill switch") }
    }

    fun resume() {
        Graph.settings.suspendedUntilMs = 0
        afterChange()
    }

    // ------------------------------------------------------------------ device events

    /**
     * Every few seconds from the service. Housekeeping only: ends bad-day mode at the day boundary and
     * times out a takeover left open too long.
     */
    fun onServiceTick(context: Context) {
        val before = Graph.state.current
        val outcome = exec(launch = false) { state, env -> Engine.refresh(state, env) }
        if (outcome.state != before) VigilService.refreshNotification(context)
    }

    fun onScreenOn(context: Context) {
        if (Graph.state.current.active != null) TakeoverLauncher.onScreenOn(context)
    }

    fun onScreenOff(context: Context) = Unit

    /** Opening the app while a takeover waits brings the takeover back. */
    fun showIfActive() {
        if (Graph.state.current.active != null) TakeoverLauncher.ensureShowing(Graph.app)
    }

    // ------------------------------------------------------------------ status

    /** One line for the always-on notification. */
    fun statusLine(): String {
        val now = Instant.now()
        if (Graph.settings.isSuspended(now.toEpochMilli())) return "Suspended by the kill switch."
        val state = Graph.state.current
        val env = Graph.env(now)
        val parts = mutableListOf<String>()
        parts += when (env.active.source) {
            PlanSource.PLAN -> "Week of ${env.active.plan?.weekOf}"
            PlanSource.FALLBACK -> "Plan expired: fallback"
            PlanSource.DEFAULT -> "Minimal default plan"
        }
        if (state.quiet) parts += "quiet"
        if (Engine.isBadDay(state, env)) parts += "not okay today"
        nextTimeDirective(env)?.let { parts += "next: $it" }
        return parts.joinToString(" · ")
    }

    private fun nextTimeDirective(env: Env): String? {
        val fire = TimeSchedule.next(env.active.directives, env.now, env.zone) ?: return null
        val title = fire.directiveIds.firstOrNull()?.let { env.active.find(it)?.title } ?: return null
        return "${title.lowercase()} ${Format.dateTime(LocalDateTime.ofInstant(fire.at, env.zone)).lowercase()}"
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Runs one engine step against the current state and world, then applies its side effects.
     * While the developer kill switch is on, anything that fires is dropped straight away (and logged).
     */
    internal fun exec(launch: Boolean = true, step: (RuntimeState, Env) -> Outcome): Outcome {
        val env = Graph.env()
        val suspended = Graph.settings.isSuspended(env.nowMs)
        val outcome = Graph.state.update { current ->
            val o = step(current, env)
            if (suspended && (o.state.active != null || o.state.pending.isNotEmpty())) {
                val cleared = Scheduling.clearAll(o.state, env, "developer kill switch")
                Outcome(cleared.state, o.events + cleared.events)
            } else {
                o
            }
        }
        if (launch || outcome.events.isNotEmpty()) afterChange()
        return outcome
    }

    private fun afterChange() {
        val app = Graph.app
        if (Graph.state.current.active != null) TakeoverLauncher.ensureShowing(app) else TakeoverLauncher.onClosed(app)
        VigilService.refreshNotification(app)
    }
}
