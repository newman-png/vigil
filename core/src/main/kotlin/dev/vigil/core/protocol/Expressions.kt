package dev.vigil.core.protocol

import dev.vigil.core.common.WallClock
import java.time.LocalTime

/** The result of reading one expression from the data file: the value, or a plain-language reason it is wrong. */
sealed interface Parsed<out T> {
    data class Ok<T>(val value: T) : Parsed<T>

    data class Error(val message: String) : Parsed<Nothing>
}

/** MY TIMES slot lists that `slot:<type>` triggers start from. */
enum class SlotType(val json: String) {
    STUDY("study"),
    MEAL("meal"),
    GYM("gym"),
    CALL("call"),
    COMMUTE("commute"),

    /** Fridays: the Jummah sequence (kind jummah only), from its leave-by time before MY TIMES `jummah_time`. */
    JUMMAH("jummah"),
    ;

    companion object {
        fun fromJson(value: String): SlotType? = entries.firstOrNull { it.json == value }
    }
}

/** The switches on the home screen. QUIET silences takeovers; WALKING and FRIENDS block the pool (and can start protocols). */
enum class ToggleName(val json: String) {
    QUIET("quiet"),
    WALKING("walking"),
    FRIENDS("friends"),
    ;

    companion object {
        fun fromJson(value: String): ToggleName? = entries.firstOrNull { it.json == value }
    }
}

/**
 * What starts a protocol. Written in the data file as text:
 * `event:adhan:<prayer>`, `event:wake_alarm`, `event:after:<protocol-id>`, `event:misses:<n>`,
 * `slot:study|meal|gym|call|commute|jummah`, `slot:meal:<label>`, `time:<expr>`, `toggle:walking|friends`, `manual`.
 */
sealed interface Trigger {
    /** The adhan app's notification for [prayer] (30-minute dedupe). */
    data class Adhan(val prayer: Prayer) : Trigger

    /** The wake alarm (M3: VIGIL rings it itself). */
    data object WakeAlarm : Trigger

    /** Right after that protocol's run completes or its lock ends (not after a bail-out or a miss). */
    data class After(val protocolId: String) : Trigger

    /** After [count] missed or bailed protocols in a row: the protocol is offered (START / NOT NOW), not forced. */
    data class Misses(val count: Int) : Trigger

    /** At the start of every MY TIMES slot of [type] that day; with [label] only the rows with that label (meals). */
    data class Slot(val type: SlotType, val label: String? = null) : Trigger

    /** At the time [expr] resolves to each day. */
    data class Time(val expr: TimeExpr) : Trigger

    /** When the [toggle] switches on; the run ends when it switches off. */
    data class Toggle(val toggle: ToggleName) : Trigger

    /** Only from a button (home or TEST). */
    data object Manual : Trigger

    /** True for triggers the day's timeline can plan (time and slot). */
    val planned: Boolean get() = this is Time || this is Slot

