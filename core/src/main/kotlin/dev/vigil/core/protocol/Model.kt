package dev.vigil.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The bundled data file `protocols.json`: the global rules (pool, whitelist, day boundary, takeover, stages) and
 * every protocol. Edited only with Claude Code and checked by [ProtocolParser]; see spec/PROTOCOLS.md for every field.
 */
@Serializable
data class ProtocolFile(
    /** Free text for whoever edits the file. Ignored by the app. */
    @SerialName("_readme") val readme: String? = null,
    /** Format version; must be [SUPPORTED_VERSION]. */
    val version: Int,
    val pool: PoolConfig = PoolConfig(),
    /** The apps the takeover's APPS button offers (the whitelist). */
    @SerialName("apps_button") val appsButton: List<AppsButtonEntry> = emptyList(),
    val day: DayConfig = DayConfig(),
    val takeover: TakeoverConfig = TakeoverConfig(),
    val stages: StagesConfig = StagesConfig(),
    val protocols: List<Protocol> = emptyList(),
) {
    /** The protocol with [id], or null. */
    fun find(id: String): Protocol? = protocols.firstOrNull { it.id == id }

    /** The prayer protocol for [prayer] (trigger `event:adhan:<prayer>`), or null when the file has none. */
    fun prayerProtocol(prayer: Prayer): Protocol? =
        protocols.firstOrNull { (it.parsedTrigger as? Trigger.Adhan)?.prayer == prayer }

    /** True when the file has a protocol of [kind]. */
    fun has(kind: ProtocolKind): Boolean = protocols.any { it.kind == kind }

    companion object {
        const val SUPPORTED_VERSION = 2
    }
}

/** The blocked apps with one shared counter, and the friction rules around them (design section 3). */
@Serializable
data class PoolConfig(
    /** Package names in the pool. Every installed browser is added when [allBrowsers] is true. */
    val apps: List<String> = emptyList(),
    @SerialName("all_browsers") val allBrowsers: Boolean = true,
    /** The Nth open of the day waits N x this many minutes ... */
    @SerialName("wait_step_min") val waitStepMin: Int = 1,
    /** ... but never more than this. */
    @SerialName("wait_cap_min") val waitCapMin: Int = 10,
    /** After a session the pool locks for this many times the minutes used ... */
    @SerialName("cooldown_factor") val cooldownFactor: Double = 2.0,
    /** ... but never longer than this. */
    @SerialName("cooldown_cap_min") val cooldownCapMin: Int = 60,
    /** Leaving the pool for less than this keeps the session going. */
    @SerialName("session_gap_s") val sessionGapS: Int = 60,
    /** The only times the pool can be opened at all. */
    val windows: List<FreeWindowConfig> = emptyList(),
    @SerialName("heavy_use") val heavyUse: HeavyUseConfig = HeavyUseConfig(),
    @SerialName("bail_out") val bailOut: BailOutConfig = BailOutConfig(),
)

/** A free window: opens and closes at anchors resolved each day, with a cap on the pool minutes used inside it. */
@Serializable
data class FreeWindowConfig(
    val id: String,
    /** An anchor: `<expr>`, `done:<protocol-id>|<expr>` or `start:<protocol-id>|<expr>`. */
    val opens: String,
    /** An anchor like [opens], or `next` (the next timeline start after the window opened, else the day's end). */
    val closes: String,
    @SerialName("cap_min") val capMin: Int,
)

/** When a session counts as heavy use: [continuousMin] minutes in one session, or [opens] opens within [withinMin] minutes. */
@Serializable
data class HeavyUseConfig(
    @SerialName("continuous_min") val continuousMin: Int = 10,
    val opens: Int = 3,
    @SerialName("within_min") val withinMin: Int = 30,
)

