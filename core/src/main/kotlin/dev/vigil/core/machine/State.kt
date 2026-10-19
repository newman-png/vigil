@file:UseSerializers(InstantIso::class)

package dev.vigil.core.machine

import dev.vigil.core.common.InstantIso
import dev.vigil.core.pool.PoolState
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.run.GymSet
import dev.vigil.core.run.Run
import dev.vigil.core.run.StageMemory
import dev.vigil.core.times.MyTimesStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate

/** The current day and what happened in it (reset when the day ends). */
@Serializable
data class DayState(
    /** The day key, yyyy-MM-dd. */
    val key: String,
    @SerialName("started_at") val startedAt: Instant,
    /** How the previous day ended (sleep_tap, idle, wake_alarm, fallback), or first_start. */
    @SerialName("start_reason") val startReason: String,
    /** Planned starts that came due, by [dev.vigil.core.timeline.PlannedStart.key]: "fired" or "skipped:<reason>". */
    val handled: Map<String, String> = emptyMap(),
    /** When each protocol's run first started today (for `start:` anchors and wind-down). */
    val started: Map<String, Instant> = emptyMap(),
    /** When each protocol's run last completed today (for `done:` anchors). */
    val completed: Map<String, Instant> = emptyMap(),
    /** The adhans that came today, by prayer. */
    val adhans: Map<Prayer, Instant> = emptyMap(),
    /** Prayers confirmed today. */
    val confirmed: List<Prayer> = emptyList(),
    /** Prayers missed today. */
    val missed: List<Prayer> = emptyList(),
    /** When the wake alarm was dismissed this day (the `woke` time base, minutes late in the journal). */
    val woke: Instant? = null,
    /** Tonight's wind-down was left early (LEAVE): the night counts as missed for sleep shifting. */
    @SerialName("sleep_bailed") val sleepBailed: Boolean = false,
    /** Today's Jummah: [JUMMAH_CONFIRMED] or [JUMMAH_MISSED] (a missed one lets Dhuhr run); null before either. */
    val jummah: String? = null,
    /** Attention resets triggered and waiting to start (offset, a class or another protocol on the screen). */
    val deferred: List<Deferred> = emptyList(),
    /** When today's first call card appeared: a call that starts after it gets "time to wrap up". */
    @SerialName("call_card_at") val callCardAt: Instant? = null,
    /** The wrap-up minutes of today's latest call card. */
    @SerialName("call_wrap_min") val callWrapMin: Int? = null,
    /** How many of today's timed nudges came due (shown or skipped); the next one is this index. */
    @SerialName("nudges_due") val nudgesDue: Int = 0,
    /** The movement nudge counts stillness from here at the earliest (the last nudge, shown or skipped). */
    @SerialName("move_from") val moveFrom: Instant? = null,
    /** When the last nudge was shown today: the next timed one waits `min_gap_min` after it. */
    @SerialName("nudged_at") val nudgedAt: Instant? = null,
) {
    val date: LocalDate get() = LocalDate.parse(key)

    companion object {
        const val JUMMAH_CONFIRMED = "confirmed"
        const val JUMMAH_MISSED = "missed"
    }
}

/**
 * A protocol offered instead of forced (design M5: the reset after missed or bailed protocols in a row, or a missed
 * Jummah): a full-intensity card with START and NOT NOW, until answered or the day ends.
 */
@Serializable
data class Offer(
    val protocol: String,
    val at: Instant,
    /** Why: misses:<n> or jummah_missed. */
    val reason: String,
)

/**
 * A protocol triggered but not started yet (the attention reset): it may start from [due], once no class is in progress
 * and nothing else holds the screen, until [due] + [Machine.DEFER_LIMIT]; then it is skipped.
 */
@Serializable
data class Deferred(
    val protocol: String,
    /** What triggered it (after:prayer-dhuhr ...), kept for its run. */
    val reason: String,
    /** When it was triggered. */
    val at: Instant,
    val due: Instant,
)

/** The attention reset's ramp (design M6): the days it was completed in full, counted once per day. */
@Serializable
data class AttentionMemory(
    val days: Int = 0,
    /** The day key of the last day that counted. */
    @SerialName("last_day") val lastDay: String? = null,
)

/** What the gym remembers per exercise name: the last set (the pre-fill), the best set, and its own rest. */
@Serializable
data class GymMemory(
    val last: Map<String, GymSet> = emptyMap(),
    val best: Map<String, GymSet> = emptyMap(),
    /** Rest seconds set for an exercise (else the gym protocol's `rest_s`). */
    @SerialName("rest_s") val restS: Map<String, Int> = emptyMap(),
)

