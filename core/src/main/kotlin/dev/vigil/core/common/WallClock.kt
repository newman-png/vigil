package dev.vigil.core.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Wall-clock helpers shared by the v2 core. Every local time is turned into an [Instant] the same way, so a
 * 05:30 start stays at 05:30 across daylight-saving changes: a time skipped by a spring-forward gap happens at
 * the end of the gap, and a time that happens twice on a fall-back night happens once, at its first occurrence.
 */
object WallClock {
    /** `HH:MM`, 00:00 to 23:59, always two digits each. */
    val TIME_PATTERN = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

    /** Day codes as the data file and MY TIMES write them, Monday first. */
    val DAY_CODES: Map<String, DayOfWeek> = linkedMapOf(
        "mon" to DayOfWeek.MONDAY,
        "tue" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY,
    )

    /** The day code ("mon".."sun") of [date]. */
    fun dayCode(date: LocalDate): String = DAY_CODES.entries.first { it.value == date.dayOfWeek }.key

    /** Parses `HH:MM`, or null when [text] is not exactly that shape. */
    fun parseTime(text: String?): LocalTime? {
        if (text == null || !TIME_PATTERN.matches(text)) return null
        return LocalTime.of(text.substring(0, 2).toInt(), text.substring(3, 5).toInt())
    }

    /** The instant of local [date] at [time] in [zone] (gap: end of the gap; overlap: first occurrence). */
    fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Instant {
        val local = LocalDateTime.of(date, time)
        // java.time would move a skipped time forward by the gap's length (02:30 -> 03:30), after later times.
        val transition = zone.rules.getTransition(local)
        if (transition != null && transition.isGap) return transition.instant
        // In an overlap atZone picks the earlier offset, i.e. the first occurrence.
        return local.atZone(zone).toInstant()
    }

    /** The local calendar date of [at]. */
    fun date(at: Instant, zone: ZoneId): LocalDate = at.atZone(zone).toLocalDate()

    /** Local time with offset and seconds, e.g. `2026-10-19T05:12:03+02:00` (the journal's `ts`). */
    fun stamp(at: Instant, zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.ofInstant(at, zone).truncatedTo(ChronoUnit.SECONDS))

    /** `HH:MM` local time of [at], for user-facing text. */
    fun hhmm(at: Instant, zone: ZoneId): String = at.atZone(zone).toLocalTime().format(HHMM)

    /** Whole minutes from [from] to [to], rounded up (0 when [to] is not after [from]). */
    fun minutesCeil(from: Instant, to: Instant): Int {
        val s = Duration.between(from, to).seconds
        return if (s <= 0) 0 else ((s + 59) / 60).toInt()
    }

    /** [seconds] as whole minutes, rounded up. */
    fun minutesCeil(seconds: Long): Int = if (seconds <= 0) 0 else ((seconds + 59) / 60).toInt()

    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
}

/** Stores an [Instant] as ISO-8601 text (`2026-10-19T03:12:00Z`) so the persisted state stays readable. */
object InstantIso : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.vigil.core.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
