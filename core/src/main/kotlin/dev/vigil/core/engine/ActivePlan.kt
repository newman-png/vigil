package dev.vigil.core.engine

import dev.vigil.core.plan.AfterBadDay
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
    /**
     * Directive ids that still fire on a "minimum" day (a mode only the morning check-in can choose): minimum.keep, or
     * bad_day.keep when the plan has no `minimum`.
     */
    val minimumKeep: Set<String> = emptySet(),
    /** Which mode the morning check-in after a bad or minimum day picks for each answer. Null: no check-in. */
    val afterBadDay: AfterBadDay? = null,
) {
    fun find(id: String): Directive? = directives.firstOrNull { it.id == id }
}

object ActivePlanResolver {
    /**
     * Picks what to run. Expiry never makes the app go silent: an expired plan drops to its fallback_plan,
     * and without one to the built-in minimal plan (prayer, water, sleep).
     *
     * On a bad day and on a minimum day the fallback plan keeps everything: it has no keep lists of its own (the
     * plan's lists can only name the plan's own directives) and is already minimal. Its morning check-in still follows
     * the plan's after_bad_day. The built-in plan uses its own lists (it has no minimum or after_bad_day).
     *
     * A plan without `minimum` cannot choose a minimum day, but one can still be running when it takes over (chosen
     * by the check-in of the plan before, or before the switch to the built-in plan). Then its bad_day.keep applies, so
     * the essentials still fire.
     */
    fun resolve(plan: Plan?, builtinDefault: Plan, now: LocalDateTime): ActiveSet {
        if (plan != null && now.isBefore(plan.expires)) {
            return ActiveSet(
                PlanSource.PLAN,
                plan.directives,
                plan.badDay.keep.toSet(),
                plan.dayBoundary,
                plan,
                minimumKeep = minimumKeep(plan),
                afterBadDay = plan.afterBadDay,
            )
        }
        val fallback = plan?.fallbackPlan
        if (plan != null && fallback != null) {
            val all = fallback.directives.map { it.id }.toSet()
            return ActiveSet(
                PlanSource.FALLBACK,
                fallback.directives,
                all,
                plan.dayBoundary,
                plan,
                minimumKeep = all,
                afterBadDay = plan.afterBadDay,
            )
        }
        return ActiveSet(
            PlanSource.DEFAULT,
            builtinDefault.directives,
            builtinDefault.badDay.keep.toSet(),
            plan?.dayBoundary ?: builtinDefault.dayBoundary,
            plan,
            minimumKeep = minimumKeep(builtinDefault),
            afterBadDay = builtinDefault.afterBadDay,
        )
    }

    /** minimum.keep, or bad_day.keep for a plan without `minimum` (see [resolve]). */
    private fun minimumKeep(plan: Plan): Set<String> = (plan.minimum?.keep ?: plan.badDay.keep).toSet()

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
