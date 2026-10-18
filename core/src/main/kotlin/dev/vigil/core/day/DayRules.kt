package dev.vigil.core.day

import dev.vigil.core.common.WallClock
import dev.vigil.core.protocol.DayConfig
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Why a day ended (design section 1, "Day"). */
enum class DayEndReason(val json: String) {
    /** GOING TO SLEEP was tapped. */
    SLEEP_TAP("sleep_tap"),

    /** The screen stayed off for `idle_end_min` once wind-down had started; the day ended when it went off. */
    IDLE("idle"),

    /** The wake alarm rang the morning after. */
    WAKE_ALARM("wake_alarm"),

    /** Nothing else ended it by `fallback_end` the morning after. */
    FALLBACK("fallback"),
    ;

    companion object {
        fun fromJson(value: String): DayEndReason? = entries.firstOrNull { it.json == value }
    }
}

/** A day end found by [DayRules.dueEnd]: why, and the moment the day ended (can be in the past, e.g. idle). */
data class DayEnd(val reason: DayEndReason, val at: Instant)

/**
 * The day boundary (design section 1): a day ends when GOING TO SLEEP is tapped, after `idle_end_min` of screen off once
 * wind-down has started (ending at the moment the screen went off), at the wake alarm the morning after, or at
 * `fallback_end` the morning after, whichever comes first. A new day starts at once; its key is the calendar date of
 * (end + 6 h), and always later than the day that ended.
 */
object DayRules {
    /** The key offset: a day that ends at 23:30 belongs to tomorrow, one that ends at 06:00 to today. */
    val KEY_SHIFT: Duration = Duration.ofHours(6)

    /** The key of the day that starts at [end]: date(end + 6 h), and at least the day after [previous]. */
    fun keyAfter(end: Instant, zone: ZoneId, previous: LocalDate?): LocalDate {
        val key = WallClock.date(end.plus(KEY_SHIFT), zone)
        return if (previous != null && !key.isAfter(previous)) previous.plusDays(1) else key
    }

    /**
     * The key of the very first day (no previous end to count from): today, or yesterday when [now] is still before
     * the fallback end (the night still belongs to the day before).
     */
    fun firstKey(now: Instant, zone: ZoneId, cfg: DayConfig): LocalDate {
        val local = now.atZone(zone)
        val fallback = fallbackTime(cfg)
        return if (local.toLocalTime() < fallback) local.toLocalDate().minusDays(1) else local.toLocalDate()
    }

    /** The latest moment day [key] can last: `fallback_end` on the next calendar date. */
    fun fallbackEnd(key: LocalDate, cfg: DayConfig, zone: ZoneId): Instant = WallClock.at(key.plusDays(1), fallbackTime(cfg), zone)

    /**
     * Where the idle count starts: when the screen went off, but not before wind-down started. Null while the screen is
     * on or wind-down has not started (or is unknown).
     */
    fun idleStart(screenOffSince: Instant?, windDownAt: Instant?): Instant? {
        if (screenOffSince == null || windDownAt == null) return null
        return maxOf(screenOffSince, windDownAt)
    }

    /**
     * The end that is due at [now] for day [key], or null while the day goes on. Of idle and fallback, the one detected
     * first wins: idle is detected `idle_end_min` after its start (and ends the day at its start); the fallback at its
     * own moment.
     */
    fun dueEnd(
        key: LocalDate,
        now: Instant,
        zone: ZoneId,
        cfg: DayConfig,
        screenOffSince: Instant?,
        windDownAt: Instant?,
    ): DayEnd? {
        val fallback = fallbackEnd(key, cfg, zone)
        val idleFrom = idleStart(screenOffSince, windDownAt)
        val idleDetected = idleFrom?.plus(Duration.ofMinutes(cfg.idleEndMin.toLong()))
        return when {
            idleFrom != null && idleDetected != null && !now.isBefore(idleDetected) && idleDetected.isBefore(fallback) ->
                DayEnd(DayEndReason.IDLE, idleFrom)
            !now.isBefore(fallback) -> DayEnd(DayEndReason.FALLBACK, fallback)
            else -> null
        }
    }

    /** The next moment a timed end could happen for day [key] (idle detection or fallback), for alarms. */
    fun nextCheck(key: LocalDate, zone: ZoneId, cfg: DayConfig, screenOffSince: Instant?, windDownAt: Instant?): Instant {
        val fallback = fallbackEnd(key, cfg, zone)
        val idleFrom = idleStart(screenOffSince, windDownAt) ?: return fallback
        return minOf(fallback, idleFrom.plus(Duration.ofMinutes(cfg.idleEndMin.toLong())))
    }

    /** True when a wake alarm at [now] ends day [key]: it rings on a later calendar date than the key (the morning after). */
    fun wakeAlarmEndsDay(key: LocalDate, now: Instant, zone: ZoneId): Boolean = WallClock.date(now, zone).isAfter(key)

    private fun fallbackTime(cfg: DayConfig): LocalTime = WallClock.parseTime(cfg.fallbackEnd) ?: LocalTime.of(6, 0)
}
