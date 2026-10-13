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
) {
    fun directive(id: String): Directive? =
        directives.firstOrNull { it.id == id } ?: fallbackPlan?.directives?.firstOrNull { it.id == id }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        val DEFAULT_DAY_BOUNDARY: LocalTime = LocalTime.of(4, 0)
    }
}

data class BadDay(val keep: List<String>) {
    companion object {
        val NONE = BadDay(emptyList())
    }
}

data class FallbackPlan(val directives: List<Directive>)

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
)

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
