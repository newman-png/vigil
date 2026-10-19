@file:UseSerializers(InstantIso::class)

package dev.vigil.core.run

import dev.vigil.core.common.InstantIso
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.StagesConfig
import dev.vigil.core.protocol.Version
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/** Where a run is in its life. Finished runs leave the state (their story is in the journal). */
@Serializable
enum class RunPhase {
    /** Waiting for the screen: another run owns it. Its timers have not started. */
    @SerialName("queued") QUEUED,

    /** Running: steps progress, its lock (if any) holds until [Run.lockEndsAt]. */
    @SerialName("active") ACTIVE,

    /** Prayer during a class: a silent card only; the takeover starts when the class ends. */
    @SerialName("card") CARD,

    /** The lock is over but the run is not finished (prayer: CONFIRM on home). No screen, no pool block. */
    @SerialName("open") OPEN,

    /** Prayer: the window is about to close unconfirmed, so the takeover came back (until confirmed or closed). */
    @SerialName("returned") RETURNED,

    /** Stage 3: one notification only, no lock. A prayer still needs confirming within its window. */
    @SerialName("reminder") REMINDER,

    /**
     * A prayer took the screen from this run: its timers and lock are frozen since [Run.pausedAt], and it goes on where
     * it was once the screen is free again.
     */
    @SerialName("paused") PAUSED,

    /**
     * The sleep protocol's night lock: a subtle screen lock and the pool blocked, from GOING TO SLEEP (or the idle or
     * fallback day end after wind-down) until the wake alarm is answered, [Run.lockEndsAt] at the latest. No steps, and
     * it counts for no day.
     */
    @SerialName("night") NIGHT,

    /** A 10-minute promise counting down to [PromiseInfo.dueAt]: nothing shows and nothing is locked yet. */
    @SerialName("waiting") WAITING,
}

/** Where a Jummah run is (design M4); its times are in [JummahInfo]. */
@Serializable
enum class JummahPart {
    /** The leave-by takeover: shoes, bag, leave, walking on (a screen lock until the arrival time at the latest). */
    @SerialName("leave") LEAVE,

    /** On the way: no screen; I'M AT UNI is asked at the arrival time and every few minutes after, until the lock. */
    @SerialName("travel") TRAVEL,

    /** The Jummah lock: phone silent (Do Not Disturb), pool blocked, a subtle silent screen; DONE after the prayer. */
    @SerialName("lock") LOCK,

    /** The lock is over unconfirmed (or was left): CONFIRM until the missed time. */
    @SerialName("confirm") CONFIRM,
}

/** A Jummah run's day: its times (from MY TIMES and the protocol's params) and what has happened. */
@Serializable
data class JummahInfo(
    /** MY TIMES jummah_time on this Friday. */
    @SerialName("jummah_at") val jummahAt: Instant,
    /** The leave-by takeover: first Friday class (or the lock) - commute_min - leave_buffer_min. */
    @SerialName("leave_at") val leaveAt: Instant,
    /** I'M AT UNI is asked from here: first Friday class (or the lock) - arrive_before_min. */
    @SerialName("arrive_at") val arriveAt: Instant,
    /** The silent class-end card; null without a Friday class ending before Jummah. */
    @SerialName("card_at") val cardAt: Instant? = null,
    /** The Jummah lock starts (jummah_time - lock_before_min). */
    @SerialName("lock_at") val lockAt: Instant,
    /** Nothing confirmed by then (jummah_time + missed_after_min): missed, Dhuhr instead, the reset offered. */
    @SerialName("missed_at") val missedAt: Instant,
    val part: JummahPart = JummahPart.LEAVE,
    /** I'M AT UNI was confirmed. */
    val arrived: Boolean = false,
    /** The last I'M AT UNI ask (the next one comes arrive_repeat_min later). */
    @SerialName("asked_at") val askedAt: Instant? = null,
    @SerialName("card_sent") val cardSent: Boolean = false,
    /** VIGIL turned Do Not Disturb on for this lock: it goes off again whatever ends the lock. */
    val dnd: Boolean = false,
    /** TEST: the one part this preview shows (leave, travel or lock); it ends with that part. Null for a real run. */
    val only: JummahPart? = null,
)