/** What the prayer rules remember across days. */
@Serializable
data class PrayerMemory(
    /** The most recent adhan per prayer: estimates (heads-up, windows) add 24 h to it. */
    @SerialName("last_adhan") val lastAdhan: Map<Prayer, Instant> = emptyMap(),
    /** The expected adhan each heads-up was sent for, so it is sent once. */
    @SerialName("heads_up_for") val headsUpFor: Map<Prayer, Instant> = emptyMap(),
)

/** Sleep shifting (design M2): tonight's target bedtime and the missed nights in a row. */
@Serializable
data class SleepState(
    /** Tonight's target bedtime `HH:MM`; null before the first day start with a sleep protocol (= bedtime_start). */
    val target: String? = null,
    /** Missed nights in a row; a good night resets it. At `hold_after_missed` the target holds for a night. */
    @SerialName("missed_in_a_row") val missedInARow: Int = 0,
)

/** VIGIL's own wake alarm (design M3): the one ringing now (or waiting to ring again) and the last one dealt with. */
@Serializable
data class WakeAlarmState(
    /** The day key whose alarm is ringing or waiting to ring again; null when none is. */
    @SerialName("ringing_for") val ringingFor: String? = null,
    /** When that alarm was due (MY TIMES wake): minutes late are counted from it. */
    val scheduled: Instant? = null,
    /** Ringing since then; null while it waits to ring again ([nextRingAt]). */
    @SerialName("ringing_since") val ringingSince: Instant? = null,
    /** How many times it has rung. */
    val rings: Int = 0,
    /** Unanswered: it rings again then. */
    @SerialName("next_ring_at") val nextRingAt: Instant? = null,
    /** The day key of the last alarm that was dismissed, given up or found too late (it does not ring again). */
    @SerialName("handled_for") val handledFor: String? = null,
    /** The alarm of this day key was never answered: the morning starts at the next unlock. */
    @SerialName("morning_at_unlock") val morningAtUnlock: String? = null,
)

/** One to-do, typed in last night's wind-down. */
@Serializable
data class Todo(val text: String, val done: Boolean = false)

/** The home switches. */
@Serializable
data class Toggles(
    val quiet: Boolean = false,
    val walking: Boolean = false,
    val friends: Boolean = false,
    @SerialName("walking_since") val walkingSince: Instant? = null,
    @SerialName("friends_since") val friendsSince: Instant? = null,
    /**
     * Without a step counter the walk ends then: an automatic commute `commute_grace_min` after the commute's end (the
     * row's end, else its start + its minutes or `commute_min`), any other walk `manual_max_min` after it began. Null
     * while walking is off.
     */
    @SerialName("walking_until") val walkingUntil: Instant? = null,
)

/** The step counter as the app reports it ([Input.Steps]): walks end, and the movement nudge comes, when steps stop. */
@Serializable
data class StepState(
    /** The phone can count steps (a step counter and the permission); false until the app says so. */
    val available: Boolean = false,
    /** The counter's last total (steps since the phone started). */
    val total: Long? = null,
    /** When the total last grew (or the counter was first or newly read: no stillness is assumed before that). */
    @SerialName("last_step_at") val lastStepAt: Instant? = null,
)

/** What VIGIL is playing ([Effect.Audio]): one sound at a time. */
@Serializable
data class AudioState(
    /** `white_noise`, `metronome` or `file:<id>`. */
    val sound: String,
    val since: Instant,
    /** The metronome's beats per minute. */
    val bpm: Int? = null,
    /** A file plays again from the start when it ends. */
    val loop: Boolean = false,
    /** A file's name as the user sees it (for the journal). */
    val name: String? = null,
)

/**
 * Everything the v2 engine remembers (design 2.3), persisted by the app as one JSON object ([encode] / [decode]).
 * Only [Machine] changes it.
 */
