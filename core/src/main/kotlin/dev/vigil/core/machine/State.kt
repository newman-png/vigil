@file:UseSerializers(InstantIso::class)

package dev.vigil.core.machine

import dev.vigil.core.common.InstantIso
import dev.vigil.core.pool.PoolState
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.run.Run
import dev.vigil.core.run.StageMemory
import dev.vigil.core.times.MyTimesStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
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
) {
    val date: LocalDate get() = LocalDate.parse(key)
}

/** What the prayer rules remember across days. */
@Serializable
data class PrayerMemory(
    /** The most recent adhan per prayer: estimates (heads-up, windows) add 24 h to it. */
    @SerialName("last_adhan") val lastAdhan: Map<Prayer, Instant> = emptyMap(),
    /** The expected adhan each heads-up was sent for, so it is sent once. */
    @SerialName("heads_up_for") val headsUpFor: Map<Prayer, Instant> = emptyMap(),
)

/** The home switches. */
@Serializable
data class Toggles(
    val quiet: Boolean = false,
    val walking: Boolean = false,
    val friends: Boolean = false,
    @SerialName("walking_since") val walkingSince: Instant? = null,
    @SerialName("friends_since") val friendsSince: Instant? = null,
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
    /** When the screen went off (while it is off). */
    @SerialName("screen_off_since") val screenOffSince: Instant? = null,
    /** The app in front, as last reported. */
    val foreground: String? = null,
    @SerialName("foreground_is_pool") val foregroundIsPool: Boolean = false,
    /** The number of the next run id. */
    @SerialName("next_run") val nextRun: Long = 1,
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val FORMAT_VERSION = 1

        internal val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            coerceInputValues = true
        }

        /** Reads saved state; missing or damaged text gives a fresh state (the first step starts a day). */
        fun decode(text: String?): MachineState =
            text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: MachineState()
    }
}
