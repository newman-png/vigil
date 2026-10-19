package dev.vigil.core.machine

import dev.vigil.core.common.WallClock
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.pool.PoolRules
import dev.vigil.core.pool.PoolText
import dev.vigil.core.pool.WindowSpan
import dev.vigil.core.protocol.Intensity
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.ProtocolFile
import dev.vigil.core.protocol.Step
import dev.vigil.core.protocol.StepType
import dev.vigil.core.run.JummahPart
import dev.vigil.core.run.Run
import dev.vigil.core.run.RunPhase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import dev.vigil.core.timeline.StartSource
import dev.vigil.core.timeline.StartStatus
import dev.vigil.core.timeline.TimelineItem
import java.time.Duration
import java.time.Instant

/**
 * The v2 engine (design section 3): a pure reducer over [MachineState]. The Android layer persists the state, appends
 * the entries to the journal and runs the effects; it never decides anything itself.
 *
 * Every [step] first applies everything due by `env.now` (day boundary, timers, locks, prayer windows, heads-ups,
 * planned starts, pool sessions), then the input, then everything that input made due. Queries read a state as it is:
 * step a [Input.Tick] first when time has passed since the last step.
 */
object Machine {
    const val FIRST_START = "first_start"

    /** A repeat of the same prayer's adhan within this is ignored (adhan apps re-post their notifications). */
    val ADHAN_DEDUPE: Duration = Duration.ofMinutes(30)

    /**
     * An adhan for a prayer whose run is still open and started less than this ago is that same prayer again (a
     * re-post, a manual start): it neither misses that run nor starts a second one. A later one (the next day's: an Isha
     * that came after GOING TO SLEEP stays open through the next day) is a new prayer and closes the old run.
     */
    val SAME_PRAYER: Duration = Duration.ofHours(12)

    /** A planned start more than this late (phone off) does not fire late; the self-check logs it as not_fired. */
    val PLANNED_GRACE: Duration = Duration.ofMinutes(30)

    /** A prayer window estimate further ahead than this is ignored (stale memory). */
    val MAX_ESTIMATE_AHEAD: Duration = Duration.ofHours(20)

    /** Without an estimate for the next adhan, a prayer window closes after this at the latest (no warning). */
    val SAFETY_WINDOW: Duration = Duration.ofHours(12)

    /** An alarm later than this is logged as alarm_late. */
    const val ALARM_LATE_S = 60L

    /** A wait screen may report done this many seconds early (timer rounding). */
    const val WAIT_TOLERANCE_S = 2L

    /** After a long gap (phone off), at most this many days are ended one by one. */
    const val MAX_CATCH_UP_DAYS = 62

    /**
     * A call pause ends by itself after this long, as if the call had ended then: a lost "call ended" report must not
     * freeze every lock and timer for the rest of the day.
     */
    val MAX_CALL_PAUSE: Duration = Duration.ofHours(3)

    /** The walking switch turns itself off after this long (until step detection ends a walk by itself). */
    val MAX_WALKING: Duration = Duration.ofHours(3)

    /** The night lock starts only when the wake alarm is due within this long (GOING TO SLEEP at night, not at 08:00). */
    val MAX_NIGHT: Duration = Duration.ofHours(16)

    /** Applies [input] to [state] at `env.now`. Pure: same arguments, same outcome. */
    fun step(state: MachineState, input: Input, env: MachineEnv): Outcome {
        val t = Txn(state, env)
        t.advance()
        t.apply(input)
        t.advance()
        return t.outcome()
    }

    /** When the app must next wake (exact alarm) for [state], or null when nothing is timed. */
    fun nextWake(state: MachineState, env: MachineEnv): Instant? = View(state, env).nextWake()

    /**
     * When the wake alarm rings next (the app sets an alarm clock for it; at that moment a step rings it with
     * [Effect.RingWakeAlarm]): MY TIMES wake of the day it belongs to, or a ring again after an unanswered one. Null
     * while it rings, or when the file has no sleep or morning protocol.
     */
    fun wakeAlarmAt(state: MachineState, env: MachineEnv): Instant? = View(state, env).wakeAlarmAt()

    /** Today's to-dos (written in last night's wind-down), in order; ticked ones have `done`. */
    fun todos(state: MachineState, env: MachineEnv): List<Todo> = View(state, env).todosToday()

    /** The run that owns the screen (its takeover should show), or null. Null while suspended. */
    fun activeRun(state: MachineState, env: MachineEnv): Run? {
        val v = View(state, env)
        if (v.suspended) return null
        return v.screenOwner()
    }

