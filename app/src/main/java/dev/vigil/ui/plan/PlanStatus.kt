package dev.vigil.ui.plan

import dev.vigil.core.engine.ActiveSet
import dev.vigil.core.engine.PlanSource
import dev.vigil.ui.Format

/** The one-line plan status under the home screen's title. */
fun planStatusLine(active: ActiveSet): String = when (active.source) {
    PlanSource.PLAN -> active.plan?.let { "WEEK OF ${Format.day(it.weekOf)} · EXPIRES ${Format.dateTime(it.expires)}" } ?: "PLAN RUNNING"
    PlanSource.FALLBACK -> "PLAN COMPLETE · FALLBACK RUNNING"
    PlanSource.DEFAULT -> if (active.plan == null) "NO PLAN · MINIMAL PLAN RUNNING" else "PLAN COMPLETE · MINIMAL PLAN RUNNING"
}

/** What runs while there is no current plan, e.g. "FALLBACK RUNNING: FAJR, WATER, SLEEP". */
fun runningInstead(active: ActiveSet): String = when (active.source) {
    PlanSource.PLAN -> "PLAN RUNNING"
    PlanSource.FALLBACK -> {
        val titles = active.directives.map { it.title.uppercase() }.distinct()
        val shown = titles.take(MAX_TITLES).joinToString(", ")
        "FALLBACK RUNNING: " + if (titles.size > MAX_TITLES) "$shown ..." else shown
    }
    // The built-in plan: the five prayers, water and sleep.
    PlanSource.DEFAULT -> "MINIMAL PLAN RUNNING: PRAYERS, WATER, SLEEP"
}

/** Where the running directives come from, for the PLAN screen. */
fun sourceName(source: PlanSource): String = when (source) {
    PlanSource.PLAN -> "THE PLAN"
    PlanSource.FALLBACK -> "ITS FALLBACK PLAN"
    PlanSource.DEFAULT -> "BUILT-IN MINIMAL PLAN"
}

private const val MAX_TITLES = 5