    companion object {
        const val MAX_MISSES = 10

        fun parse(text: String): Parsed<Trigger> {
            val t = text.trim()
            if (t != text || t.isEmpty()) return Parsed.Error("\"$text\" is not a trigger (no spaces; see spec/PROTOCOLS.md).")
            return when {
                t == "manual" -> Parsed.Ok(Manual)
                t == "event:wake_alarm" -> Parsed.Ok(WakeAlarm)
                t.startsWith("event:adhan:") -> {
                    val name = t.removePrefix("event:adhan:")
                    Prayer.fromJson(name)?.let { Parsed.Ok(Adhan(it)) }
                        ?: Parsed.Error("\"$name\" is not a prayer; use fajr, dhuhr, asr, maghrib or isha.")
                }
                t.startsWith("event:after:") -> {
                    val id = t.removePrefix("event:after:")
                    if (ID_PATTERN.matches(id)) Parsed.Ok(After(id)) else Parsed.Error("\"$id\" is not a protocol id.")
                }
                t.startsWith("event:misses:") -> {
                    val n = t.removePrefix("event:misses:").takeIf { INT.matches(it) }?.toIntOrNull()
                    if (n != null && n in 1..MAX_MISSES) {
                        Parsed.Ok(Misses(n))
                    } else {
                        Parsed.Error("event:misses needs a whole number from 1 to $MAX_MISSES, e.g. event:misses:2.")
                    }
                }
                t.startsWith("slot:meal:") -> {
                    val label = t.removePrefix("slot:meal:")
                    if (label in TimeExpr.MEAL_LABELS) {
                        Parsed.Ok(Slot(SlotType.MEAL, label))
                    } else {
                        Parsed.Error("\"$label\" is not a meal; use slot:meal:breakfast, slot:meal:lunch or slot:meal:dinner.")
                    }
                }
                t.startsWith("slot:") -> {
                    val name = t.removePrefix("slot:")
                    SlotType.fromJson(name)?.let { Parsed.Ok(Slot(it)) }
                        ?: Parsed.Error("\"$name\" is not a slot; use study, meal, meal:<label>, gym, call, commute or jummah.")
                }
                t.startsWith("time:") -> when (val e = TimeExpr.parse(t.removePrefix("time:"))) {
                    is Parsed.Ok -> Parsed.Ok(Time(e.value))
                    is Parsed.Error -> e
                }
                t.startsWith("toggle:") -> {
                    val name = t.removePrefix("toggle:")
                    val toggle = ToggleName.fromJson(name)
                    if (toggle == ToggleName.WALKING || toggle == ToggleName.FRIENDS) {
                        Parsed.Ok(Toggle(toggle))
                    } else {
                        Parsed.Error("\"$name\" cannot start a protocol; use toggle:walking or toggle:friends.")
                    }
                }
                t.startsWith("event:") -> Parsed.Error(
                    "\"$t\" is not a known event; use event:adhan:<prayer>, event:wake_alarm, event:after:<id> or event:misses:<n>.",
                )
                else -> Parsed.Error(
                    "\"$t\" is not a trigger; use event:..., slot:..., time:..., toggle:... or manual (see spec/PROTOCOLS.md).",
                )
            }
        }

        private val INT = Regex("^(0|[1-9][0-9]*)$")
    }
}

/** The part of a time expression before the offset. */
sealed interface TimeBase {
    /** MY TIMES wake time of the day's weekday (always the morning of the day). */
    data object Wake : TimeBase

    /**
     * Tonight's target bedtime: sleep shifting's target when the file has a sleep protocol (from MY TIMES bedtime_start
     * towards bedtime_goal), else MY TIMES bedtime_goal.
     */
    data object Bedtime : TimeBase

    /** The moment the wake alarm was dismissed today; MY TIMES wake while it has not been. */
    data object Woke : TimeBase

    /** The start of the day's MY TIMES meal with [label] (breakfast, lunch or dinner). */
    data class Meal(val label: String) : TimeBase

    /** The start of the day's first class. */
    data object ClassStart : TimeBase

    /** The end of the day's last class. */
    data object ClassEnd : TimeBase

    /** A fixed local time; before `day.night_edge` it belongs to the night after the day. */
    data class Clock(val time: LocalTime) : TimeBase
}

/**
 * A time expression: `wake`, `woke`, `bedtime`, `meal:<label>`, `class_start`, `class_end` or `HH:MM`, optionally `+N` /
 * `-N` minutes.
 */
data class TimeExpr(val base: TimeBase, val offsetMin: Int = 0) {
    override fun toString(): String {
        val b = when (base) {
            TimeBase.Wake -> "wake"
            TimeBase.Woke -> "woke"
            TimeBase.Bedtime -> "bedtime"
            is TimeBase.Meal -> "meal:${base.label}"
            TimeBase.ClassStart -> "class_start"
            TimeBase.ClassEnd -> "class_end"
            is TimeBase.Clock -> "%02d:%02d".format(base.time.hour, base.time.minute)
        }
        return when {
            offsetMin > 0 -> "$b+$offsetMin"
            offsetMin < 0 -> "$b$offsetMin"
            else -> b
        }
    }