/** A bail-out locks the pool for [extraLockMin] more minutes (stacking), at most [dailyCapMin] minutes of penalty a day. */
@Serializable
data class BailOutConfig(
    @SerialName("extra_lock_min") val extraLockMin: Int = 120,
    @SerialName("daily_cap_min") val dailyCapMin: Int = 360,
)

/** One app of the takeover's APPS button: exactly one of [role], [packageName] or [labelContains]. */
@Serializable
data class AppsButtonEntry(
    /** `dialer`, `adhan` or `clock`: resolved on the phone (default dialer, the adhan app setting, the clock app). */
    val role: String? = null,
    @SerialName("package") val packageName: String? = null,
    /** The first launcher app whose label contains this text (case-insensitive). */
    @SerialName("label_contains") val labelContains: String? = null,
)

/** Day boundary settings (design section 1, "Day"). */
@Serializable
data class DayConfig(
    /** After wind-down has started, this many minutes of screen off end the day (at the moment the screen went off). */
    @SerialName("idle_end_min") val idleEndMin: Int = 120,
    /** The day ends at this local time the morning after at the latest. */
    @SerialName("fallback_end") val fallbackEnd: String = "06:00",
    /** `HH:MM` times earlier than this belong to the night after the day (00:30 bedtime = after midnight). */
    @SerialName("night_edge") val nightEdge: String = "05:00",
)

/** Takeover timings the Android layer applies (full intensity length, Glyph lights). */
@Serializable
data class TakeoverConfig(
    @SerialName("glyph_off_after_s") val glyphOffAfterS: Int = 30,
    /** `full_then_subtle` is full for this many seconds, then subtle. */
    @SerialName("full_seconds") val fullSeconds: Int = 30,
)

/** Stage rules: promote after [promoteAfterClean] clean completions in a row; demote after [demoteAfterMisses] misses or bail-outs within the last [window] outcomes. */
@Serializable
data class StagesConfig(
    @SerialName("promote_after_clean") val promoteAfterClean: Int = 10,
    @SerialName("demote_after_misses") val demoteAfterMisses: Int = 3,
    val window: Int = 10,
)

