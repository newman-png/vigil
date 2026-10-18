package dev.vigil.core.times

import dev.vigil.core.common.WallClock
import dev.vigil.core.protocol.SlotType
import dev.vigil.core.protocol.TimeBase
import dev.vigil.core.protocol.TimeExpr
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * One row of a MY TIMES list: on [days] ("mon".."sun") from [start] (`HH:MM`), until [end] or for [minutes].
 * [label] says what it is: the meal (breakfast, lunch, dinner), what to study, who to call.
 */
@Serializable
data class Slot(
    val days: List<String>,
    val start: String,
    val end: String? = null,
    val minutes: Int? = null,
    val label: String = "",
)

/**
 * The user's times (design 2.2), edited on the MY TIMES screen. A change applies from the next day: the app keeps a
 * [MyTimesStore] and its pending copy becomes current at each day start.
 */
@Serializable
data class MyTimes(
    /** Wake time per weekday code (default 07:00 every day). */
    val wake: Map<String, String> = WallClock.DAY_CODES.keys.associateWith { DEFAULT_WAKE },
    /** Where sleep shifting aims: tonight's target moves earlier each night until it reaches this. */
    @SerialName("bedtime_goal") val bedtimeGoal: String = "23:00",
    /** Where sleep shifting starts (the first night's target); the target is never later than this. */
    @SerialName("bedtime_start") val bedtimeStart: String = "00:30",
    val classes: List<Slot> = emptyList(),
    /** label breakfast, lunch or dinner; dinner anchors the evening free window. */
    val meals: List<Slot> = DEFAULT_MEALS,
    /** label = what to study. */
    val study: List<Slot> = emptyList(),
    val gym: List<Slot> = emptyList(),
    /** label = who; start = reminder time; end = deadline (default 16:00); minutes = wrap-up after (default 20). */
    val calls: List<Slot> = emptyList(),
    /** Starts walking mode automatically (M8). */
    val commute: List<Slot> = emptyList(),
    @SerialName("commute_min") val commuteMin: Int = 60,
    /** Friday prayer time at uni (M4). */
    @SerialName("jummah_time") val jummahTime: String = "14:40",
) {
    /** Problems in plain language for the MY TIMES screen; empty when everything is usable. */
    fun problems(): List<String> {
        val out = mutableListOf<String>()
        for ((code, time) in wake) {
            if (code !in WallClock.DAY_CODES) out += "Wake: \"$code\" is not a day (use mon to sun)."
            val t = WallClock.parseTime(time)
            if (t == null) {
                out += "Wake ${code.uppercase()}: \"$time\" is not a time (HH:MM)."
            } else if (t < EARLIEST_WAKE || t > LATEST_WAKE) {
                out += "Wake ${code.uppercase()}: $time is outside $EARLIEST_WAKE to $LATEST_WAKE (the wake alarm rings at this time)."
            }
        }
        if (WallClock.parseTime(bedtimeGoal) == null) out += "Bedtime goal: \"$bedtimeGoal\" is not a time (HH:MM)."
        if (WallClock.parseTime(bedtimeStart) == null) out += "Bedtime start: \"$bedtimeStart\" is not a time (HH:MM)."
        if (WallClock.parseTime(jummahTime) == null) out += "Jummah time: \"$jummahTime\" is not a time (HH:MM)."
        if (commuteMin !in 0..300) out += "Commute: $commuteMin minutes is not from 0 to 300."
        for ((name, list) in lists()) {
            list.forEachIndexed { i, s ->
                val row = "${name.replaceFirstChar { it.uppercase() }} row ${i + 1}"
                if (s.days.isEmpty()) out += "$row: pick at least one day."
                s.days.filter { it !in WallClock.DAY_CODES }.forEach { out += "$row: \"$it\" is not a day (use mon to sun)." }
                val start = WallClock.parseTime(s.start)
                if (start == null) out += "$row: start \"${s.start}\" is not a time (HH:MM)."
                if (s.end != null && WallClock.parseTime(s.end) == null) out += "$row: end \"${s.end}\" is not a time (HH:MM)."
                if (s.minutes != null && s.minutes !in 1..600) out += "$row: ${s.minutes} minutes is not from 1 to 600."
                if (name == "classes" && s.end == null && s.minutes == null) out += "$row: a class needs an end or minutes."
                if (name == "meals" && s.label !in TimeExpr.MEAL_LABELS) out += "$row: the label must be breakfast, lunch or dinner."
            }
        }
        return out
    }

    /** Every slot list by its MY TIMES name. */
    fun lists(): List<Pair<String, List<Slot>>> = listOf(
        "classes" to classes, "meals" to meals, "study" to study, "gym" to gym, "calls" to calls, "commute" to commute,
    )

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val DEFAULT_WAKE = "07:00"

        /** The wake times MY TIMES accepts (the wake alarm rings at them). */
        val EARLIEST_WAKE: LocalTime = LocalTime.of(4, 0)
        val LATEST_WAKE: LocalTime = LocalTime.of(11, 0)
        const val DEFAULT_CALL_DEADLINE = "16:00"
        const val DEFAULT_CALL_WRAP_UP_MIN = 20

        /** Every day at breakfast 07:30, lunch 13:00 and dinner 19:30, so the evening window has its dinner anchor. */
        val DEFAULT_MEALS: List<Slot> = listOf(
            Slot(WallClock.DAY_CODES.keys.toList(), "07:30", label = "breakfast"),
            Slot(WallClock.DAY_CODES.keys.toList(), "13:00", label = "lunch"),
            Slot(WallClock.DAY_CODES.keys.toList(), "19:30", label = "dinner"),
        )

        internal val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            coerceInputValues = true
        }

        /** Reads MY TIMES; damaged or missing text gives the defaults. */
        fun decode(text: String?): MyTimes =
            text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: MyTimes()
    }
}

