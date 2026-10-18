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
}

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
) {
    /** The single-step version, shown marked "minimum". */
    val isMinimum: Boolean get() = version == Version.SINGLE

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