/** One protocol: a part of the day with a trigger, steps, a lock, step-down versions and a stage. */
@Serializable
data class Protocol(
    val id: String,
    val kind: ProtocolKind,
    /** Shown big on the takeover; short and uppercase reads best. */
    val title: String,
    /** See [Trigger] for every form. */
    val trigger: String,
    val intensity: Intensity = Intensity.FULL_THEN_SUBTLE,
    val lock: Lock = Lock(),
    /** The full version. */
    val steps: List<Step>,
    /** Step-down versions ("I'M NOT OKAY"): 1st tap short, 2nd minimal, 3rd single (one step, marked "minimum"). */
    val short: List<Step>? = null,
    val minimal: List<Step>? = null,
    val single: List<Step>? = null,
    /** Per-weekday replacements ("mon".."sun"); a version missing here falls back to the protocol's own. */
    val weekdays: Map<String, StepVersions> = emptyMap(),
    /** Kind-specific numbers (see [ProtocolKind.params]). */
    val params: JsonObject = JsonObject(emptyMap()),
    /** False pins the protocol at stage 1 (never promoted). */
    val stages: Boolean = true,
) {
    /** The parsed [trigger], or null when it is not valid (the parser rejects such a file). */
    val parsedTrigger: Trigger? get() = (Trigger.parse(trigger) as? Parsed.Ok)?.value

    /**
     * The steps of [version] on [weekday] ("mon".."sun"): the weekday's own list when it has one, else the
     * protocol's. Null when that version does not exist.
     */
    fun stepsFor(version: Version, weekday: String?): List<Step>? {
        val override = weekday?.let { weekdays[it] }
        return when (version) {
            Version.FULL -> override?.steps ?: steps
            Version.SHORT -> override?.short ?: short
            Version.MINIMAL -> override?.minimal ?: minimal
            Version.SINGLE -> override?.single ?: single
        }
    }

    /** The next step-down version after [current] that exists on [weekday], or null at the bottom of the ladder. */
    fun nextVersion(current: Version, weekday: String?): Version? =
        Version.entries.filter { it.ordinal > current.ordinal }.firstOrNull { !stepsFor(it, weekday).isNullOrEmpty() }

    /** An integer kind param, or its default from [ProtocolKind.params] (0 when the kind has no such param). */
    fun intParam(name: String): Int {
        val raw = params[name] as? JsonPrimitive
        raw?.takeIf { !it.isString }?.intOrNull?.let { return it }
        return (kind.params[name] as? ParamSpec.IntRange)?.default ?: 0
    }

    /** A true/false kind param, or its default. */
    fun boolParam(name: String): Boolean {
        val raw = params[name] as? JsonPrimitive
        raw?.takeIf { !it.isString }?.booleanOrNull?.let { return it }
        return (kind.params[name] as? ParamSpec.Bool)?.default ?: false
    }

    /** A text-list kind param (empty when absent). */
    fun textListParam(name: String): List<String> =
        (params[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

    /** The raw value of a kind param, or null. */
    fun param(name: String): JsonElement? = params[name]
}

/** A weekday's replacement step lists. */
@Serializable
data class StepVersions(
    val steps: List<Step>? = null,
    val short: List<Step>? = null,
    val minimal: List<Step>? = null,
    val single: List<Step>? = null,
)

/** What a run locks while it is active. */
@Serializable
data class Lock(
    val type: LockType = LockType.NONE,
    /** The lock ends this many minutes after the run starts; null = until the steps are done. */
    val minutes: Int? = null,
    /** The `done` step stays locked for this many minutes after the run starts (stage 1). */
    @SerialName("done_after_min") val doneAfterMin: Int? = null,
)

/** One step of a protocol. */
@Serializable
data class Step(
    val text: String,
    val type: StepType = StepType.CHECK,
    /** check: locked for this many minutes; timer: lasts this many minutes (none = until the lock ends). */
    val min: Int? = null,
    /** text: how many lines to type (default 1); with [todos], up to this many (at least one). */
    val lines: Int? = null,
    /** text, sleep protocols: the lines become the next day's to-dos (home, the morning takeover). */
    val todos: Boolean = false,
    /** Left out on days with a MY TIMES class or commute (the morning walk: the commute counts as the walk). */
    @SerialName("skip_on_class_days") val skipOnClassDays: Boolean = false,
    /** The takeover offers the WHITE NOISE switch on this step (wind-down: lights low). */
    @SerialName("noise_toggle") val noiseToggle: Boolean = false,
    /** text: may be left empty (SKIP); the reset's "name it". */
    val optional: Boolean = false,
    /** Reset protocols, last step: finishing it re-enters the day (missed runs dropped, the next protocol starts). */
    val reenter: Boolean = false,
    /** Finishing this step switches the walking switch on (Jummah: leaving for uni). */
    @SerialName("walking_on") val walkingOn: Boolean = false,
    /** Jummah: the I'M AT UNI step. Steps before it are the leave-by takeover, steps after it the Jummah lock. */
    val arrival: Boolean = false,
)

/** Step types: what a step asks for and when it is finished. */
@Serializable
enum class StepType(val json: String) {
    /** Tap to confirm; locked for `min` minutes when given (stage 1). */
    @SerialName("check") CHECK("check"),

    /** Lasts `min` minutes, or until the lock ends when there is no `min`; ends by itself. */
    @SerialName("timer") TIMER("timer"),

    /** Type `lines` lines; saved into the run and the journal. */
    @SerialName("text") TEXT("text"),

    /** The final confirmation; locked until the lock's `done_after_min` (stage 1). */
    @SerialName("done") DONE("done"),
    ;

    companion object {
        fun fromJson(value: String): StepType? = entries.firstOrNull { it.json == value }
    }
}

/** Lock types (design section 1, "Lock"). */
@Serializable
enum class LockType(val json: String) {
    /** The takeover stays up (it comes back if left); only the whitelist is usable; the pool is blocked. */
    @SerialName("screen") SCREEN("screen"),

    /** Only the pool is blocked; the phone is otherwise normal. */
    @SerialName("pool") POOL("pool"),

    @SerialName("none") NONE("none"),
    ;

    companion object {
        fun fromJson(value: String): LockType? = entries.firstOrNull { it.json == value }
    }
}

/** How loud a takeover is. QUIET mode makes every takeover silent whatever its intensity. */
@Serializable
enum class Intensity(val json: String) {
    /** Dark screen, vibration, pixel eye, sound, Glyph lights (Glyph off after `glyph_off_after_s`). */
    @SerialName("full") FULL("full"),

    /** Quiet screen, no sound, no Glyph. */
    @SerialName("subtle") SUBTLE("subtle"),

    /** Full for the first `takeover.full_seconds`, then subtle. */
    @SerialName("full_then_subtle") FULL_THEN_SUBTLE("full_then_subtle"),
    ;

    companion object {
        fun fromJson(value: String): Intensity? = entries.firstOrNull { it.json == value }
    }
}

/** The versions of a protocol, in ladder order. [json] is the field that holds its steps. */
@Serializable
enum class Version(val json: String) {
    @SerialName("full") FULL("steps"),
    @SerialName("short") SHORT("short"),
    @SerialName("minimal") MINIMAL("minimal"),

    /** One step, shown marked "minimum". */
    @SerialName("single") SINGLE("single"),
    ;

    /** The name the journal uses: full, short, minimal or single. */
    val label: String get() = name.lowercase()
}

/** The five daily prayers, in order. */
@Serializable
enum class Prayer(val json: String) {
    @SerialName("fajr") FAJR("fajr"),
    @SerialName("dhuhr") DHUHR("dhuhr"),
    @SerialName("asr") ASR("asr"),
    @SerialName("maghrib") MAGHRIB("maghrib"),
    @SerialName("isha") ISHA("isha"),
    ;

    /** The prayer whose adhan ends this one's window (null for Isha: its window lasts until the day ends). */
    val next: Prayer? get() = entries.getOrNull(ordinal + 1)

    /** Uppercase name for user text. */
    val title: String get() = name

    companion object {
        fun fromJson(value: String): Prayer? = entries.firstOrNull { it.json == value }
    }
}

/**
 * Protocol kinds: behaviour lives in code, numbers in `params`. [ladder] lists the step-down versions the kind may
 * define (empty = no ladder: "I'M NOT OKAY" does what the kind says, e.g. a prayer shortens its lock).
 */
@Serializable
enum class ProtocolKind(val json: String, val ladder: Set<Version>, val params: Map<String, ParamSpec>) {
    @SerialName("sleep") SLEEP(
        "sleep", ALL_LADDER,
        mapOf(
            "shift_min" to ParamSpec.IntRange(0, 120, 20),
            "hold_after_missed" to ParamSpec.IntRange(0, 7, 2),
            "late_min" to ParamSpec.IntRange(0, 240, 30),
            "night_lock_grace_min" to ParamSpec.IntRange(10, 600, 120),
            "white_noise" to ParamSpec.Bool(false),
        ),
    ),
    @SerialName("morning") MORNING(
        "morning", ALL_LADDER,
        mapOf(
            "ring_max_min" to ParamSpec.IntRange(1, 60, 10),
            "snooze_min" to ParamSpec.IntRange(1, 60, 5),
            "max_rings" to ParamSpec.IntRange(1, 10, 3),
        ),
    ),
    @SerialName("prayer") PRAYER(
        "prayer", emptySet(),
        mapOf(
            "heads_up_min" to ParamSpec.IntRange(0, 60, 10),
            "window_warn_min" to ParamSpec.IntRange(0, 120, 15),
        ),
    ),
    @SerialName("jummah") JUMMAH(
        "jummah", emptySet(),
        mapOf(
            "leave_buffer_min" to ParamSpec.IntRange(0, 120, 15),
            "arrive_before_min" to ParamSpec.IntRange(0, 120, 15),
            "arrive_repeat_min" to ParamSpec.IntRange(1, 60, 10),
            "card_before_end_min" to ParamSpec.IntRange(0, 60, 5),
            "lock_before_min" to ParamSpec.IntRange(0, 60, 5),
            "missed_after_min" to ParamSpec.IntRange(0, 240, 50),
            "prep_todos" to ParamSpec.TextList,
        ),
    ),
    @SerialName("study") STUDY(
        "study", ALL_LADDER,
        mapOf(
            "work_min" to ParamSpec.IntRange(1, 240, 50),
            "break_min" to ParamSpec.IntRange(0, 60, 10),
            "short_work_min" to ParamSpec.IntRange(1, 240, 25),
            "short_break_min" to ParamSpec.IntRange(0, 60, 5),
        ),
    ),
    @SerialName("meal") MEAL(
        "meal", ALL_LADDER,
        mapOf(
            "walk_min" to ParamSpec.IntRange(0, 120, 10),
            "dishes" to ParamSpec.TextMap,
            "fallback" to ParamSpec.TextList,
        ),
    ),
    @SerialName("gym") GYM(
        "gym", ALL_LADDER,
        mapOf("rest_s" to ParamSpec.IntRange(10, 600, 90), "exercises" to ParamSpec.TextList),
    ),
    @SerialName("walk") WALK("walk", emptySet(), mapOf("still_end_min" to ParamSpec.IntRange(1, 120, 10))),
    /** Spec: "short = 3 minutes, whatever the ramp says. There is no minimal version below that." */
    @SerialName("attention") ATTENTION(
        "attention", setOf(Version.SHORT),
        mapOf(
            "start_min" to ParamSpec.IntRange(1, 30, 3),
            "max_min" to ParamSpec.IntRange(1, 60, 10),
            "ramp_every_days" to ParamSpec.IntRange(1, 30, 2),
            "short_min" to ParamSpec.IntRange(1, 30, 3),
        ),
    ),
    @SerialName("friends") FRIENDS("friends", emptySet(), mapOf("cap_min" to ParamSpec.IntRange(10, 720, 240))),
    @SerialName("call") CALL(
        "call", setOf(Version.SHORT),
        mapOf(
            "min_call_min" to ParamSpec.IntRange(1, 60, 3),
            "return_after_min" to ParamSpec.IntRange(1, 240, 30),
            "wrap_up_min" to ParamSpec.IntRange(1, 120, 20),
        ),
    ),
    @SerialName("promise") PROMISE(
        "promise", emptySet(),
        mapOf("delays" to ParamSpec.IntList(1, 240), "snooze_min_min" to ParamSpec.IntRange(1, 60, 2)),
    ),
    @SerialName("reset") RESET("reset", ALL_LADDER, mapOf("next_within_min" to ParamSpec.IntRange(0, 240, 60))),
    ;

    companion object {
        fun fromJson(value: String): ProtocolKind? = entries.firstOrNull { it.json == value }
    }
}

private val ALL_LADDER = setOf(Version.SHORT, Version.MINIMAL, Version.SINGLE)

/** The shape and range of one kind param, for validation and defaults. */
sealed interface ParamSpec {
    /** A whole number from [min] to [max]. */
    data class IntRange(val min: Int, val max: Int, val default: Int) : ParamSpec

    data class Bool(val default: Boolean) : ParamSpec

    /** A list of texts. */
    data object TextList : ParamSpec

    /** An object of label -> text. */
    data object TextMap : ParamSpec

    /** A list of whole numbers from [min] to [max]. */
    data class IntList(val min: Int, val max: Int) : ParamSpec
}
