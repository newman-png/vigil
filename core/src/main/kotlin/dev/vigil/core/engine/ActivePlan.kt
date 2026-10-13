package dev.vigil.core.engine

import dev.vigil.core.plan.Directive
import dev.vigil.core.plan.ParseResult
import dev.vigil.core.plan.Plan
import dev.vigil.core.plan.PlanParser
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Where the directives currently being executed come from. */
enum class PlanSource(val json: String) {
    /** The imported plan, before its `expires`. */
    PLAN("plan"),

    /** The imported plan has expired; running its `fallback_plan`. */
    FALLBACK("fallback_plan"),

    /** No usable plan (none imported, or expired without a fallback): running the built-in minimal plan. */
    DEFAULT("builtin_default"),
}

/** The directives the app executes right now. */
data class ActiveSet(
    val source: PlanSource,
    val directives: List<Directive>,
    /** Directive ids that still fire in "I'm not okay today" mode. */
    val badDayKeep: Set<String>,
    val dayBoundary: LocalTime,
    /** The imported plan, if any (also set when it has expired). */
    val plan: Plan?,
) {
    fun find(id: String): Directive? = directives.firstOrNull { it.id == id }
}

object ActivePlanResolver {
    /**
     * Picks what to run. Expiry never makes the app go silent: an expired plan drops to its fallback_plan,
     * and without one to the built-in minimal plan (prayer, water, sleep).
     *
     * On a bad day the fallback plan keeps everything: it has no bad_day list of its own (the plan's list can only
     * name the plan's own directives) and is already minimal. The built-in plan uses its own bad_day.keep.
     */
    fun resolve(plan: Plan?, builtinDefault: Plan, now: LocalDateTime): ActiveSet {
        if (plan != null && now.isBefore(plan.expires)) {
            return ActiveSet(PlanSource.PLAN, plan.directives, plan.badDay.keep.toSet(), plan.dayBoundary, plan)
        }
        val fallback = plan?.fallbackPlan
        if (plan != null && fallback != null) {
            return ActiveSet(
                PlanSource.FALLBACK,
                fallback.directives,
                fallback.directives.map { it.id }.toSet(),
                plan.dayBoundary,
                plan,
            )
        }
        return ActiveSet(
            PlanSource.DEFAULT,
            builtinDefault.directives,
            builtinDefault.badDay.keep.toSet(),
            plan?.dayBoundary ?: builtinDefault.dayBoundary,
            plan,
        )
    }

    /** The calendar day a moment belongs to, where days start at [boundary] (e.g. 01:30 belongs to yesterday). */
    fun dayKey(now: LocalDateTime, boundary: LocalTime): LocalDate =
        if (now.toLocalTime().isBefore(boundary)) now.toLocalDate().minusDays(1) else now.toLocalDate()
}

/** The built-in minimal plan, bundled as a resource so it is validated by the same parser and tests. */
object BuiltinPlan {
    const val RESOURCE = "/dev/vigil/core/default-plan.json"

    val text: String by lazy {
        BuiltinPlan::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
            ?: error("Missing built-in plan resource $RESOURCE")
    }

    val plan: Plan by lazy {
        when (val result = PlanParser.parse(text)) {
            is ParseResult.Success -> result.plan
            is ParseResult.Failure -> error("Built-in plan is invalid: ${result.errors}")
        }
    }
}
