package dev.vigil.engine

import android.content.Context
import dev.vigil.Graph
import dev.vigil.core.engine.Engine
import dev.vigil.core.engine.Env
import dev.vigil.core.engine.Outcome
import dev.vigil.core.engine.RuntimeState
import dev.vigil.core.log.EventType
import dev.vigil.core.log.LogEvent
import dev.vigil.data.PlanRepository
import dev.vigil.takeover.BadDayResult
import dev.vigil.takeover.StepResult
import dev.vigil.takeover.TakeoverLauncher
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The only entry point for things that change what VIGIL does. Each call runs one pure [Engine] step
 * through the state store, then carries out its side effects. No decisions are made here.
 */
object Vigil {
    fun onAppStart(context: Context) {
        exec { state, env -> Engine.refresh(state, env) }
    }

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
                exec { state, env -> Engine.refresh(state, env) }
            }
            is PlanRepository.ImportOutcome.Rejected -> Graph.eventLog.append(
                LogEvent(ts, EventType.PLAN_IMPORT_FAILED, detail = outcome.errors.firstOrNull()?.toString()),
            )
        }
        return outcome
    }

    /** Debug: show a directive (or the built-in demo) right now. */
    fun fireNow(directiveId: String) {
        exec { state, env -> Engine.fireNow(state, env, directiveId) }
        showIfActive()
    }

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

    /** "I'm not okay today" held. Tells the takeover whether it was closed by it. */
    fun notOkay(takeoverId: String): BadDayResult {
        val outcome = setBadDay(true)
        return if (outcome.events.any { it.type == EventType.BYPASSED_BAD_DAY && it.takeoverId == takeoverId }) {
            BadDayResult.BYPASSED
        } else {
            BadDayResult.KEPT
        }
    }

    fun setBadDay(on: Boolean): Outcome = exec { state, env -> Engine.setBadDay(state, env, on) }

    /** Makes sure the takeover is on screen if the engine has one active. */
    fun showIfActive() {
        if (Graph.state.current.active != null) TakeoverLauncher.ensureShowing(Graph.app)
    }

    /** Runs one engine step against the current state and world. */
    internal fun exec(step: (RuntimeState, Env) -> Outcome): Outcome {
        val env = Graph.env()
        return Graph.state.update { step(it, env) }
    }
}