    companion object {
        const val MAX_OFFSET_MIN = 1440
        val MEAL_LABELS = listOf("breakfast", "lunch", "dinner")
        private val SHAPE = Regex("^(wake|woke|bedtime|class_start|class_end|meal:[a-z]+|[0-9]{2}:[0-9]{2})(([+-])([0-9]{1,4}))?$")

        fun parse(text: String): Parsed<TimeExpr> {
            val m = SHAPE.matchEntire(text)
                ?: return Parsed.Error(
                    "\"$text\" is not a time; use wake, woke, bedtime, meal:<label>, class_start, class_end or HH:MM, " +
                        "optionally followed by +N or -N minutes (e.g. wake+60).",
                )
            val baseText = m.groupValues[1]
            val base = when {
                baseText == "wake" -> TimeBase.Wake
                baseText == "woke" -> TimeBase.Woke
                baseText == "bedtime" -> TimeBase.Bedtime
                baseText == "class_start" -> TimeBase.ClassStart
                baseText == "class_end" -> TimeBase.ClassEnd
                baseText.startsWith("meal:") -> {
                    val label = baseText.removePrefix("meal:")
                    if (label !in MEAL_LABELS) return Parsed.Error("\"$label\" is not a meal; use breakfast, lunch or dinner.")
                    TimeBase.Meal(label)
                }
                else -> TimeBase.Clock(
                    WallClock.parseTime(baseText) ?: return Parsed.Error("\"$baseText\" is not a time of day (00:00 to 23:59)."),
                )
            }
            var offset = 0
            if (m.groupValues[2].isNotEmpty()) {
                val n = m.groupValues[4].toInt()
                if (n > MAX_OFFSET_MIN) return Parsed.Error("The offset in \"$text\" is more than $MAX_OFFSET_MIN minutes.")
                if (m.groupValues[4].length > 1 && m.groupValues[4].startsWith("0")) {
                    return Parsed.Error("Write the offset in \"$text\" without leading zeros.")
                }
                offset = if (m.groupValues[3] == "-") -n else n
            }
            return Parsed.Ok(TimeExpr(base, offset))
        }
    }
}

/**
 * When a free window opens or closes. `<expr>`; `done:<protocol-id>|<expr>` = the moment that protocol's run completed
 * today, else (or if that protocol is not in the file) the expression; `start:<protocol-id>|<expr>` likewise for its
 * start; `max(<anchor>,<anchor>)` = the later of the two (both must exist that day); `next` (closes only) = the next
 * timeline start after the window opened, else the day's end.
 */
sealed interface Anchor {
    data class At(val expr: TimeExpr) : Anchor

    data class Done(val protocolId: String, val fallback: TimeExpr) : Anchor

    data class Start(val protocolId: String, val fallback: TimeExpr) : Anchor

    /** The later of two anchors (neither of them `next` or another `max`). */
    data class Max(val first: Anchor, val second: Anchor) : Anchor

    data object Next : Anchor

    companion object {
        fun parse(text: String, allowNext: Boolean): Parsed<Anchor> {
            if (text == "next") {
                return if (allowNext) Parsed.Ok(Next) else Parsed.Error("\"next\" can only close a window.")
            }
            if (text.startsWith("max(")) {
                val parts = text.removePrefix("max(").removeSuffix(")").split(',')
                if (!text.endsWith(")") || parts.size != 2 || parts.any { it.startsWith("max(") || it == "next" }) {
                    return Parsed.Error(
                        "\"$text\" must be max(<anchor>,<anchor>): two anchors, no spaces, e.g. max(done:morning|woke+60,woke+60).",
                    )
                }
                val first = parse(parts[0], allowNext = false)
                if (first is Parsed.Error) return first
                val second = parse(parts[1], allowNext = false)
                if (second is Parsed.Error) return second
                return Parsed.Ok(Max((first as Parsed.Ok).value, (second as Parsed.Ok).value))
            }
            for ((prefix, make) in listOf<Pair<String, (String, TimeExpr) -> Anchor>>("done:" to ::Done, "start:" to ::Start)) {
                if (!text.startsWith(prefix)) continue
                val rest = text.removePrefix(prefix)
                val bar = rest.indexOf('|')
                if (bar < 0) return Parsed.Error("\"$text\" needs a fallback time after |, e.g. ${prefix}morning|wake+60.")
                val id = rest.substring(0, bar)
                if (!ID_PATTERN.matches(id)) return Parsed.Error("\"$id\" in \"$text\" is not a protocol id.")
                return when (val e = TimeExpr.parse(rest.substring(bar + 1))) {
                    is Parsed.Ok -> Parsed.Ok(make(id, e.value))
                    is Parsed.Error -> e
                }
            }
            return when (val e = TimeExpr.parse(text)) {
                is Parsed.Ok -> Parsed.Ok(At(e.value))
                is Parsed.Error -> e
            }
        }
    }
}

/** Protocol and window ids: lowercase letters, digits, - and _, starting with a letter or digit, at most 48 characters. */
val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,47}$")