    /**
     * True when leaving [run]'s takeover brings it back (stage 1); stage 2 shows once and is not re-asserted, and neither
     * is an asking takeover (I'M AT UNI asks again by itself; a promise keeps its notification).
     */
    fun reasserts(run: Run): Boolean = run.stage == 1 && !run.asking

    /** How loud [run]'s takeover is: its protocol's intensity, except the Jummah lock, a subtle and silent screen. */
    fun intensity(run: Run, file: ProtocolFile): Intensity =
        if (run.jummah?.part == JummahPart.LOCK) Intensity.SUBTLE else file.find(run.protocol)?.intensity ?: Intensity.FULL_THEN_SUBTLE

    /** True while VIGIL's Do Not Disturb should be on (a Jummah lock holds); the app turns off one it left on otherwise. */
    fun dndWanted(state: MachineState, env: MachineEnv): Boolean {
        val v = View(state, env)
        return state.runs.any { it.jummah?.dnd == true && v.lockActive(it) }
    }

    /**
     * The minutes a SNOOZE on promise [run] gives: half its delay (rounded up), at least `snooze_min_min`; null when no
     * snooze is offered (not due, or already snoozed once).
     */
    fun snoozeMinutes(run: Run, file: ProtocolFile): Int? {
        val promise = run.promise ?: return null
        if (promise.snoozed || run.phase != RunPhase.ACTIVE) return null
        val p = file.find(run.protocol) ?: return null
        return maxOf(p.intParam("snooze_min_min"), (promise.minutes + 1) / 2)
    }

    /** The steps [run] shows: its version on its weekday. */
    fun stepsFor(run: Run, env: MachineEnv): List<Step> = View(MachineState(), env).stepsOf(run)

    /**
     * The current step of run [runId] with its locks, for the takeover; null when there is no such run or step, and for
     * the night lock (it has no steps: [Run.phase] NIGHT is the night lock screen, [HomeStatus.nightLockUntil] its end).
     */
    fun stepView(state: MachineState, runId: String, env: MachineEnv): StepView? {
        val v = View(state, env)
        val r = v.run(runId)?.takeIf { it.phase != RunPhase.NIGHT } ?: return null
        val steps = v.stepsOf(r)
        val st = steps.getOrNull(r.step) ?: return null
        val locked = v.stepLockedUntil(r, st)
        val timerEnds = if (st.type == StepType.TIMER) v.timerEnd(r, st)?.plus(v.callShift(r)) else null
        val canTap = when (st.type) {
            StepType.TIMER -> !(r.stage == 1 && r.phase == RunPhase.ACTIVE)
            else -> locked == null
        }
        // The wind-down's last step is GOING TO SLEEP: completing it ends the day (an open Isha is asked about first).
        val sleepStep = r.kind == ProtocolKind.SLEEP && !r.test && r.step == steps.lastIndex
        // A reset's last step says what re-entering does now.
        val reentry = if (!st.reenter) null else {
            val line = v.reentryLine(v.reentryPlan(v.protocol(r)?.intParam("next_within_min") ?: 0))
            if (r.test) "TEST, NOTHING CHANGES. IT WOULD SAY: $line" else line
        }
        return StepView(
            r.step, steps.size, st, locked, timerEnds, canTap, r.isMinimum, v.lockEndShown(r),
            goingToSleep = sleepStep, ishaToConfirm = if (sleepStep) v.ishaToConfirm()?.id else null,
            arrival = st.arrival, reentry = reentry,
        )
    }

    /** May a pool app open now? [isPool] false = not a pool app = always allowed. */
    fun poolDecision(state: MachineState, env: MachineEnv, isPool: Boolean = true): PoolDecision = View(state, env).decision(isPool)

    /** Today's free windows (opening and closing moments, caps). */
    fun windows(state: MachineState, env: MachineEnv): List<WindowSpan> = View(state, env).windows()

