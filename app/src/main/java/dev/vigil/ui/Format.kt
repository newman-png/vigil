package dev.vigil.ui

import dev.vigil.core.plan.Trigger
import dev.vigil.core.plan.UsageLimit
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Short, uppercase, human descriptions for the pixel UI. */
object Format {
    private val dateTime = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)

    fun dateTime(t: LocalDateTime): String = dateTime.format(t).uppercase()

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

    private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
}
