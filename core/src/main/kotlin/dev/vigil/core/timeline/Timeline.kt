package dev.vigil.core.timeline

import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ProtocolFile
import dev.vigil.core.protocol.Trigger
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
     * that came during this day.
     */
    fun plan(
        file: ProtocolFile,
        times: DayTimes,
        lastAdhan: Map<Prayer, Instant>,
        adhanToday: Map<Prayer, Instant>,
        from: Instant,
        until: Instant,
    ): List<PlannedStart> {
        val out = mutableListOf<PlannedStart>()
        for (p in file.protocols) {
            when (val t = p.parsedTrigger) {
                is Trigger.Time -> times.resolve(t.expr)?.let { out += PlannedStart(p.id, p.title, it, StartSource.TIME) }
                is Trigger.Slot -> times.slots(t.type).forEach { out += PlannedStart(p.id, p.title, it.start, StartSource.SLOT, slot = it) }
                is Trigger.Adhan -> {
                    val actual = adhanToday[t.prayer]
                    val at = actual ?: lastAdhan[t.prayer]?.let { estimate(it, from) }
                    if (at != null) out += PlannedStart(p.id, p.title, at, StartSource.ADHAN, estimated = actual == null, prayer = t.prayer)
                }
                else -> Unit
            }
        }
        return out.filter { !it.at.isBefore(from) && it.at.isBefore(until) }.sortedWith(compareBy({ it.at }, { it.protocolId }))
    }

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
