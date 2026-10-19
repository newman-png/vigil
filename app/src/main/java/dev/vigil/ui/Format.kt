package dev.vigil.ui

import dev.vigil.core.common.WallClock
import dev.vigil.core.journal.Entry
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.pool.PoolText
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Short, uppercase, human descriptions for the UI. */
object Format {
    private val dayTime = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
    private val dateTime = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
    private val date = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** 14:05 */
    fun clock(at: Instant, zone: ZoneId): String = WallClock.hhmm(at, zone)

    /** SUN 14:05 */
    fun dayTime(at: Instant, zone: ZoneId): String = dayTime.format(at.atZone(zone)).uppercase()

    /** SUN 18 OCT 14:05 */
    fun dateTime(at: Instant, zone: ZoneId): String = dateTime.format(at.atZone(zone)).uppercase()

    /** SUN 18 OCT */
    fun date(d: LocalDate): String = date.format(d).uppercase()

    /** A day key (yyyy-MM-dd) as SUN 18 OCT, or the key itself when it is not a date. */
    fun dayKey(key: String): String = runCatching { date(LocalDate.parse(key)) }.getOrDefault(key)

    /**
     * When something happened, as short as it can be: 14:05 today, SAT 14:05 within the last week, else the date.
     */
    fun ago(at: Instant, now: Instant, zone: ZoneId): String {
        val day = at.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when {
            day == today -> clock(at, zone)
            day.isAfter(today.minusDays(7)) && !day.isAfter(today) -> dayTime(at, zone)
            else -> dateTime(at, zone)
        }
    }

    /** Day codes ("mon".."sun") as DAILY, MON-FRI, SAT-SUN or "MON WED". */
    fun days(codes: List<String>): String {
        val set = codes.toSet()
        val all = WallClock.DAY_CODES.keys
        return when {
            set.containsAll(all) -> "DAILY"
            set == WEEKDAYS -> "MON-FRI"
            set == WEEKEND -> "SAT-SUN"
            set.isEmpty() -> "NO DAYS"
            else -> all.filter { it in set }.joinToString(" ") { it.uppercase() }
        }
    }

    /** What the pool would do: ALLOWED, WAIT 3 MIN, or BLOCKED with the first reason and its end. */
    fun decision(d: PoolDecision, zone: ZoneId): String = when (d) {
        PoolDecision.Allowed -> "ALLOWED (A SESSION IS RUNNING, OR VIGIL IS SUSPENDED)"
        is PoolDecision.Wait -> "WAIT ${d.minutes} MIN, THEN OPEN"
        is PoolDecision.Blocked -> "BLOCKED · " + PoolText.block(d.blocks.first(), zone)
    }

    /**
     * One compact line for the TEST screen's event list:
     * "SUN 14:05 FIRED PRAYER-DHUHR · STAGE 1 · ADHAN · TEST".
     */
    fun entry(e: Entry): String {
        val at = try {
            OffsetDateTime.parse(e.ts)
        } catch (ex: DateTimeParseException) {
            null
        }
        val time = at?.let { dayTime.format(it).uppercase() } ?: e.ts.take(16)
        val head = listOfNotNull(time, e.type.uppercase().replace('_', ' '), e.protocol?.uppercase()).joinToString(" ")
        val details = listOfNotNull(
            e.prayer?.uppercase(),
            e.step?.let { "STEP ${it + 1}" },
            e.version?.uppercase(),
            e.stage?.let { "STAGE $it" },
            e.app,
            // A gym set: SQUAT 5 × 60 KG.
            e.exercise?.let { ex -> "${ex.uppercase()} ${e.reps ?: 0} × ${e.weight?.let { w -> if (w % 1.0 == 0.0) w.toLong().toString() else w.toString() } ?: "0"} KG" },
            e.minutes?.let { "$it MIN" },
            e.reason?.uppercase()?.replace('_', ' '),
            e.detail,
            e.text?.let { "\"$it\"" },
            e.day,
            e.until?.let { u -> "UNTIL " + (runCatching { OffsetDateTime.parse(u).toLocalTime().toString().take(5) }.getOrNull() ?: u) },
            if (e.test == true) "TEST" else null,
        ).distinct()
        return if (details.isEmpty()) head else head + " · " + details.joinToString(" · ")
    }

    private val WEEKDAYS = setOf("mon", "tue", "wed", "thu", "fri")
    private val WEEKEND = setOf("sat", "sun")
}
