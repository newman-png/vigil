package dev.vigil.engine

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.vigil.BuildConfig
import dev.vigil.Graph
import dev.vigil.core.common.WallClock
import dev.vigil.core.journal.Entry
import dev.vigil.core.journal.Report
import dev.vigil.core.journal.ReportMeta
import dev.vigil.core.machine.Effect
import dev.vigil.core.machine.HomeStatus
import dev.vigil.core.machine.Input
import dev.vigil.core.machine.Machine
import dev.vigil.core.machine.MachineEnv
import dev.vigil.core.machine.MachineState
import dev.vigil.core.machine.Outcome
import dev.vigil.core.machine.StepView
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ProtocolFile
import dev.vigil.core.protocol.Step
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.run.Run
import dev.vigil.core.timeline.TimelineItem
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.MyTimesStore
import dev.vigil.service.VigilService
import dev.vigil.takeover.TakeoverLauncher
import dev.vigil.trigger.AlarmScheduler
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration
import java.time.Instant

/**
 * The bridge between Android and the v2 engine ([Machine], design section 3). Every Android event becomes one engine
 * input; each step is saved with its journal entries ([dev.vigil.data.StateStore]) and its effects are carried out
 * here (notifications, the takeover, the Guard's kick, the next alarm). Screens, the takeover and the Guard read and
 * act only through this object. No decisions are made here: the app never thinks.
 */
object Vigil {
    private const val TAG = "Vigil"

    /** An alarm is always set at most this far ahead, so a lost alarm or a stopped service is noticed (heartbeat). */
    private val HEARTBEAT: Duration = Duration.ofMinutes(30)

    /** The adb kill switch accepts at most a week. */
    private const val MAX_SUSPEND_MINUTES = 7 * 24 * 60L

    /** [poolDecision] steps the engine first when the last step is older than this. */
    private const val FRESH_MS = 1_000L

    private val main = Handler(Looper.getMainLooper())

    /** When the last engine step ran (elapsed realtime). */
    @Volatile
    private var lastStepAt = 0L

    /** The status line last put on the always-on notification. */
    @Volatile
    private var lastStatus: String? = null

    // ------------------------------------------------------------------ state

    /** The persisted engine state, live. */
    val state: StateFlow<MachineState> get() = Graph.state.state

    /** The bundled protocols.json, parsed (a bad edit fails the core unit tests, so it never ships). */
    fun file(): ProtocolFile = Graph.env().protocols

    /** MY TIMES: the current times and the edit waiting for tomorrow ([MyTimesStore.upcoming] is what tomorrow uses). */
    fun myTimes(): MyTimesStore = state.value.times

    /**
     * Saves MY TIMES. A change applies from tomorrow (the next day start); only the very first save (first setup, when
     * [MyTimesStore.edited] is still false) applies at once.
     */
    fun saveMyTimes(times: MyTimes) {
        exec(Input.EditMyTimes(times, applyNow = !state.value.times.edited))
    }

    // ------------------------------------------------------------------ Guard (pool enforcement)

    /**
     * Set by the Guard (the accessibility service) while it runs: closes pool app [app] (null = whatever pool app is
     * in front) at once and shows why ([reason], e.g. heavy use). Called on the main thread for the engine's Kick
     * effect. Without a Guard, VIGIL goes to the home screen instead (needs "display over other apps").
     */
    @Volatile
    var onKick: ((app: String?, reason: BlockReason) -> Unit)? = null

    /** True for a pool app: listed in protocols.json, or any installed browser. VIGIL itself never is. */
    fun isPoolApp(packageName: String): Boolean {
        val app = Graph.app
        if (packageName == app.packageName) return false
        return Machine.isPoolApp(packageName, file(), Device.browsers(app))
    }