/** A 10-minute promise (design M5): exactly the user's line and its timer. */
@Serializable
data class PromiseInfo(
    /** The line, shown exactly on the takeover. */
    val text: String,
    /** The delay picked (5, 10, 15 or 30); a snooze is half of it. */
    val minutes: Int,
    /** It comes due then: a full takeover, then the pool lock until DONE. */
    @SerialName("due_at") val dueAt: Instant,
    /** The one snooze was used. */
    val snoozed: Boolean = false,
)

/** One logged gym set: [reps] of [exercise] with [weight] kg (0 = body weight). */
@Serializable
data class GymSet(
    val exercise: String,
    val reps: Int,
    val weight: Double = 0.0,
    val at: Instant? = null,
)

/** A gym run's workout: the sets logged in it and the rest timer after the last one. */
@Serializable
data class GymInfo(
    val sets: List<GymSet> = emptyList(),
    /** The rest after the last set ends then (the app counts it down and sounds its end). */
    @SerialName("rest_until") val restUntil: Instant? = null,
)

/** The MY TIMES slot a `slot:` trigger started a run for. */
@Serializable
data class SlotInfo(
    /** class, meal, study, gym, call, commute or jummah. */
    val list: String,
    val label: String,
    val start: Instant,
    val end: Instant? = null,
    val minutes: Int? = null,
)

/** One live execution of a protocol. */
@Serializable
data class Run(
    /** "run-<n>", unique for the install. */
    val id: String,
    val protocol: String,
    val kind: ProtocolKind,
    /** The stage it runs at (fixed when it fires): 1 forced, 2 guided, 3 reminder. */
    val stage: Int,
    val phase: RunPhase,
    val firedAt: Instant,
    /** What started it: adhan, time, slot:<type>, manual, test, wake_alarm, after:<id>, misses:<n>, toggle:<name>. */
    val reason: String,
    /** The day key's weekday ("mon".."sun"): picks weekday step lists. */
    val weekday: String,
    /** The lock this run applies (none at stage 3). */
    val lock: LockType = LockType.NONE,
    val version: Version = Version.FULL,
    /** The planned moment for time and slot triggers. */
    val planned: Instant? = null,
    val slot: SlotInfo? = null,
    /** A preview from the TEST screen: logged, but no stages, no penalties, no memory. */
    val test: Boolean = false,
    /** When it first became active (null while queued or a card). */
    val startedAt: Instant? = null,
    /** When its current lock started (the run's start, or the end of the class for a prayer card). */
    val lockStartedAt: Instant? = null,
    /** When the timed lock ends; extended by calls. Null = no timed lock (until the steps are done). */
    val lockEndsAt: Instant? = null,
    /** Before this the `done` step cannot be tapped (stage 1); extended by calls. */
    val doneAfterAt: Instant? = null,
    /** The current step (index into the version's steps). */
    val step: Int = 0,
    val stepStartedAt: Instant? = null,
    /** What was typed into text steps, by step index. */
    val texts: Map<String, String> = emptyMap(),
    /** The done step was tapped (prayer: confirmed). */
    val confirmed: Boolean = false,
    /** Shown silently whatever QUIET says (a prayer during a class). */
    val silent: Boolean = false,
    val prayer: Prayer? = null,
    /** The expected next adhan (prayer window end); null for Isha (until the day ends). */
    val windowEndsAt: Instant? = null,
    /** True when [windowEndsAt] is an estimate from yesterday's adhan (false = a safety limit only, no warning). */
    val windowEstimated: Boolean = false,
    /** Prayer card: when the class (block) ends. */
    val classEndsAt: Instant? = null,
    /** The window warning has happened. */
    val warned: Boolean = false,
    /** "I'M NOT OKAY" shortened a prayer lock to its minimum. */
    val shortened: Boolean = false,
    /** "I'M NOT OKAY" moved to a shorter version at least once. */
    val steppedDown: Boolean = false,
    /** The takeover had to come back (window warning). */
    val returned: Boolean = false,
    /** A prayer lock was left after its minimum. */
    val released: Boolean = false,
    /** A bail-out was already counted for this run. */
    val bailed: Boolean = false,
    /** `event:after:` triggers already fired for this run. */
    val afterFired: Boolean = false,
    /** Paused for a prayer since then (see [RunPhase.PAUSED]). */
    val pausedAt: Instant? = null,
    /** It fired on a day with a MY TIMES class or commute: steps marked `skip_on_class_days` are left out. */
    val classDay: Boolean = false,
    /**
     * Its full takeover is up without a screen lock (the I'M AT UNI ask, a promise that came due): it owns the screen
     * until answered, yields to any screen lock and is never brought back once left.
     */
    val asking: Boolean = false,
    /** A Jummah run: its times and part. */
    val jummah: JummahInfo? = null,
    /** A 10-minute promise: its line and timer. */
    val promise: PromiseInfo? = null,
    /**
     * The run's length in minutes, fixed when it fired: a study block (its rounds fill it; a step-down keeps only what is
     * left), the attention reset's ramp minutes (its timer without `min` lasts them), a gym session's planned workout.
     */
    @SerialName("length_min") val lengthMin: Int? = null,
    /** What it is for: the MY TIMES row's label (what to study, the meal) or what was typed for a manual start. */
    val label: String? = null,
    /** A gym run: the sets logged and the rest timer. */
    val gym: GymInfo? = null,
) {
    /** The single-step version, shown marked "minimum". */
    val isMinimum: Boolean get() = version == Version.SINGLE

    /** A prayer, or the Jummah lock: it goes before other protocols for the screen, and no other protocol pauses it. */
    val prayerLike: Boolean get() = prayer != null || jummah?.part == JummahPart.LOCK

    /** A completion without step-down, NOT OKAY, release, bail-out or a returning takeover. */
    val clean: Boolean get() = version == Version.FULL && !steppedDown && !shortened && !returned && !released && !bailed
}

