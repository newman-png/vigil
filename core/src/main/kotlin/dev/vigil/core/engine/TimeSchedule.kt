package dev.vigil.core.engine

import dev.vigil.core.plan.Directive
import dev.vigil.core.plan.Trigger
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** One moment at which one or more time-triggered directives fire. */
data class TimeFire(val at: Instant, val directiveIds: List<String>)

/** What to do after waking up (from an alarm, a reboot or a clock change). */
data class SchedulePass(
    /** Fires whose moment passed more than the grace period ago: logged as missed, not shown. */
    val missed: List<TimeFire>,
    /** Fires that are due now (within the grace period): show them. */
    val due: List<TimeFire>,
    /** The next fire to set an alarm for, or null when nothing is time-triggered. */
    val next: TimeFire?,
)

/**
 * Wall-clock maths for `time` triggers. Times are local to [ZoneId], so a 05:30 directive stays at 05:30
 * across daylight-saving changes. A time that does not exist on a spring-forward day fires right after the
 * gap; a time that happens twice on a fall-back day fires once, at the first occurrence.
 */
object TimeSchedule {
    const val DEFAULT_GRACE_MINUTES = 30L

    /** The earliest fire strictly after [after], with every directive due at that same moment. */
    fun next(directives: List<Directive>, after: Instant, zone: ZoneId): TimeFire? {
        var best: Instant? = null
        val ids = mutableListOf<String>()
        for (d in directives) {
            val t = d.trigger as? Trigger.Time ?: continue
            val at = nextOccurrence(t, after, zone) ?: continue
            when {
                best == null || at.isBefore(best) -> {
                    best = at
                    ids.clear()
                    ids += d.id
                }
                at == best -> ids += d.id
            }
        }
        return best?.let { TimeFire(it, ids.toList()) }
    }

    /**
     * Everything that happened in (cursor, now], plus the next fire after [now].
     * [cursor] is the last moment already handled; pass `now` after importing a plan so nothing old fires.
     */
    fun pass(
        directives: List<Directive>,
        cursor: Instant,
        now: Instant,
        zone: ZoneId,
        graceMinutes: Long = DEFAULT_GRACE_MINUTES,
    ): SchedulePass {
        val missed = mutableListOf<TimeFire>()
        val due = mutableListOf<TimeFire>()
        val graceStart = now.minusSeconds(graceMinutes * 60)
        var from = cursor
        // A week contains at most 7 * 1440 distinct minutes; the guard only protects against a broken clock.
        var guard = 0
        while (from.isBefore(now) && guard++ < 20_000) {
            val fire = next(directives, from, zone) ?: break
            if (fire.at.isAfter(now)) break
            if (fire.at.isBefore(graceStart)) missed += fire else due += fire
            from = fire.at
        }
        return SchedulePass(missed, due, next(directives, now, zone))
    }

    private fun nextOccurrence(t: Trigger.Time, after: Instant, zone: ZoneId): Instant? {
        val start = after.atZone(zone).toLocalDate()
        // Today plus the next seven days always contains the next matching weekday.
        for (offset in 0..8L) {
            val date = start.plusDays(offset)
            if (date.dayOfWeek !in t.days) continue
            val candidate = ZonedDateTime.of(date, t.at, zone).toInstant()
            if (candidate.isAfter(after)) return candidate
        }
        return null
    }
}
