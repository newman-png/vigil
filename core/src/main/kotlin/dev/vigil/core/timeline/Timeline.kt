package dev.vigil.core.timeline

import dev.vigil.core.common.WallClock
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.Protocol
import dev.vigil.core.protocol.ProtocolFile
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.SlotType
import dev.vigil.core.protocol.Trigger
import dev.vigil.core.run.JummahInfo
import dev.vigil.core.times.DayTimes
import dev.vigil.core.times.ResolvedSlot
import java.time.Duration
import java.time.Instant

/** Where a planned start comes from. */
enum class StartSource(val json: String) {
    /** A `time:<expr>` trigger. */
    TIME("time"),

    /** A `slot:<type>` trigger and a MY TIMES row. */
    SLOT("slot"),

    /** A prayer: its adhan today, or the estimate from the last adhan (+24 h). */
    ADHAN("adhan"),
}

/** One start the day expects. */
data class PlannedStart(
    val protocolId: String,
    val title: String,
    val at: Instant,
    val source: StartSource,
    /** True for a prayer whose adhan has not come yet today (the time is yesterday's adhan + 24 h). */
    val estimated: Boolean = false,
    /** The MY TIMES row for a slot start. */
    val slot: ResolvedSlot? = null,
    val prayer: Prayer? = null,
) {
    /** Identifies this start within the day (protocol and moment). */
    val key: String get() = "$protocolId@${at.epochSecond}"
}

/** A planned start with what happened to it. */
enum class StartStatus {
    /** Still ahead (or within its grace period). */
    PLANNED,

    /** It fired (prayer: the adhan came). */
    FIRED,

    /** It came due, but nothing started (suspended, or the protocol was already running). */
    SKIPPED,

    /** Its moment passed and it never fired. The day-end self-check logs these as not_fired. */
    NOT_FIRED,
}

/** A line of the home screen's timeline. */
data class TimelineItem(
    val start: PlannedStart,
    val status: StartStatus,
    /** When this protocol last fired (any day), for "last fired" on the home screen. */
    val lastFired: Instant?,
)

/**
 * The day's planned starts (design: timeline): every `time:` and `slot:` trigger resolved against MY TIMES for the day,
 * plus each prayer at its adhan (or its estimate), between the day's start and its latest end. DST-safe: local times
 * go through [DayTimes]; prayer estimates add whole 24-hour periods to the last adhan, which follows the sun across a
 * clock change.
 */