@Serializable
data class MachineState(
    val version: Int = FORMAT_VERSION,
    val day: DayState? = null,
    val times: MyTimesStore = MyTimesStore(),
    /** Open runs: active, queued, cards, open prayers and reminders. */
    val runs: List<Run> = emptyList(),
    val pool: PoolState = PoolState(),
    val toggles: Toggles = Toggles(),
    /** Stage memory per protocol id. */
    val stages: Map<String, StageMemory> = emptyMap(),
    val prayers: PrayerMemory = PrayerMemory(),
    /** When each protocol last fired (any day). */
    @SerialName("last_fired") val lastFired: Map<String, Instant> = emptyMap(),
    /** Missed or bailed outcomes in a row (any protocol); a completion resets it. */
    @SerialName("misses_in_a_row") val missesInARow: Int = 0,
    /** A call has been connected since then: protocol timers and locks are paused. */
    @SerialName("call_since") val callSince: Instant? = null,
    /** Kill switch: VIGIL is suspended until then (no takeovers, pool open). */
    @SerialName("suspended_until") val suspendedUntil: Instant? = null,
    @SerialName("screen_on") val screenOn: Boolean = true,
    @SerialName("screen_unlocked") val screenUnlocked: Boolean = true,
    /**
     * When the phone was last put down (the screen went off); kept through wakes that show only the lock screen, null
     * once it is unlocked. The idle day end counts from it while the screen is off.
     */
    @SerialName("screen_off_since") val screenOffSince: Instant? = null,
    /** The app in front, as last reported. */
    val foreground: String? = null,
    @SerialName("foreground_is_pool") val foregroundIsPool: Boolean = false,
    /** The number of the next run id. */
    @SerialName("next_run") val nextRun: Long = 1,
    val sleep: SleepState = SleepState(),
    @SerialName("wake_alarm") val wakeAlarm: WakeAlarmState = WakeAlarmState(),
    /** To-dos by day key: written in a wind-down for the next day, gone when that day ends. */
    val todos: Map<String, List<Todo>> = emptyMap(),
    /** The protocol offered now (the reset), or null. */
    val offer: Offer? = null,
    val attention: AttentionMemory = AttentionMemory(),
    val gym: GymMemory = GymMemory(),
    val steps: StepState = StepState(),
    /** VIGIL's sound playing now (white noise, the metronome, a file), or null. */
    val audio: AudioState? = null,
    /** "Time to wrap up" is due then for the call in progress (once per call); null when none is. */
    @SerialName("call_wrap_at") val callWrapAt: Instant? = null,
    /** The call in progress already had its "time to wrap up". */
    @SerialName("call_wrapped") val callWrapped: Boolean = false,
    /** The phone has been unlocked (in use) since then; null while it is locked or off. */
    @SerialName("unlocked_since") val unlockedSince: Instant? = null,
) {
    /**
     * The state as JSON. Nulls are written out: a field whose default is not null (a pool session's `inSince`) would
     * otherwise come back as its default instead of null.
     */
    fun encode(): String = WRITER.encodeToString(serializer(), this)

    companion object {
        const val FORMAT_VERSION = 1

        internal val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            coerceInputValues = true
        }

        private val WRITER = Json(JSON) { explicitNulls = true }

        /**
         * Reads saved state; missing or unreadable text gives a fresh state (the first step starts a day). Readable
         * JSON with a part this version cannot read (a run of a kind that no longer exists, a value from a newer app)
         * keeps everything else: the unreadable fields fall back to their defaults and unreadable list items (runs)
         * are left out, so stages, penalties and cooldowns are never lost to one bad value.
         */
        fun decode(text: String?): MachineState = read(text).let { state ->
            // Saved before nulls were written out, a session out of the pool lost its null `inSince` and came back as
            // in the pool since it started (counted twice). Out of the pool is the only state with a `leftAt`.
            val session = state.pool.session?.takeIf { it.inSince != null && it.leftAt != null } ?: return@let state
            state.copy(pool = state.pool.copy(session = session.copy(inSince = null)))
        }

        private fun read(text: String?): MachineState {
            if (text == null) return MachineState()
            runCatching { return JSON.decodeFromString(serializer(), text) }
            val root = runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return MachineState()
            return runCatching { JSON.decodeFromJsonElement(serializer(), salvage(root)) }.getOrDefault(MachineState())
        }

        /**
         * [root] without what stops it from decoding. Every top-level field has a default, so each is tried on its own:
         * a list keeps its readable items; an object keeps its readable fields (each on its own), or loses the one
         * field without which it reads; anything else unreadable is left out.
         */
        private fun salvage(root: JsonObject): JsonObject {
            fun fits(key: String, value: JsonElement) =
                runCatching { JSON.decodeFromJsonElement(serializer(), JsonObject(mapOf(key to value))) }.isSuccess
            val kept = LinkedHashMap<String, JsonElement>()
            for ((key, value) in root) {
                if (fits(key, value)) {
                    kept[key] = value
                    continue
                }
                val repaired = when (value) {
                    is JsonArray -> JsonArray(value.filter { fits(key, JsonArray(listOf(it))) })
                    is JsonObject -> {
                        val alone = JsonObject(value.filter { (sub, v) -> fits(key, JsonObject(mapOf(sub to v))) })
                        val oneOut = value.keys.firstOrNull { sub -> fits(key, JsonObject(value - sub)) }?.let { JsonObject(value - it) }
                        listOfNotNull(alone, oneOut).firstOrNull { fits(key, it) }
                    }
                    else -> null
                }
                if (repaired != null && fits(key, repaired)) kept[key] = repaired
            }
            return JsonObject(kept)
        }
    }
}
