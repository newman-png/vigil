package dev.vigil.core.machine

import dev.vigil.core.journal.Entry
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.protocol.BundledProtocols
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ProtocolFile
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.times.MyTimes
import java.time.Instant
import java.time.ZoneId

/** The world a step runs in: the clock, the time zone and the protocols file. */
data class MachineEnv(
    val now: Instant,
    val zone: ZoneId,
    val protocols: ProtocolFile = BundledProtocols.file,
)

/** Something that happened, fed to [Machine.step] by the Android layer. */
sealed interface Input {
    /** Periodic heartbeat (and after boot, time or zone changes): applies everything due by now. */
    data object Tick : Input

    /** An exact alarm set from [Machine.nextWake] fired; late by more than a minute = alarm_late. */
    data class Alarm(val scheduledAt: Instant? = null) : Input

    /** The adhan app posted its notification for [prayer] (AdhanMatcher on the app side). */
    data class Adhan(val prayer: Prayer) : Input

    /** The wake alarm rang (or was dismissed): ends the day the morning after, starts `event:wake_alarm` protocols. */
    data object WakeAlarm : Input

    /** A home switch changed. */
    data class Toggle(val name: ToggleName, val on: Boolean) : Input

    /**
     * A protocol started from a button. For a prayer this acts like its adhan (with the same dedupe). [test] = a
     * TEST-screen preview at stage 1: journaled with `test: true`, no stages, memory, penalties or report counts.
     */
    data class Manual(val protocolId: String, val test: Boolean = false) : Input

    /** The step [index] of run [runId] was tapped; [text] for text steps. Stale or locked taps are ignored. */
    data class StepDone(val runId: String, val index: Int, val text: String? = null) : Input

    /** CONFIRM on home or a card/notification: a prayer was prayed, or a stage-3 reminder was done. */
    data class Confirm(val runId: String) : Input

    /** "I'M NOT OKAY" on the takeover: one rung down the ladder (prayer: lock shortened to its minimum). */
    data class NotOkay(val runId: String) : Input

    /** LEAVE held for 10 seconds: quit the run or lock early. */
    data class BailOut(val runId: String) : Input

    /** A phone call connected (on) or ended (off). */
    data class CallActive(val on: Boolean) : Input

    /** The screen turned on or off. */
    data class Screen(val on: Boolean, val unlocked: Boolean = on) : Input

    /** The app in front changed ([app] null = none, e.g. screen off). [isPool] as the app judges (pool list + browsers). */
    data class Foreground(val app: String?, val isPool: Boolean) : Input

    /** The pool wait screen started for [app]. */
    data class PoolWaitStarted(val app: String) : Input

    /** The pool wait screen reached its end for [app]: a session starts if the wait really lasted. */
    data class PoolWaitDone(val app: String) : Input

    /** The pool wait screen was left early. */
    data class PoolWaitAbandoned(val app: String) : Input

    /** GOING TO SLEEP: the day ends now. */
    data object GoingToSleep : Input

    /** Kill switch (after its 5-minute wait, or the adb escape): suspend VIGIL for [minutes]. */
    data class Suspend(val minutes: Int = 60) : Input

    /** End a suspension early. */
    data object Resume : Input

    /** MY TIMES saved: applies from the next day ([applyNow] only counts for the very first edit). */
    data class EditMyTimes(val times: MyTimes, val applyNow: Boolean = false) : Input

    /** On app start: a previous process exit (ApplicationExitInfo reason, when, detail). */
    data class AppExit(val reason: String, val at: Instant? = null, val detail: String? = null) : Input

    /** The speed bump covered a settings page ([target]: app_info, accessibility, notification_access, device_admin, uninstall). */
    data class SpeedBump(val target: String, val detail: String? = null) : Input
}

/** What a notification is for (the app picks channel and look). */
enum class NotifyKind {
    /** The subtle heads-up before a prayer. Cancelled when the adhan comes; give it a timeout in case it never does. */
    HEADS_UP,

    /** A prayer during a class: silent card. */
    CLASS_CARD,

    /** An open prayer waiting for CONFIRM. */
    CONFIRM,

    /** A stage-3 reminder (the one notification). */
    REMINDER,

    /** A run without a screen lock (pool or no lock): its steps are on home. */
    RUN,

    /** A prayer window about to close (stage 3, or while suspended). */
    WARNING,
}

/** Something the Android layer must do after a step. */
sealed interface Effect {
    /** Show (or replace) notification [id]. */
    data class Notify(
        val id: String,
        val title: String,
        val text: String,
        val silent: Boolean,
        val kind: NotifyKind,
        val runId: String? = null,
    ) : Effect

    data class CancelNotify(val id: String) : Effect

    /**
     * Run [runId] now owns the screen (emitted once, whenever the screen passes to a run): launch its takeover. After
     * every step the takeover shows [Machine.activeRun]; when that is null the takeover closes. Re-asserting a takeover
     * the user left is the app's job while [Machine.reasserts] is true.
     */
    data class ShowTakeover(val runId: String) : Effect

    /**
     * The user entered pool app [app] without a session: show the wait screen ([PoolDecision.Wait]) or close the app
     * and show why ([PoolDecision.Blocked]).
     */
    data class PoolGate(val app: String, val decision: PoolDecision) : Effect

    /** Close the front pool app now (the session ended: heavy use, window over, a lock began) and show [reason]. */
    data class Kick(val app: String?, val reason: BlockReason) : Effect

    /** Ring VIGIL's own wake alarm (M3). */
    data object RingWakeAlarm : Effect

    /** Do Not Disturb on/off (M4 Jummah). */
    data class SetDnd(val on: Boolean) : Effect

    /** Audio control (M8): [action] play/stop, [sound] a bundled sound or an uploaded file. */
    data class Audio(val action: String, val sound: String? = null) : Effect
}

/** The result of [Machine.step]: the new state to persist, journal entries to append, and effects to run. */
data class Outcome(val state: MachineState, val entries: List<Entry>, val effects: List<Effect>)
