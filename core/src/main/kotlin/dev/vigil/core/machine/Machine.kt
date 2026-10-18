package dev.vigil.core.machine

import dev.vigil.core.common.WallClock
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.pool.PoolRules
import dev.vigil.core.pool.PoolText
import dev.vigil.core.pool.WindowSpan
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ProtocolFile
import dev.vigil.core.protocol.Step
import dev.vigil.core.protocol.StepType
import dev.vigil.core.run.Run
import dev.vigil.core.run.RunPhase
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

    /** The run that owns the screen (its takeover should show), or null. Null while suspended. */
    fun activeRun(state: MachineState, env: MachineEnv): Run? {
        val v = View(state, env)
        if (v.suspended) return null
        return v.screenOwner()
    }

    /** True when leaving [run]'s takeover brings it back (stage 1); stage 2 shows once and is not re-asserted. */
    fun reasserts(run: Run): Boolean = run.stage == 1

    /** The steps [run] shows: its version on its weekday. */
    fun stepsFor(run: Run, env: MachineEnv): List<Step> = View(MachineState(), env).stepsOf(run)

    /** The current step of run [runId] with its locks, for the takeover; null when there is no such run or step. */
    fun stepView(state: MachineState, runId: String, env: MachineEnv): StepView? {
        val v = View(state, env)
        val r = v.run(runId) ?: return null
        val steps = v.stepsOf(r)
        val st = steps.getOrNull(r.step) ?: return null
        val locked = v.stepLockedUntil(r, st)
        val timerEnds = if (st.type == StepType.TIMER) v.timerEnd(r, st)?.plus(v.callShift(r)) else null
        val canTap = when (st.type) {
            StepType.TIMER -> !(r.stage == 1 && r.phase == RunPhase.ACTIVE)
            else -> locked == null
        }
        return StepView(r.step, steps.size, st, locked, timerEnds, canTap, r.isMinimum, v.lockEndShown(r))
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
        )
    }

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
)
