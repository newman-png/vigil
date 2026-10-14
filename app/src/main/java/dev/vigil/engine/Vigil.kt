package dev.vigil.engine

import android.app.KeyguardManager
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
import dev.vigil.core.plan.CheckInAnswer
import dev.vigil.core.plan.DayMode
import dev.vigil.core.plan.Rating
import dev.vigil.data.PlanRepository
import dev.vigil.service.VigilService
import dev.vigil.takeover.BadDayResult
import dev.vigil.takeover.StepResult
import dev.vigil.takeover.TakeoverLauncher
import dev.vigil.trigger.AlarmScheduler
import dev.vigil.trigger.UsageWatcher
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
                UsageWatcher.reset()
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
        exec { state, env -> Engine.stepShown(state, env, takeoverId) }
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

    /** True when the engine wants EASY / OK / HARD for this just-completed takeover. */
    fun pendingRatingFor(takeoverId: String): Boolean = Graph.state.current.pendingRating?.takeoverId == takeoverId

    /** The difficulty report after a completion; null = skipped. */
    fun rate(takeoverId: String, rating: Rating?) {
        exec { state, env -> Engine.rate(state, env, takeoverId, rating) }
    }

    /** The morning check-in after a bad day was answered. */
    fun answerCheckIn(takeoverId: String, answer: CheckInAnswer) {
        exec { state, env -> Engine.answerCheckIn(state, env, takeoverId, answer) }
    }

    /** "I'm not okay today" held on the takeover. Tells it whether it was closed by that. */
    fun notOkay(takeoverId: String): BadDayResult {
        val outcome = setBadDay(true)
        // A check-in on screen is replaced by the bad day (one check_in event) rather than bypassed.
        val closed = outcome.events.any {
            it.takeoverId == takeoverId && (it.type == EventType.BYPASSED_BAD_DAY || it.type == EventType.CHECK_IN)
        }
        return if (closed) {
            BadDayResult.BYPASSED
        } else {
            BadDayResult.KEPT
        }
    }

    // ------------------------------------------------------------------ modes

    fun setBadDay(on: Boolean): Outcome = exec { state, env -> Engine.setBadDay(state, env, on) }

    fun setQuiet(on: Boolean): Outcome = exec { state, env -> Engine.setQuiet(state, env, on) }

    /** Today's mode: normal, bad_day ("I'm not okay today") or minimum (chosen by the check-in). */
    fun dayMode(): DayMode = Engine.dayMode(Graph.state.current, Graph.env())

    /**
     * The imported plan has expired (the fallback or built-in plan runs) and its weekly bundle has not been
     * exported since. The app then shows PLAN COMPLETE with its one EXPORT button.
     */
    fun planComplete(): Boolean {
        val snapshot = Graph.plans.snapshot.value
        val plan = snapshot.plan ?: return false
        if (Graph.activeSet().source == PlanSource.PLAN) return false
        val s = Graph.settings
        val expiredAt = plan.expires.atZone(Graph.zone).toInstant().toEpochMilli()
        val exported = s.lastBundleExportImportedAtMs == (snapshot.importedAtMs ?: -1L) && s.lastBundleExportAtMs >= expiredAt
        return !exported
    }

    /** Developer kill switch: no takeovers for [minutes]; whatever is showing or waiting is dropped. */
    fun suspend(minutes: Long) {
        Graph.settings.suspendedUntilMs = System.currentTimeMillis() + minutes * 60_000
        exec { state, env -> Scheduling.clearAll(state, env, "developer kill switch") }
    }

    fun resume() {
        Graph.settings.suspendedUntilMs = 0
        VigilService.refreshNotification(Graph.app)
    }

    // ------------------------------------------------------------------ device events

    /**
     * Every few seconds from the service: housekeeping (ends bad-day mode at the day boundary, times out a
     * takeover left open too long) and app-usage polling.
     */
    fun onServiceTick(context: Context) {
        exec { state, env -> Engine.refresh(state, env) }
        UsageWatcher.poll(context, System.currentTimeMillis())
    }

    fun onScreenOn(context: Context) {
        // The morning check-in waits for the user to be there: an awake, unlocked phone. It never wakes the screen.
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (!locked && Device.isInteractive(context)) onUserPresent()
        if (Graph.state.current.active != null) TakeoverLauncher.onScreenOn(context)
    }

    /** The user is at the phone (unlocked it, or opened VIGIL): show a due morning check-in. */
    fun onUserPresent() {
        // Always ask the engine: a check-in only becomes due in its housekeeping at the day boundary.
        exec { state, env -> Engine.onUserPresent(state, env) }
    }

    fun onScreenOff(context: Context) = UsageWatcher.onScreenOff(System.currentTimeMillis())


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
        when (Engine.dayMode(state, env)) {
            DayMode.BAD_DAY -> parts += "not okay today"
            DayMode.MINIMUM -> parts += "minimum day"
            DayMode.NORMAL -> Unit
        }
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
     * Runs one engine step against the current state and world, then applies its side effects. Only a
     * takeover that has just become active is put on screen (and may wake it); one that was already
     * waiting comes back through the screen-on, unlock and escape paths instead.
     * While the developer kill switch is on, anything that fires is dropped straight away (and logged).
     */
    internal fun exec(step: (RuntimeState, Env) -> Outcome): Outcome {
        val env = Graph.env()
        val before = Graph.state.current
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
        if (outcome.state != before || outcome.events.isNotEmpty()) afterChange(before, outcome, env)
        return outcome
    }

    private fun afterChange(before: RuntimeState, outcome: Outcome, env: Env) {
        val app = Graph.app
        val after = outcome.state
        // The week is over: say so once, so the bundle gets exported for the next plan.
        if (outcome.events.any { it.type == EventType.PLAN_EXPIRED }) Notifications.planComplete(app)
        val active = after.active
        when {
            active == null -> TakeoverLauncher.onClosed(app)
            active.takeoverId != before.active?.takeoverId -> TakeoverLauncher.ensureShowing(app)
        }
        // A new active takeover moves the next wake-up (its hard maximum); so do mode changes.
        if (active?.takeoverId != before.active?.takeoverId) AlarmScheduler.schedule(app, Scheduling.nextWakeFor(after, env))
        if (after != before) VigilService.refreshNotification(app)
    }
}