    /** May [packageName] be used now? Not a pool app = always [PoolDecision.Allowed]. */
    fun poolDecision(packageName: String): PoolDecision {
        val isPool = isPoolApp(packageName)
        // A session that ended or a lock that started since the last step must count.
        if (SystemClock.elapsedRealtime() - lastStepAt > FRESH_MS) exec(Input.Tick)
        return Machine.poolDecision(state.value, Graph.env(), isPool)
    }

    /** The app in front changed (from the Guard); null = none (screen off, or unknown). */
    fun onForeground(packageName: String?) {
        exec(Input.Foreground(packageName, packageName != null && isPoolApp(packageName)))
        // Leaving the takeover for a non-whitelisted app brings it back (TakeoverLauncher's rules decide).
        if (packageName != null && !TakeoverLauncher.visible && Machine.activeRun(state.value, Graph.env()) != null) {
            TakeoverLauncher.onScreenOn(Graph.app)
        }
    }

    /** The pool wait screen started for [packageName]. */
    fun poolWaitStarted(packageName: String) {
        exec(Input.PoolWaitStarted(packageName))
    }

    /** The pool wait screen reached its end for [packageName] (a session starts if the wait really lasted). */
    fun poolWaitDone(packageName: String) {
        exec(Input.PoolWaitDone(packageName))
    }

    /** The pool wait screen was left early (the same wait applies next time). */
    fun poolWaitAbandoned(packageName: String) {
        exec(Input.PoolWaitAbandoned(packageName))
    }

    /** The APPS button's apps (the whitelist), resolved on this phone. */
    fun whitelistApps(): List<WhitelistApp> = Whitelist.apps(Graph.app, file().appsButton, Graph.settings.adhanApps)

    /** True for a whitelisted app (usable in every lock); phone, dialer and emergency apps always are. */
    fun isWhitelisted(packageName: String): Boolean =
        Device.isCallApp(Graph.app, packageName) ||
            packageName in Whitelist.packages(Graph.app, file().appsButton, Graph.settings.adhanApps)

    /** Journals that the speed bump covered [what] (app_info, accessibility, notification_access, device_admin, uninstall). */
    fun speedBump(what: String) {
        exec(Input.SpeedBump(what))
    }

    // ------------------------------------------------------------------ runs (takeover)

    /** The run that owns the screen now (its takeover should show), or null (also while suspended). */
    fun activeRun(): Run? = Machine.activeRun(state.value, Graph.env())

    /** The steps [run] shows: its version on its weekday. */
    fun steps(run: Run): List<Step> = Machine.stepsFor(run, Graph.env())

    /** The current step of run [runId] with its locks and timers, or null. */
    fun stepView(runId: String): StepView? = Machine.stepView(state.value, runId, Graph.env())

    /** True when [run]'s takeover must be silent (QUIET, or a prayer during a class). */
    fun isSilent(run: Run): Boolean = Machine.isSilent(state.value, run)

    /** Step [index] of run [runId] was finished ([text] for text steps). Stale or locked taps are ignored. */
    fun stepDone(runId: String, index: Int, text: String? = null) {
        exec(Input.StepDone(runId, index, text))
    }

    /** CONFIRM: a prayer was prayed (or a stage-3 reminder done). */
    fun confirm(runId: String) {
        exec(Input.Confirm(runId))
    }

    /** "I'M NOT OKAY": one rung down the ladder (a prayer's lock shortens to its minimum). */
    fun notOkay(runId: String) {
        exec(Input.NotOkay(runId))
    }

    /** LEAVE held: quit the run early (logged; the pool penalty follows). */
    fun bailOut(runId: String) {
        exec(Input.BailOut(runId))
    }

    // ------------------------------------------------------------------ home

    /** GOING TO SLEEP: the day ends now. */
    fun goingToSleep() {
        exec(Input.GoingToSleep)
    }

    /** QUIET mode: every takeover silent. */
    fun setQuiet(on: Boolean) {
        exec(Input.Toggle(ToggleName.QUIET, on))
    }

