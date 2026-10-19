package dev.vigil.takeover

import dev.vigil.core.journal.Entry
import dev.vigil.core.journal.EntryType
import dev.vigil.core.machine.StepView
import dev.vigil.core.protocol.BailOutConfig
import dev.vigil.core.protocol.Intensity
import dev.vigil.core.protocol.Protocol
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.StepType
import dev.vigil.core.protocol.Version
import dev.vigil.core.run.JummahPart
import dev.vigil.core.run.Run
import dev.vigil.core.run.RunPhase
import java.time.Duration
import java.time.Instant
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// What the takeover shows about a run, read from the run and its protocol. Nothing is decided here: these lines
// only describe what the engine does, and the engine checks every tap again (it ignores what it does not allow).

/** The intro, the pips and the sound of a protocol's intensity: 3 full, 2 full then subtle, 1 subtle. */
internal fun tierFor(intensity: Intensity): Int = when (intensity) {
    Intensity.FULL -> 3
    Intensity.FULL_THEN_SUBTLE -> 2
    Intensity.SUBTLE -> 1
}

/**
 * One takeover: a prayer whose takeover came back (window warning) is a new takeover of the same run; so is each part
 * of Jummah (leave-by, I'M AT UNI, the lock) and a promise coming due again after its snooze.
 */
internal fun takeoverKey(run: Run): String {
    val jummah = run.jummah
    return when {
        run.phase == RunPhase.RETURNED -> "${run.id}:returned"
        jummah != null -> "${run.id}:${jummah.part.name.lowercase()}"
        run.promise?.snoozed == true -> "${run.id}:snoozed"
        else -> run.id
    }
}

/**
 * True when "I'M NOT OKAY" changes something for [run] now, as the engine's rule says: a prayer's lock shortens to
 * its minimum (once); a protocol with a ladder moves to its next shorter version while there is one.
 */
internal fun canStepDown(run: Run, protocol: Protocol?): Boolean {
    val p = protocol ?: return false
    if (run.phase != RunPhase.ACTIVE) return false
    if (p.kind == ProtocolKind.PRAYER) return !run.shortened && p.lock.doneAfterMin != null
    if (p.kind.ladder.isEmpty()) return false
    return p.nextVersion(run.version, run.weekday) != null
}

/** Which version runs now, when it is not the full one: under the step for as long as it lasts. */
internal fun versionLine(run: Run, protocol: Protocol?): String? = when {
    run.shortened -> protocol?.lock?.doneAfterMin?.let { "LOCK SHORTENED TO $it MIN" } ?: "LOCK SHORTENED"
    run.version == Version.SINGLE -> "MINIMUM"
    run.version == Version.MINIMAL -> "MINIMAL VERSION"
    run.version == Version.SHORT -> "SHORT VERSION"
    else -> null
}

/** The note right after "I'M NOT OKAY" took effect (the version line under the step says what runs now). */
internal const val STEP_DOWN_NOTE = "OKAY. THIS COSTS NOTHING."

/**
 * True for runs whose "I'M NOT OKAY" says so when nothing changes (Jummah and the promise have no shorter version; the
 * attention reset none below its 3 minutes), so it still shows.
 */
internal fun saysNoStepDown(run: Run): Boolean = run.jummah != null || run.promise != null || run.kind == ProtocolKind.ATTENTION

/** What "I'M NOT OKAY" says where there is no shorter version. */
internal const val NO_STEP_DOWN_NOTE = "THERE IS NO SHORTER VERSION OF THIS ONE. IT STAYS AS IT IS."

/** Minutes of pool lock a bail-out adds now, the daily cap counted (the engine's penalty rule). */
internal fun bailOutCost(cfg: BailOutConfig, penaltyToday: Int): Int =
    min(cfg.extraLockMin, max(0, cfg.dailyCapMin - penaltyToday))

/**
 * True when leaving [run] now costs nothing: a test, or a prayer past its minimum (or whose takeover came back),
 * which the engine releases without a penalty.
 */
internal fun leavesFree(run: Run, protocol: Protocol?, now: Instant): Boolean {
    if (run.test) return true
    if (run.prayer == null) return false
    if (run.phase == RunPhase.RETURNED) return true
    val minimumEnd = run.doneAfterAt ?: run.lockStartedAt?.plus(Duration.ofMinutes((protocol?.lock?.doneAfterMin ?: 0).toLong()))
    return minimumEnd != null && !now.isBefore(minimumEnd)
}

/** The honest line shown while LEAVE is held: what leaving costs. */
internal fun leaveLine(run: Run, protocol: Protocol?, cfg: BailOutConfig, penaltyToday: Int, now: Instant): String {
    if (leavesFree(run, protocol, now)) return if (run.test) "TEST RUN: LEAVING COSTS NOTHING" else "LEAVING NOW COSTS NOTHING"
    val cost = bailOutCost(cfg, penaltyToday)
    return if (cost > 0) "LEAVING COSTS ${span(cost)} OF POOL LOCK" else "LEAVING IS LOGGED. TODAY'S POOL LOCK CAP IS REACHED"
}

/** What shows after LEAVE was held to the end. */
internal fun leftText(run: Run, protocol: Protocol?, cfg: BailOutConfig, penaltyToday: Int, now: Instant): String {
    val free = leavesFree(run, protocol, now)
    val lines = mutableListOf(if (free && run.prayer != null && !run.test) "LEFT AFTER THE MINIMUM." else "LEFT EARLY. LOGGED.")
    val cost = if (free) 0 else bailOutCost(cfg, penaltyToday)
    if (cost > 0) lines += "THE POOL IS LOCKED ${span(cost)} MORE."
    if (run.prayer != null && !run.confirmed) lines += "CONFIRM ON HOME ONCE YOU HAVE PRAYED."
    when (run.jummah?.part) {
        JummahPart.LEAVE -> lines += "THE JUMMAH LOCK STILL COMES."
        JummahPart.LOCK -> lines += "CONFIRM JUMMAH ON HOME ONCE YOU HAVE PRAYED."
        JummahPart.TRAVEL, JummahPart.CONFIRM, null -> Unit
    }
    return lines.joinToString("\n")
}

