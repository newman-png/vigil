package dev.vigil.core.plan

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * In-memory form of plan.json (see /spec/schema.json).
 *
 * The app never decides anything about the plan: these types only describe what the planner wrote.
 * [PlanParser] is the only way to build them from JSON, and it is the place that reports problems.
 */
data class Plan(
    val schemaVersion: Int,
    val weekOf: java.time.LocalDate,
    val expires: LocalDateTime,
    val generatedBy: String?,
    val context: String?,
    /** When a "day" ends for bad-day mode and daily bookkeeping. Defaults to 04:00. */
    val dayBoundary: LocalTime,
    val directives: List<Directive>,
    val badDay: BadDay,
    /** Directives to run once [expires] has passed. Null when the planner gave none. */
    val fallbackPlan: FallbackPlan?,
    /** Directives that still fire on a "minimum" day (a mode only `after_bad_day` can choose). Null when the planner gave none. */
    val minimum: Minimum? = null,
    /** Which mode the morning check-in after a bad day picks for each answer. Null: no check-in, the next day is normal. */
    val afterBadDay: AfterBadDay? = null,
) {
    fun directive(id: String): Directive? =
        directives.firstOrNull { it.id == id } ?: fallbackPlan?.directives?.firstOrNull { it.id == id }

    companion object {
        /** The newest plan.json version this app reads, and the one planners are told to write. Version 1 still loads. */
        const val SUPPORTED_SCHEMA_VERSION = 2
        val DEFAULT_DAY_BOUNDARY: LocalTime = LocalTime.of(4, 0)
    }
}

data class BadDay(val keep: List<String>) {
    companion object {
        val NONE = BadDay(emptyList())
    }
}

data class FallbackPlan(val directives: List<Directive>)

/** `minimum.keep`: the only directives that fire on a minimum day. */
data class Minimum(val keep: List<String>)

/** `after_bad_day`: the mode for the day of each check-in answer. */
data class AfterBadDay(val better: DayMode, val same: DayMode, val worse: DayMode) {
    fun modeFor(answer: CheckInAnswer): DayMode = when (answer) {
        CheckInAnswer.BETTER -> better
        CheckInAnswer.SAME -> same
        CheckInAnswer.WORSE -> worse
    }
}

/** How much of the plan runs today. Only the user ("I'm not okay today") and the check-in change it. */
enum class DayMode(val json: String) {
    /** Everything fires. */
    NORMAL("normal"),

    /** Only `bad_day.keep` fires. */
    BAD_DAY("bad_day"),

    /** Only `minimum.keep` fires. */
    MINIMUM("minimum");

    companion object {
        fun fromJson(value: String): DayMode? = entries.firstOrNull { it.json == value }
    }
}

/** The user's answer to the morning check-in after a bad day. */
enum class CheckInAnswer(val json: String) {
    BETTER("better"),
    SAME("same"),
    WORSE("worse");

    companion object {
        fun fromJson(value: String): CheckInAnswer? = entries.firstOrNull { it.json == value }
    }
}

/** The one-tap difficulty report after a completed directive. */
enum class Rating(val json: String) {
    EASY("easy"),
    OK("ok"),
    HARD("hard");

    companion object {
        fun fromJson(value: String): Rating? = entries.firstOrNull { it.json == value }
    }
}

/** When the difficulty report is shown after a directive is completed (`rating`). */
enum class RatingPolicy(val json: String) {
    /** After every completion. */
    ALWAYS("always"),

    /** After the 1st, 4th, 7th, ... completion in the plan (every [SAMPLE_EVERY]th). */
    SAMPLE("sample"),

    /** Never. */
    NEVER("never");

    companion object {
        const val SAMPLE_EVERY = 3

        /** What a directive gets when the plan leaves `rating` out: sample for tier 1, always for tiers 2-3. */
        fun defaultFor(tier: Int): RatingPolicy = if (tier <= 1) SAMPLE else ALWAYS

        fun fromJson(value: String): RatingPolicy? = entries.firstOrNull { it.json == value }
    }
}

/**
 * A version of a directive the planner wrote in advance (`variants.easier`). The app only switches to it,
 * never invents one: see [Directive.stepDownAfterHard].
 */