object Timeline {
    /**
     * The starts in [from, until), by time. [lastAdhan] is the most recent adhan per prayer; [adhanToday] the adhans
     * that came during this day. [next] is the next day's times: its early `time:` and `slot:` starts (on its own date
     * before this day's latest end, e.g. `time:05:30` or `time:wake` at 05:30 before a 06:00 fallback end) happen while
     * this day still runs, so they are this day's.
     */
    fun plan(
        file: ProtocolFile,
        times: DayTimes,
        lastAdhan: Map<Prayer, Instant>,
        adhanToday: Map<Prayer, Instant>,
        from: Instant,
        until: Instant,
        next: DayTimes? = null,
    ): List<PlannedStart> {
        val out = mutableListOf<PlannedStart>()
        for (p in file.protocols) {
            when (val t = p.parsedTrigger) {
                is Trigger.Time -> {
                    times.resolve(t.expr)?.let { out += PlannedStart(p.id, p.title, it, StartSource.TIME) }
                    next?.resolve(t.expr)?.let { if (early(it, next)) out += PlannedStart(p.id, p.title, it, StartSource.TIME) }
                }
                // Jummah starts at its leave-by time (the whole Friday sequence follows from it).
                is Trigger.Slot -> if (t.type == SlotType.JUMMAH) {
                    jummah(p, times)?.let { out += PlannedStart(p.id, p.title, it.leaveAt, StartSource.SLOT) }
                    next?.let { jummah(p, it) }?.leaveAt?.let { if (early(it, next)) out += PlannedStart(p.id, p.title, it, StartSource.SLOT) }
                } else {
                    rows(file, p, t, times).forEach { out += PlannedStart(p.id, p.title, it.start, StartSource.SLOT, slot = it) }
                    next?.let { rows(file, p, t, it) }?.filter { early(it.start, next) }
                        ?.forEach { out += PlannedStart(p.id, p.title, it.start, StartSource.SLOT, slot = it) }
                }
                is Trigger.Adhan -> {
                    val actual = adhanToday[t.prayer]
                    val at = actual ?: lastAdhan[t.prayer]?.let { estimate(it, from) }
                    if (at != null) out += PlannedStart(p.id, p.title, at, StartSource.ADHAN, estimated = actual == null, prayer = t.prayer)
                }
                else -> Unit
            }
        }
        return out.filter { !it.at.isBefore(from) && it.at.isBefore(until) }
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.at }, { it.protocolId }))
    }

    /**
     * Fridays: the Jummah times for [p] (kind jummah, design M4) from MY TIMES jummah_time, commute_min and the Friday
     * classes; null on other days or with an unreadable jummah_time. The target is the first Friday class (before the
     * lock), else the lock itself: leave-by = target - commute_min - leave_buffer_min, I'M AT UNI from target -
     * arrive_before_min, the lock at jummah_time - lock_before_min, the card card_before_end_min before the last
     * class that ends by jummah_time, missed at jummah_time + missed_after_min.
     */
    fun jummah(p: Protocol, times: DayTimes): JummahInfo? {
        if (times.dayCode != "fri") return null
        val time = WallClock.parseTime(times.times.jummahTime) ?: return null
        fun param(name: String): Duration = Duration.ofMinutes(p.intParam(name).toLong())
        val jummahAt = times.at(time)
        val lockAt = jummahAt.minus(param("lock_before_min"))
        val target = times.classStart?.takeIf { it.isBefore(lockAt) } ?: lockAt
        val leaveAt = target.minus(Duration.ofMinutes(times.times.commuteMin.toLong())).minus(param("leave_buffer_min"))
        val arriveAt = maxOf(leaveAt, target.minus(param("arrive_before_min")))
        val lastEnd = times.classes.mapNotNull { it.end }.filter { it.isAfter(leaveAt) && !it.isAfter(jummahAt) }.maxOrNull()
        return JummahInfo(
            jummahAt = jummahAt, leaveAt = leaveAt, arriveAt = arriveAt, cardAt = lastEnd?.minus(param("card_before_end_min")),
            lockAt = lockAt, missedAt = jummahAt.plus(param("missed_after_min")),
        )
    }

    /**
     * The MY TIMES rows slot trigger [t] of [p] starts from on [times]' day: the trigger's label only (`slot:meal:lunch`),
     * and no breakfast for a meal protocol while the file has a morning routine (its breakfast step covers it).
     */
    private fun rows(file: ProtocolFile, p: Protocol, t: Trigger.Slot, times: DayTimes): List<ResolvedSlot> =
        times.slots(t.type).filter { row ->
            val label = t.label
            (label == null || row.label.equals(label, ignoreCase = true)) &&
                !(p.kind == ProtocolKind.MEAL && t.type == SlotType.MEAL && row.label.equals(BREAKFAST, ignoreCase = true) &&
                    file.has(ProtocolKind.MORNING))
        }

    private const val BREAKFAST = "breakfast"

    /** A start on [next]'s own date (its morning; the [until] filter keeps those this day can still reach). */
    private fun early(at: Instant, next: DayTimes): Boolean = WallClock.date(at, next.zone) == next.date

    /** The first moment at or after [from] that is [last] plus a whole number of 24-hour periods. */
    fun estimate(last: Instant, from: Instant): Instant {
        val day = Duration.ofDays(1)
        var at = last
        if (at.isBefore(from)) {
            val days = Duration.between(at, from).toDays()
            at = at.plus(day.multipliedBy(days))
            if (at.isBefore(from)) at = at.plus(day)
        }
        return at
    }
}