    /** One line for the home screen, e.g. "POOL BLOCKED · COOLDOWN UNTIL 14:20". */
    fun poolStatus(state: MachineState, env: MachineEnv): String {
        val v = View(state, env)
        val zone = env.zone
        if (v.suspended) return "POOL OPEN · VIGIL SUSPENDED UNTIL ${WallClock.hhmm(state.suspendedUntil!!, zone)}"
        val windows = v.windows()
        val session = state.pool.session
        return when (val d = v.decision(true)) {
            is PoolDecision.Blocked -> {
                val b = d.blocks.first()
                val head = if (b.reason == BlockReason.OUTSIDE_WINDOW) "POOL CLOSED" else "POOL BLOCKED"
                "$head · ${PoolText.block(b, zone)}"
            }
            is PoolDecision.Wait -> {
                val w = PoolRules.openWindow(windows, state.pool, env.now)
                val left = w?.let { WallClock.minutesCeil(it.capSeconds - PoolRules.windowUsedSeconds(it.id, state.pool, env.now)) }
                "POOL OPEN" + (w?.let { " · ${it.id.uppercase()} $left MIN LEFT" } ?: "") + " · WAIT ${d.minutes} MIN"
            }
            PoolDecision.Allowed -> {
                val used = session?.let { WallClock.minutesCeil(it.usedAt(env.now)) } ?: 0
                val w = session?.windowId?.let { id -> windows.firstOrNull { it.id == id } }
                val left = w?.let { WallClock.minutesCeil(it.capSeconds - PoolRules.windowUsedSeconds(it.id, state.pool, env.now)) }
                "POOL IN USE · $used MIN" + (w?.let { " · ${it.id.uppercase()} $left MIN LEFT" } ?: "")
            }
        }
    }

    /** Today's timeline: planned starts with what happened to them and when each protocol last fired. */
    fun timeline(state: MachineState, env: MachineEnv): List<TimelineItem> {
        val v = View(state, env)
        val day = v.day
        return v.plan().map { start ->
            val status = when {
                start.source == StartSource.ADHAN -> when {
                    start.prayer != null && start.prayer in day.adhans -> StartStatus.FIRED
                    start.at.plus(PLANNED_GRACE).isBefore(env.now) -> StartStatus.NOT_FIRED
                    else -> StartStatus.PLANNED
                }
                day.handled[start.key] == "fired" -> StartStatus.FIRED
                day.handled[start.key] != null -> StartStatus.SKIPPED
                start.at.plus(PLANNED_GRACE).isBefore(env.now) -> StartStatus.NOT_FIRED
                else -> StartStatus.PLANNED
            }
            TimelineItem(start, status, state.lastFired[start.protocolId])
        }
    }

    /** True when [run]'s takeover and notifications must be silent (QUIET, or a prayer during a class). */
    fun isSilent(state: MachineState, run: Run): Boolean = state.toggles.quiet || run.silent