/** How a run ended, for the stage rules. */
enum class RunOutcome(val json: String) {
    CLEAN("clean"),

    /** Completed, but not clean (stepped down, shortened, released, the takeover came back). */
    DONE("done"),
    MISSED("missed"),
    BAILED("bailed"),
    ;

    val failure: Boolean get() = this == MISSED || this == BAILED
}

/** A protocol's stage and its recent outcomes. */
@Serializable
data class StageMemory(
    val stage: Int = 1,
    /** Clean completions in a row since the last stage change. */
    @SerialName("clean_streak") val cleanStreak: Int = 0,
    /** The last outcomes ([RunOutcome.json]), at most `stages.window`, since the last stage change. */
    val recent: List<String> = emptyList(),
)

/**
 * Stage rules (design section 1): promote after `promote_after_clean` clean completions in a row; demote one stage
 * after `demote_after_misses` misses or bail-outs within the last `window` outcomes. A stage change starts the count
 * again. A protocol with `stages: false` stays at stage 1.
 */
object Stages {
    const val MAX_STAGE = 3

    /** Records [outcome]; returns the new memory and +1 / -1 when the stage changed (else 0). */
    fun record(memory: StageMemory, outcome: RunOutcome, cfg: StagesConfig, pinned: Boolean): Pair<StageMemory, Int> {
        if (pinned) return StageMemory(stage = 1) to 0
        val streak = if (outcome == RunOutcome.CLEAN) memory.cleanStreak + 1 else 0
        val recent = (memory.recent + outcome.json).takeLast(cfg.window)
        val failures = recent.count { it == RunOutcome.MISSED.json || it == RunOutcome.BAILED.json }
        return when {
            memory.stage < MAX_STAGE && streak >= cfg.promoteAfterClean -> StageMemory(memory.stage + 1) to +1
            memory.stage > 1 && failures >= cfg.demoteAfterMisses -> StageMemory(memory.stage - 1) to -1
            else -> StageMemory(memory.stage, streak, recent) to 0
        }
    }
}