    /** A home switch (QUIET, walking, friends). */
    fun setToggle(name: ToggleName, on: Boolean) {
        exec(Input.Toggle(name, on))
    }

    /**
     * Starts [protocolId] now. [test] true (the default, for the TEST screen) runs a preview: stage 1, journaled as a
     * test, no stages, memory, penalties or report counts. [test] false is a real manual start (a prayer then counts
     * as its adhan).
     */
    fun startManual(protocolId: String, test: Boolean = true) {
        exec(Input.Manual(protocolId, test))
    }

    /** Today's planned starts with what happened to them and when each protocol last fired. */
    fun timeline(): List<TimelineItem> = Machine.timeline(state.value, Graph.env())

    /** One line about the pool, e.g. "POOL BLOCKED · COOLDOWN UNTIL 14:20". */
    fun poolStatus(): String = Machine.poolStatus(state.value, Graph.env())

    /** Everything the home screen shows, in one read. */
    fun status(): HomeStatus = Machine.status(state.value, Graph.env())

    /** Kill switch (after its 5-minute wait, or adb): no takeovers and the pool open for [minutes]. */
    fun suspend(minutes: Long) {
        exec(Input.Suspend(minutes.coerceIn(1L, MAX_SUSPEND_MINUTES).toInt()))
    }

    /** Ends a suspension early. */
    fun resume() {
        exec(Input.Resume)
    }

    /** The progress report (markdown) and the journal (CSV), built from the whole journal. Blocking: reads a file. */
    fun report(): Pair<String, String> {
        val entries = Graph.journal.readAll()
        val meta = ReportMeta(Instant.now(), Graph.zone, BuildConfig.VERSION_NAME)
        return Report.markdown(entries, meta) to Report.csv(entries)
    }

    /** The newest [count] journal entries, newest last (TEST screen). Blocking: reads a file. */
    fun recentEntries(count: Int): List<Entry> = Graph.journal.tail(count)

    // ------------------------------------------------------------------ Android events