/**
 * What the app stores (design 2.2): the [current] times, and the [pending] edit that becomes current at the next
 * day start. [edited] is false until the first edit, which may apply at once (first setup).
 */
@Serializable
data class MyTimesStore(
    val current: MyTimes = MyTimes(),
    val pending: MyTimes? = null,
    val edited: Boolean = false,
) {
    /** The times tomorrow will use. */
    val upcoming: MyTimes get() = pending ?: current

    /** At a day start: the pending edit (if any) becomes current. */
    fun atDayStart(): MyTimesStore = if (pending == null) this else copy(current = pending, pending = null)

    /** An edit from the MY TIMES screen; applies from the next day, or at once for the very first edit when [applyNow]. */
    fun edit(times: MyTimes, applyNow: Boolean = false): MyTimesStore =
        if (applyNow && !edited) MyTimesStore(current = times, pending = null, edited = true)
        else copy(pending = times.takeIf { it != current }, edited = true)

    fun encode(): String = MyTimes.JSON.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String?): MyTimesStore =
            text?.let { runCatching { MyTimes.JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: MyTimesStore()
    }
}

/** Which MY TIMES list a resolved slot comes from. */
enum class SlotList { CLASS, MEAL, STUDY, GYM, CALL, COMMUTE, JUMMAH }

/** A slot placed on a real day. */
data class ResolvedSlot(
    val list: SlotList,
    val label: String,
    val start: Instant,
    /** The end (explicit end, or start + minutes); null when the row has neither. */
    val end: Instant?,
    val minutes: Int?,
)

/**
 * MY TIMES resolved for one day key [date] (design 2.2): per-weekday values and the time expressions of the data file.
 * `HH:MM` values earlier than [nightEdge] belong to the night after the day (a 00:30 bedtime is after midnight);
 * `wake` always lies on the day itself. Every instant is DST-safe ([WallClock.at]). [bedtimeTarget] is tonight's target
 * from sleep shifting (null = bedtime_goal); [woke] is when the wake alarm was dismissed that day (null = not yet).
 */