/**
 * What shows when the run stopped owning the screen but is still open: its lock ended (or VIGIL was suspended)
 * before it was finished, or it was left from somewhere else.
 */
internal fun lockOverText(run: Run): String {
    val jummah = run.jummah
    if (jummah != null) {
        return when {
            jummah.part == JummahPart.TRAVEL && jummah.arrived -> "AT UNI.\nWHEN IT IS TIME, GO STRAIGHT TO THE PRAYER PLACE."
            jummah.part == JummahPart.TRAVEL && run.bailed -> "LEFT EARLY. LOGGED.\nTHE JUMMAH LOCK STILL COMES."
            jummah.part == JummahPart.TRAVEL -> "ON YOUR WAY.\nTAP I'M AT UNI ONCE YOU ARE THERE."
            else -> (if (run.bailed) "LEFT EARLY. LOGGED." else "THE LOCK IS OVER.") + "\nCONFIRM JUMMAH ON HOME ONCE YOU HAVE PRAYED."
        }
    }
    val head = when {
        run.released -> "LEFT AFTER THE MINIMUM."
        run.bailed -> "LEFT EARLY. LOGGED."
        else -> "THE LOCK IS OVER."
    }
    val tail = when {
        run.prayer == null -> "FINISH IT ON HOME."
        !run.confirmed -> "CONFIRM ON HOME ONCE YOU HAVE PRAYED."
        else -> null
    }
    return if (tail == null) head else "$head\n$tail"
}

/** Journal entry types that end a run (it leaves the engine's state); the run's last one says how it ended. */
internal val RUN_ENDINGS = setOf(EntryType.COMPLETED, EntryType.MISSED, EntryType.DROPPED, EntryType.BAILED)

/**
 * What shows when [entry] (one of [RUN_ENDINGS]) ended the run while its takeover was on screen; null for a
 * completion, which the filled eye closing says by itself.
 */
internal fun endText(entry: Entry): String? = when (entry.type) {
    EntryType.COMPLETED -> null
    EntryType.MISSED -> when (entry.reason) {
        "window_closed" -> "THE WINDOW CLOSED.\nLOGGED AS MISSED."
        "next_adhan" -> "THE NEXT ADHAN CAME.\nLOGGED AS MISSED."
        "day_end" -> "THE DAY ENDED.\nLOGGED AS MISSED."
        else -> "LOGGED AS MISSED."
    }
    EntryType.BAILED -> "LEFT EARLY. LOGGED."
    EntryType.DROPPED -> if (entry.reason == "kill_switch") "VIGIL IS SUSPENDED." else "ENDED. LOGGED."
    else -> "ENDED. LOGGED."
}

/** A number of minutes as words: 45 MIN, 1 HOUR, 2 HOURS, 1 H 30 MIN. */
internal fun span(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m MIN"
        m == 0 -> if (h == 1) "1 HOUR" else "$h HOURS"
        else -> "$h H $m MIN"
    }
}

/** A countdown: 4:05, or 1:04:05 from an hour up. Never negative. */
internal fun countdown(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

/** Whole minutes from [nowSec] (epoch seconds) until [until], rounded up, at least 1. */
internal fun minutesUntil(until: Instant, nowSec: Long): Long = ((until.epochSecond - nowSec + 59) / 60).coerceAtLeast(1L)

/** Whole seconds from [nowSec] (epoch seconds) until [until], rounded up, never negative. */
internal fun secondsUntil(until: Instant, nowSec: Long): Long {
    val partial = if (until.nano > 0) 1L else 0L
    return (until.epochSecond + partial - nowSec).coerceAtLeast(0L)
}

/**
 * How far [run] has come, 0..1: the steps done (a running timer counts in part) or the lock's time gone, whichever
 * is further. Only a rough measure: the eye shows it late and in coarse steps ([coarse]).
 */
internal fun runProgress(run: Run, view: StepView?, now: Instant): Float {
    var steps = 0f
    if (view != null && view.total > 0) {
        var done = view.index.toFloat()
        val ends = view.timerEndsAt
        val started = run.stepStartedAt
        if (view.step.type == StepType.TIMER && ends != null && started != null && ends.isAfter(started)) {
            done += fraction(started, ends, now)
        }
        steps = done / view.total
    }
    val lockStart = run.lockStartedAt
    val lockEnd = view?.lockEndsAt ?: run.lockEndsAt
    val lock = if (lockStart != null && lockEnd != null && lockEnd.isAfter(lockStart)) fraction(lockStart, lockEnd, now) else 0f
    return max(steps, lock).coerceIn(0f, 1f)
}

private fun fraction(from: Instant, to: Instant, now: Instant): Float {
    val total = Duration.between(from, to).toMillis().toFloat()
    if (total <= 0f) return 1f
    return (Duration.between(from, now).toMillis() / total).coerceIn(0f, 1f)
}

/** The eye's fill comes in this many steps. */
internal const val FILL_STEPS = 6

/** [progress] cut down to a whole fill step; never full while the run lasts (the eye fills up when it is done). */
internal fun coarse(progress: Float): Float =
    (floor(progress.coerceIn(0f, 1f) * FILL_STEPS) / FILL_STEPS).coerceAtMost((FILL_STEPS - 1f) / FILL_STEPS)
