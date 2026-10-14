package dev.vigil.ui

import dev.vigil.core.log.LogEvent
import dev.vigil.core.plan.Trigger
import dev.vigil.core.plan.UsageLimit
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Short, uppercase, human descriptions for the UI. */
object Format {
    private val dateTime = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
    private val day = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    /** MON 26 OCT 04:00 */
    fun dateTime(t: LocalDateTime): String = dateTime.format(t).uppercase()

    /** 19 OCT */
    fun day(d: LocalDate): String = day.format(d).uppercase()

    /** 14:05 */
    fun clock(t: LocalTime): String = clock.format(t)

    /** 14:05 for a moment in epoch milliseconds. */
    fun clock(epochMs: Long, zone: ZoneId): String = clock(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime())

    /** MON 26 OCT 04:00 for a moment in epoch milliseconds. */
    fun dateTime(epochMs: Long, zone: ZoneId): String = dateTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone))

    fun trigger(t: Trigger, appLabel: (String) -> String = { it }): String = when (t) {
        is Trigger.Time -> "TIME ${t.at} ${days(t.days)}"
        is Trigger.Adhan -> "ADHAN ${t.prayer.json.uppercase()}"
        is Trigger.AppUsage -> {
            val apps = t.apps.joinToString(", ") { appLabel(it) }
            when (val l = t.limit) {
                is UsageLimit.Opens -> "${l.opens} OPENS / ${l.windowMin} MIN: $apps"
                is UsageLimit.Continuous -> "${l.minutes} MIN NONSTOP: $apps"
            }
        }
        is Trigger.Unsupported -> "UNSUPPORTED \"${t.type}\" (NEVER FIRES)"
    }

    fun days(days: Set<DayOfWeek>): String = when {
        days.size == 7 -> "DAILY"
        days == WEEKDAYS -> "MON-FRI"
        days == WEEKEND -> "SAT-SUN"
        else -> days.sorted().joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase() }
    }

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}S"
        seconds < 3600 -> "${seconds / 60}M ${seconds % 60}S"
        else -> "${seconds / 3600}H ${(seconds % 3600) / 60}M"
    }

    /** One compact line for the TEST screen's event list: "SUN 14:05 DIRECTIVE FIRED water-am DEBUG". */
    fun event(e: LogEvent): String {
        val at = runCatching { OffsetDateTime.parse(e.ts) }.getOrNull()
        val time = at?.let { "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase()} ${clock(it.toLocalTime())}" }
            ?: e.ts.take(16)
        return listOfNotNull(
            time,
            e.type.uppercase().replace('_', ' '),
            e.directiveId,
            e.stepIndex?.let { "STEP ${it + 1}" },
            e.rating?.uppercase(),
            e.answer?.uppercase(),
            e.mode?.uppercase()?.replace('_', ' '),
            e.variant?.uppercase(),
            e.trigger?.uppercase(),
        ).joinToString(" ")
    }

    private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
}