class DayTimes(
    val date: LocalDate,
    val zone: ZoneId,
    val nightEdge: LocalTime,
    val times: MyTimes,
    val bedtimeTarget: LocalTime? = null,
    val woke: Instant? = null,
) {
    /** The weekday code of the day ("mon".."sun"). */
    val dayCode: String = WallClock.dayCode(date)

    /** The local time on this day, moved to the next calendar date when it is before the night edge. */
    fun at(time: LocalTime, nightRule: Boolean = true): Instant {
        val d = if (nightRule && time < nightEdge) date.plusDays(1) else date
        return WallClock.at(d, time, zone)
    }

    /** MY TIMES wake for this weekday (07:00 when unset or broken). */
    val wake: Instant = at(WallClock.parseTime(times.wake[dayCode]) ?: LocalTime.of(7, 0), nightRule = false)

    /** Tonight's target bedtime: [bedtimeTarget], else bedtime_goal. */
    val bedtime: Instant = at(bedtimeTarget ?: WallClock.parseTime(times.bedtimeGoal) ?: LocalTime.of(23, 0))

    /** Every class today, by start. */
    val classes: List<ResolvedSlot> = resolve(SlotList.CLASS, times.classes)

    /** Every meal today, by start. */
    val meals: List<ResolvedSlot> = resolve(SlotList.MEAL, times.meals)

    /** The first class's start, or null on a day without classes. */
    val classStart: Instant? = classes.minOfOrNull { it.start }

    /** The last class's end, or null on a day without classes (or without class ends). */
    val classEnd: Instant? = classes.mapNotNull { it.end }.maxOrNull()

    /** The start of today's meal with [label], or null. */
    fun meal(label: String): Instant? = meals.firstOrNull { it.label.equals(label, ignoreCase = true) }?.start

    /** The slots that `slot:<type>` starts from today, by start. */
    fun slots(type: SlotType): List<ResolvedSlot> = when (type) {
        SlotType.STUDY -> resolve(SlotList.STUDY, times.study)
        SlotType.MEAL -> meals
        SlotType.GYM -> resolve(SlotList.GYM, times.gym)
        SlotType.CALL -> resolve(SlotList.CALL, times.calls)
        SlotType.COMMUTE -> resolve(SlotList.COMMUTE, times.commute)
        SlotType.JUMMAH -> {
            val t = WallClock.parseTime(times.jummahTime)
            if (dayCode == "fri" && t != null) listOf(ResolvedSlot(SlotList.JUMMAH, "jummah", at(t), null, null)) else emptyList()
        }
    }

    /** The class in progress at [at] (start <= at < end), or null. */
    fun classAt(at: Instant): ResolvedSlot? = classes.firstOrNull { c -> c.end != null && !at.isBefore(c.start) && at.isBefore(c.end) }

    /**
     * When the class in progress at [at] is over, following back-to-back classes (one starting by the time the
     * previous ends): null when no class is in progress.
     */
    fun classBlockEnd(at: Instant): Instant? {
        var end = classAt(at)?.end ?: return null
        while (true) {
            val next = classes.filter { it.end != null && !it.start.isAfter(end) && it.end.isAfter(end) }.maxByOrNull { it.end!! }
                ?: return end
            end = next.end!!
        }
    }

    /** The instant [expr] resolves to today, or null when its anchor does not exist today (no class, no such meal). */
    fun resolve(expr: TimeExpr): Instant? {
        val base = when (val b = expr.base) {
            TimeBase.Wake -> wake
            TimeBase.Woke -> woke ?: wake
            TimeBase.Bedtime -> bedtime
            is TimeBase.Meal -> meal(b.label)
            TimeBase.ClassStart -> classStart
            TimeBase.ClassEnd -> classEnd
            is TimeBase.Clock -> at(b.time)
        } ?: return null
        return base.plus(Duration.ofMinutes(expr.offsetMin.toLong()))
    }

    private fun resolve(list: SlotList, slots: List<Slot>): List<ResolvedSlot> = slots
        .filter { dayCode in it.days }
        .mapNotNull { s ->
            val startTime = WallClock.parseTime(s.start) ?: return@mapNotNull null
            val start = at(startTime)
            val endTime = WallClock.parseTime(s.end ?: if (list == SlotList.CALL) MyTimes.DEFAULT_CALL_DEADLINE else null)
            var end = endTime?.let { at(it) }
            if (end != null && !end.isAfter(start)) end = end.plus(Duration.ofDays(1))
            val minutes = s.minutes ?: when (list) {
                SlotList.CALL -> MyTimes.DEFAULT_CALL_WRAP_UP_MIN
                SlotList.COMMUTE -> times.commuteMin
                else -> null
            }
            if (end == null && minutes != null && list != SlotList.CALL) end = start.plus(Duration.ofMinutes(minutes.toLong()))
            ResolvedSlot(list, s.label, start, end, minutes)
        }
        .sortedBy { it.start }
}