    /** Everything the home screen shows. */
    fun status(state: MachineState, env: MachineEnv): HomeStatus {
        val v = View(state, env)
        val day = v.day
        val active = activeRun(state, env)
        val toConfirm = state.runs.filter { it.prayer != null && !it.confirmed && it.phase != RunPhase.ACTIVE && it.phase != RunPhase.QUEUED }
        val next = timeline(state, env).firstOrNull { it.status == StartStatus.PLANNED && it.start.at.isAfter(env.now) }
        val wake = v.dayTimes().wake
        val alarm = state.wakeAlarm
        return HomeStatus(
            day = day.key,
            dayStartedAt = day.startedAt,
            dayEndsBy = v.fallback(),
            quiet = state.toggles.quiet,
            walking = state.toggles.walking,
            friends = state.toggles.friends,
            suspendedUntil = state.suspendedUntil?.takeIf { it.isAfter(env.now) },
            callActive = state.callSince != null,
            activeRun = active,
            runs = state.runs,
            toConfirm = toConfirm,
            pool = poolStatus(state, env),
            poolDecision = v.decision(true),
            confirmedToday = day.confirmed,
            missedToday = day.missed,
            nextStart = next,
            stages = env.protocols.protocols.associate { it.id to v.stageOf(it) },
            bedtime = if (v.sleepProtocol != null) v.dayTimes().bedtime else null,
            wakeAlarmAt = if (!v.alarmOn) null else alarm.scheduled.takeIf { alarm.ringingFor != null } ?: v.alarmAt(v.alarmKey()),
            wakeAlarmRinging = alarm.ringingSince != null,
            nightLockUntil = v.nightRun()?.lockEndsAt,
            todos = v.todosToday(),
            ishaToConfirm = v.ishaToConfirm(),
            sleepTapIgnored = if (env.now.isAfter(wake)) null else
                "BEFORE TODAY'S WAKE TIME (${WallClock.hhmm(wake, env.zone)}): THIS DAY BEGAN AT " +
                    "${WallClock.hhmm(day.startedAt, env.zone)} AND GOES ON.",
            missedNights = state.sleep.missedInARow,
            offer = state.offer,
            resetProtocol = v.resetProtocol?.id,
            promise = state.runs.firstOrNull { it.promise != null },
            promiseDelays = v.promiseProtocol?.let { p ->
                (p.param("delays") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: DEFAULT_DELAYS
            } ?: emptyList(),
            jummah = state.runs.firstOrNull { it.jummah != null && !it.test },
            jummahToday = day.jummah,
            dndOn = dndWanted(state, env),
        )
    }

    /** The promise's delays when its protocol lists none. */
    val DEFAULT_DELAYS = listOf(5, 10, 15, 30)

    /**
     * Whether [packageName] is in the pool: listed in the file, or an installed browser when `all_browsers` is on
     * ([installedBrowsers] = apps that resolve ACTION_VIEW https://, detected by the app).
     */
    fun isPoolApp(packageName: String, file: ProtocolFile, installedBrowsers: Set<String>): Boolean =
        packageName in file.pool.apps || (file.pool.allBrowsers && packageName in installedBrowsers)
}

/** The current step of a run, as the takeover shows it. */
data class StepView(
    val index: Int,
    val total: Int,
    val step: Step,
    /** The step cannot be tapped before this (check min, done lock, a timer's end at stage 1); null = now. */
    val lockedUntil: Instant?,
    /** For a timer: when it ends by itself. */
    val timerEndsAt: Instant?,
    val canTap: Boolean,
    /** The single-step version ("minimum"). */
    val minimum: Boolean,
    /** When the run's lock ends (live during a call), or null. */
    val lockEndsAt: Instant?,
    /** The wind-down's GOING TO SLEEP step: the app finishes it with [Input.GoingToSleep], which ends the day. */
    val goingToSleep: Boolean = false,
    /** On that step: the open Isha run to ask about first (CONFIRM ISHA), or null. */
    val ishaToConfirm: String? = null,
    /** Jummah's I'M AT UNI step: confirmed like any step (or [Input.Confirm]); NOT YET is [Input.Dismiss]. */
    val arrival: Boolean = false,
    /** A reset's re-enter step: what re-entering does now, e.g. "NEXT: ASR AT ~15:20." (null on other steps). */
    val reentry: String? = null,
)

/** Everything the home screen shows. */
data class HomeStatus(
    val day: String,
    val dayStartedAt: Instant,
    /** The latest the day can last (fallback end). */
    val dayEndsBy: Instant,
    val quiet: Boolean,
    val walking: Boolean,
    val friends: Boolean,
    val suspendedUntil: Instant?,
    val callActive: Boolean,
    /** The run owning the screen. */
    val activeRun: Run?,
    /** Every open run. */
    val runs: List<Run>,
    /** Prayers waiting for CONFIRM (lock over, card, reminder, returned). */
    val toConfirm: List<Run>,
    /** The one-line pool status. */
    val pool: String,
    val poolDecision: PoolDecision,
    val confirmedToday: List<Prayer>,
    val missedToday: List<Prayer>,
    /** The next planned start still ahead. */
    val nextStart: TimelineItem?,
    /** The current stage per protocol id. */
    val stages: Map<String, Int>,
    /** Tonight's target bedtime (sleep shifting), or null without a sleep protocol. */
    val bedtime: Instant? = null,
    /** When the next (or the ringing) wake alarm is due, or null when VIGIL rings none. */
    val wakeAlarmAt: Instant? = null,
    val wakeAlarmRinging: Boolean = false,
    /** The night lock holds until the wake alarm is answered, this at the latest; null when it is off. */
    val nightLockUntil: Instant? = null,
    /** Today's to-dos (tick with [Input.TodoDone]). */
    val todos: List<Todo> = emptyList(),
    /** An open, unconfirmed Isha: GOING TO SLEEP asks CONFIRM ISHA first ([Input.GoingToSleep] ishaPrayed). */
    val ishaToConfirm: Run? = null,
    /** Non-null when GOING TO SLEEP would be ignored now (before the day's wake time): the reason, for the user. */
    val sleepTapIgnored: String? = null,
    /** Missed nights in a row (sleep shifting holds the target after hold_after_missed of them). */
    val missedNights: Int = 0,
    /** A protocol offered now (the reset): START or NOT NOW ([Input.AnswerOffer]); null when none is. */
    val offer: Offer? = null,
    /** The reset protocol's id (the RESET button: [Input.Manual]), or null when the file has none. */
    val resetProtocol: String? = null,
    /** The 10-minute promise, counting down ([RunPhase.WAITING]) or due, or null. */
    val promise: Run? = null,
    /** LATER's choices in minutes (the promise protocol's `delays`); empty when the file has no promise protocol. */
    val promiseDelays: List<Int> = emptyList(),
    /** Today's Jummah run (Fridays), or null. */
    val jummah: Run? = null,
    /** Today's Jummah outcome: "confirmed", "missed" or null. */
    val jummahToday: String? = null,
    /** VIGIL's Do Not Disturb is on (the Jummah lock). */
    val dndOn: Boolean = false,
)
