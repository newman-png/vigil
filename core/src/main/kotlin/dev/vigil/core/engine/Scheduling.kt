package dev.vigil.core.engine

import dev.vigil.core.log.EventType
import dev.vigil.core.log.LogEvent
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Time-trigger processing and other clock-driven engine steps. */
object Scheduling {
    const val MAX_MISSED_LOGGED = 50

    /**
     * Handles everything time-triggered between the last processed moment and now: fires that are due go
     * through [Engine.onTrigger]; fires that passed long ago (phone off, deep sleep) are logged as missed.
     * With [catchUp] false (after an import or a manual clock change) nothing old is replayed.
     *
     * Returns the outcome and the moment the next alarm should go off (or null if nothing is scheduled).
     */
    fun onClock(
        state: RuntimeState,
        env: Env,
        catchUp: Boolean,
        graceMinutes: Long = TimeSchedule.DEFAULT_GRACE_MINUTES,
    ): Pair<Outcome, Instant?> {
        val refreshed = Engine.refresh(state, env)
        var s = refreshed.state
        val events = refreshed.events.toMutableList()
        var cursor = state.scheduleCursorMs?.takeIf { catchUp }?.let(Instant::ofEpochMilli) ?: env.now
        // When the plan just expired, the fallback/built-in directives only count from the moment they took over.
        val switchedAt = env.active.plan?.expires?.atZone(env.zone)?.toInstant()
        if (state.lastSource == PlanSource.PLAN.json && env.active.source != PlanSource.PLAN && switchedAt != null) {
            cursor = maxOf(cursor, minOf(switchedAt, env.now))
        }
        val pass = TimeSchedule.pass(env.active.directives, cursor, env.now, env.zone, graceMinutes)

        // After a very long gap (phone off for weeks) the log gets a summary line instead of hundreds of entries.
        for (fire in pass.missed.take(MAX_MISSED_LOGGED)) {
            for (id in fire.directiveIds) {
                val d = env.active.find(id) ?: continue
                events += LogEvent(
                    env.timestamp(), EventType.MISSED, directiveId = id, tier = d.tier, trigger = "time",
                    scheduledFor = format(fire.at, env),
                )
            }
        }
        if (pass.missed.size > MAX_MISSED_LOGGED) {
            val rest = pass.missed.drop(MAX_MISSED_LOGGED)
            events += LogEvent(
                env.timestamp(), EventType.MISSED, trigger = "time",
                detail = "${rest.sumOf { it.directiveIds.size }} more missed between ${format(rest.first().at, env)} and ${format(rest.last().at, env)}",
            )
        }
        for (fire in pass.due) {
            val r = Engine.onTrigger(s, env, fire.directiveIds, "time", format(fire.at, env))
            s = r.state
            events += r.events
        }
        s = s.copy(scheduleCursorMs = env.nowMs)
        return Outcome(s, events) to nextWakeFor(s, env)
    }

    /**
     * When the app must next wake for [state]: the next time trigger, the plan's expiry, or the moment the active
     * takeover reaches its hard maximum (so the cap holds even if nothing else wakes the app).
     */
    fun nextWakeFor(state: RuntimeState, env: Env): Instant? {
        val fire = TimeSchedule.next(env.active.directives, env.now, env.zone)?.at
        val timeout = state.active?.let {
            Instant.ofEpochMilli((it.activeSinceMs ?: it.firedAtMs) + Engine.MAX_TAKEOVER_MINUTES * 60_000)
        }
        return listOfNotNull(nextWake(fire, env), timeout).minOrNull()
    }

    /**
     * The next moment the app must wake: the next time trigger, or the plan's expiry if that comes first
     * (so the fallback plan's own time triggers are scheduled as soon as it takes over).
     */
    fun nextWake(nextFire: Instant?, env: Env): Instant? {
        val plan = env.active.plan
        val expiry = if (env.active.source == PlanSource.PLAN && plan != null) {
            plan.expires.atZone(env.zone).toInstant().takeIf { it.isAfter(env.now) }
        } else {
            null
        }
        return listOfNotNull(nextFire, expiry).minOrNull()
    }

    /**
     * The developer kill switch: closes everything showing or waiting, without counting it as a failure.
     * Quiet mode and bad-day mode are left as they were.
     */
    fun clearAll(state: RuntimeState, env: Env, reason: String): Outcome {
        val events = (listOfNotNull(state.active) + state.pending).map {
            LogEvent(env.timestamp(), EventType.DROPPED, directiveId = it.directiveId, takeoverId = it.takeoverId, detail = reason)
        }
        return Outcome(state.copy(active = null, pending = emptyList()), events)
    }

    // ISO_OFFSET_DATE_TIME always writes the seconds (toString() drops ":00").
    private fun format(at: Instant, env: Env): String =
        OffsetDateTime.ofInstant(at, env.zone).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