data class Variant(val title: String?, val steps: List<Step>, val why: String?) {
    companion object {
        /** The only variant name in this version. */
        const val EASIER = "easier"
    }
}

data class Directive(
    val id: String,
    /** 1 = nudge (one step), 2 = short checklist, 3 = full sequence. */
    val tier: Int,
    /** Headline typed out on the takeover. Falls back to the id when the plan has none. */
    val title: String,
    val trigger: Trigger,
    val steps: List<Step>,
    /** One line shown at the bottom of the takeover. */
    val why: String,
    val quiet: QuietPolicy,
    /** When to ask EASY / OK / HARD after it is completed. */
    val rating: RatingPolicy = RatingPolicy.defaultFor(tier),
    /** `variants.easier`, if the planner wrote one. */
    val easier: Variant? = null,
    /**
     * `adapt.step_down_after_hard`: when this many ratings in a row are HARD, [easier] runs for the rest of the plan.
     * The app never steps back up by itself; only a new plan does.
     */
    val stepDownAfterHard: Int? = null,
) {
    /** This directive as it runs with [variant] (e.g. [Variant.EASIER]); unchanged for null or a variant it lacks. */
    fun withVariant(variant: String?): Directive {
        val v = easier
        if (variant != Variant.EASIER || v == null) return this
        return copy(title = v.title ?: title, steps = v.steps, why = v.why ?: why)
    }
}

data class Step(val text: String, val minSeconds: Int)

/** What a directive does while quiet mode is on. */
enum class QuietPolicy(val json: String) {
    /** Held, and fired when quiet mode ends. */
    QUEUE("queue"),

    /** Shown without sound or vibration. */
    SILENT("silent"),

    /** Skipped entirely. */
    IGNORE("ignore"),

    /** Fires normally, with sound, even in quiet mode. Tier 3 only. */
    OVERRIDE("override");

    companion object {
        fun fromJson(value: String): QuietPolicy? = entries.firstOrNull { it.json == value }
    }
}

enum class Prayer(val json: String) {
    FAJR("fajr"),
    DHUHR("dhuhr"),
    ASR("asr"),
    MAGHRIB("maghrib"),
    ISHA("isha");

    companion object {
        fun fromJson(value: String): Prayer? = entries.firstOrNull { it.json == value }
    }
}

sealed interface Trigger {
    /** The trigger "type" string as written in plan.json. */
    val type: String

    /** Fires at a wall-clock time on the given days (all seven when the plan omits "days"). */
    data class Time(val at: LocalTime, val days: Set<DayOfWeek>) : Trigger {
        override val type get() = TYPE

        companion object {
            const val TYPE = "time"
        }
    }

    /** Fires when the configured adhan app posts a notification for this prayer. */
    data class Adhan(val prayer: Prayer) : Trigger {
        override val type get() = TYPE

        companion object {
            const val TYPE = "adhan"
        }
    }

    /** Fires when the listed apps are opened too often, or used for too long in one go. */
    data class AppUsage(
        val apps: List<String>,
        val limit: UsageLimit,
        /** Minutes after firing during which this trigger stays quiet. */
        val cooldownMin: Int,
    ) : Trigger {
        override val type get() = TYPE

        companion object {
            const val TYPE = "app_usage"
        }
    }

    /**
     * A trigger type this version of the app does not know (for example the reserved "geofence").
     * The directive is kept so nothing in the plan is lost, but it never fires.
     */
    data class Unsupported(override val type: String) : Trigger

    companion object {
        val SUPPORTED_TYPES = listOf(Time.TYPE, Adhan.TYPE, AppUsage.TYPE)

        /** Reserved for future versions; accepted with a warning and ignored. */
        val RESERVED_TYPES = listOf("geofence", "rule", "if_then", "lockdown")
    }
}

sealed interface UsageLimit {
    /** Any of the apps opened [opens] times within [windowMin] minutes. */
    data class Opens(val opens: Int, val windowMin: Int) : UsageLimit

    /** Any of the apps in the foreground for [minutes] minutes without a break. */
    data class Continuous(val minutes: Int) : UsageLimit
}