    /** The process started: notification channels, exits since the last start, catch-up, the alarm and the service. */
    fun onAppStart(context: Context) {
        Notifications.createChannels(context)
        try {
            ExitReasons.newExits(context).forEach { (reason, at, detail) -> exec(Input.AppExit(reason, at, detail)) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not journal the exit reasons", e)
        }
        exec(Input.Tick)
        VigilService.start(context)
    }

    /** The service's heartbeat: applies everything due by now. */
    fun onTick() {
        exec(Input.Tick)
    }

    /** The engine's exact alarm went off; [scheduledAt] = when it was set for (a late alarm is journaled). */
    fun onAlarm(scheduledAt: Instant?) {
        exec(Input.Alarm(scheduledAt))
    }

    /** Boot, app update, clock or time zone change: catch up and set the alarm again. */
    fun onSystemEvent() {
        AlarmScheduler.schedule(Graph.app, null)
        exec(Input.Tick)
    }

    /** The screen turned on ([unlocked] when the phone is usable) or off. */
    fun onScreen(on: Boolean, unlocked: Boolean) {
        exec(Input.Screen(on, unlocked))
        if (on && Machine.activeRun(state.value, Graph.env()) != null) TakeoverLauncher.onScreenOn(Graph.app)
    }

    /** A call connected ([active]) or ended: protocol timers and locks pause for its length. */
    fun onCall(active: Boolean) {
        exec(Input.CallActive(active))
    }

    /** The adhan app announced [prayer]. */
    fun onAdhan(prayer: Prayer) {
        exec(Input.Adhan(prayer))
    }

    /** One line for the always-on notification. */
    fun statusLine(): String {
        val s = state.value
        val env = Graph.env()
        s.suspendedUntil?.takeIf { it.isAfter(env.now) }?.let { return "Suspended until ${WallClock.hhmm(it, env.zone)} (kill switch)." }
        val parts = mutableListOf<String>()
        Machine.activeRun(s, env)?.let { r -> parts += "${env.protocols.find(r.protocol)?.title ?: r.protocol} RUNNING" }
        parts += Machine.poolStatus(s, env)
        Machine.status(s, env).nextStart?.let { parts += "NEXT ${it.start.title} ${WallClock.hhmm(it.start.at, env.zone)}" }
        return parts.joinToString(" · ")
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Runs one engine step for [input] now, then carries out its effects: notices, the takeover, the Guard's kick, the
     * next alarm and the always-on notification. Effects run on the calling thread; each one on its own, so one that
     * fails does not stop the others.
     */
    internal fun exec(input: Input): Outcome {
        val (before, outcome) = Graph.state.step(input) { Graph.env() }
        lastStepAt = SystemClock.elapsedRealtime()
        val env = Graph.env()
        val app = Graph.app
        for (effect in outcome.effects) {
            try {
                execute(app, effect)
            } catch (e: Exception) {
                Log.e(TAG, "Effect $effect failed", e)
            }
        }
        guard("Takeover") {
            // Nothing owns the screen any more (finished, lock over, suspended): the takeover goes away.
            val owner = Machine.activeRun(outcome.state, env)
            val shown = outcome.effects.any { it is Effect.ShowTakeover }
            if (owner == null && (shown || Machine.activeRun(before, env) != null)) TakeoverLauncher.onClosed(app)
        }
        guard("Alarm") { scheduleAlarm(outcome.state, env) }
        guard("Status") {
            // Also when only the clock moved it on (minutes left, a wait that grew), not just the state.
            val line = statusLine()
            if (line != lastStatus) {
                lastStatus = line
                VigilService.refreshNotification(app, line)
            }
        }
        return outcome
    }

    private fun execute(context: Context, effect: Effect) {
        when (effect) {
            is Effect.Notify -> Notifications.show(context, effect)
            is Effect.CancelNotify -> Notifications.cancel(context, effect.id)
            is Effect.ShowTakeover -> TakeoverLauncher.ensureShowing(context)
            // The Guard asks for the decision itself whenever a pool app comes to the front.
            is Effect.PoolGate -> Unit
            is Effect.Kick -> kick(context, effect)
            Effect.RingWakeAlarm -> Log.i(TAG, "The wake alarm is not part of this version (M3)")
            is Effect.SetDnd -> Log.i(TAG, "Do Not Disturb is not part of this version (M4): ${effect.on}")
            is Effect.Audio -> Log.i(TAG, "Audio is not part of this version (M8): ${effect.action} ${effect.sound}")
        }
    }

    private fun kick(context: Context, effect: Effect.Kick) {
        val handler = onKick
        if (handler != null) {
            main.post {
                try {
                    handler(effect.app, effect.reason)
                } catch (e: Exception) {
                    Log.e(TAG, "The Guard could not close ${effect.app}", e)
                }
            }
            return
        }
        // No Guard: at least leave the pool app for the home screen. Never during a call.
        if (Device.inCall(context) || !Settings.canDrawOverlays(context)) return
        try {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not go to the home screen: ${e.message}")
        }
    }

    /**
     * Keeps the exact alarm at the engine's next wake ([Machine.nextWake]), at most [HEARTBEAT] ahead. An alarm that is
     * still ahead and not later than needed is left alone (one set too early only wakes the engine once more).
     */
    private fun scheduleAlarm(state: MachineState, env: MachineEnv) {
        val heartbeat = env.now.plus(HEARTBEAT)
        val wake = Machine.nextWake(state, env)
        val want = if (wake == null || wake.isAfter(heartbeat)) heartbeat else wake
        val set = AlarmScheduler.scheduledAt
        if (set == null || !set.isAfter(env.now) || set.isAfter(want)) AlarmScheduler.schedule(Graph.app, want)
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "$what failed", e)
        }
    }
}
