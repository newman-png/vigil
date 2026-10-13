package dev.vigil.core.engine

import dev.vigil.core.plan.Trigger
import dev.vigil.core.plan.UsageLimit

/**
 * Counts app opens and continuous use for `app_usage` triggers. Fed with "this package is now in the
 * foreground" events (from UsageStatsManager polling or the accessibility service) and periodic ticks.
 *
 * An "open" is a switch from any app outside a trigger's list to an app in it. Turning the screen off
 * (or VIGIL's own takeover appearing) counts as leaving, so coming back counts as a new open and
 * restarts continuous use. Not thread-safe: call from one thread.
 *
 * Pass every call the same map of all the active plan's app_usage triggers (directive id -> trigger):
 * a directive missing from the map is forgotten, so a filtered map would wipe the other counts.
 */
class AppUsageTracker {
    private class State {
        val opens = ArrayDeque<Long>()
        var continuousSince: Long? = null
        var lastFiredAt: Long? = null
    }

    private val states = mutableMapOf<String, State>()
    private var current: String? = null

    /** The package currently in the foreground, as last reported (null = screen off or unknown). */
    val foreground: String? get() = current

    /**
     * Reports that [packageName] came to the foreground at [atMs] (null when the screen turned off).
     * Returns the directive ids whose trigger fired.
     */
    fun onForeground(packageName: String?, atMs: Long, triggers: Map<String, Trigger.AppUsage>): List<String> {
        val previous = current
        current = packageName
        prune(triggers.keys)
        val fired = mutableListOf<String>()
        for ((id, trigger) in triggers) {
            val s = states.getOrPut(id) { State() }
            val wasIn = previous != null && previous in trigger.apps
            val isIn = packageName != null && packageName in trigger.apps
            when (val limit = trigger.limit) {
                is UsageLimit.Opens -> {
                    if (isIn && !wasIn) {
                        s.opens.addLast(atMs)
                        dropOld(s, atMs, limit.windowMin)
                        if (s.opens.size >= limit.opens && !coolingDown(s, trigger, atMs)) {
                            fired += id
                            s.lastFiredAt = atMs
                            s.opens.clear()
                        }
                    }
                }
                is UsageLimit.Continuous -> {
                    if (isIn && !wasIn) s.continuousSince = atMs
                    if (!isIn) s.continuousSince = null
                    if (isIn && checkContinuous(s, trigger, limit, atMs)) fired += id
                }
            }
        }
        return fired
    }

    /** Call periodically (e.g. every few seconds while the screen is on) so continuous use is noticed. */
    fun onTick(atMs: Long, triggers: Map<String, Trigger.AppUsage>): List<String> {
        prune(triggers.keys)
        val pkg = current ?: return emptyList()
        val fired = mutableListOf<String>()
        for ((id, trigger) in triggers) {
            val limit = trigger.limit as? UsageLimit.Continuous ?: continue
            if (pkg !in trigger.apps) continue
            val s = states.getOrPut(id) { State() }
            if (s.continuousSince == null) s.continuousSince = atMs
            if (checkContinuous(s, trigger, limit, atMs)) fired += id
        }
        return fired
    }

    /** Opens counted for a directive as of its latest open (for the settings/debug screen). */
    fun openCount(directiveId: String): Int = states[directiveId]?.opens?.size ?: 0

    /** Forget all counts, e.g. after a new plan is imported. */
    fun reset() {
        states.clear()
    }

    private fun checkContinuous(s: State, trigger: Trigger.AppUsage, limit: UsageLimit.Continuous, atMs: Long): Boolean {
        val since = s.continuousSince ?: return false
        if (atMs - since < limit.minutes * MINUTE) return false
        if (coolingDown(s, trigger, atMs)) return false
        s.lastFiredAt = atMs
        // Another full stretch of use is needed before it can fire again.
        s.continuousSince = atMs
        return true
    }

    private fun coolingDown(s: State, trigger: Trigger.AppUsage, atMs: Long): Boolean {
        val last = s.lastFiredAt ?: return false
        return atMs - last < trigger.cooldownMin * MINUTE
    }

    private fun dropOld(s: State, atMs: Long, windowMin: Int) {
        val cutoff = atMs - windowMin * MINUTE
        while (s.opens.isNotEmpty() && s.opens.first() <= cutoff) s.opens.removeFirst()
    }

    private fun prune(active: Set<String>) {
        if (states.keys.any { it !in active }) states.keys.retainAll(active)
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
