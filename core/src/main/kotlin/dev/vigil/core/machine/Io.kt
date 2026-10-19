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

    /**
     * The wake alarm was dismissed (held on its screen): ends the day if it has not ended, ends the night lock, journals
     * the wake time and starts the `event:wake_alarm` protocols (the morning). Ignored (journaled as skipped) while no
     * alarm rings or waits to ring again. [test] = a TEST ring: the morning starts as a TEST preview and nothing else
     * changes.
     */
    data class WakeAlarm(val test: Boolean = false) : Input

    /** The wake alarm rang for its `ring_max_min` without an answer and the app stopped it: it rings again later. */
    data class WakeAlarmUnanswered(val test: Boolean = false) : Input

    /** TEST > RING WAKE ALARM NOW: ring the alarm now as a TEST (its dismissal previews the morning). */
    data object RingWakeAlarmTest : Input

    /** A to-do of today's list was ticked off ([done]) or unticked. */
    data class TodoDone(val index: Int, val done: Boolean = true) : Input

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

    /**
     * GOING TO SLEEP (home, or the wind-down's last step): the wind-down completes, the day ends now and the night lock
     * starts. Ignored before the day's wake time. [ishaPrayed]: the app asked about an open Isha ([HomeStatus.ishaToConfirm])
     * and the answer was yes, so Isha is confirmed first (it counts, even inside its done lock).
     */
    data class GoingToSleep(val ishaPrayed: Boolean = false) : Input

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

    /**
     * LATER (home or the VIGIL notification): a 10-minute promise of [text] (its first line) in [minutes] (the promise
     * protocol's `delays`, 1-240). One at a time. [test] = a TEST preview that counts for nothing.
     */
    data class Promise(val text: String, val minutes: Int, val test: Boolean = false) : Input

    /** SNOOZE on a promise that came due: once per promise, half its delay (at least `snooze_min_min`). */
    data class Snooze(val runId: String) : Input

    /**
     * ON IT (a promise that came due: the takeover closes, the pool lock stays) or NOT YET (the I'M AT UNI ask: it asks
     * again `arrive_repeat_min` later): the asking takeover of run [runId] closes.
     */
    data class Dismiss(val runId: String) : Input

    /** The answer to [MachineState.offer]: START (the offered protocol starts now) or NOT NOW (costs nothing). */
    data class AnswerOffer(val start: Boolean) : Input

    /** The app could not switch Do Not Disturb (no access granted): journaled; the lock shows all the same. */
    data object DndNoAccess : Input

    /** TEST > JUMMAH: one part of Jummah as a preview now (the card is only its silent notification). */
    data class TestJummah(val part: JummahTestPart) : Input
}

/** The parts of Jummah the TEST screen can fire. */
enum class JummahTestPart { LEAVE, ARRIVAL, CARD, LOCK }

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

    /** A run without a screen lock (pool or no lock): its current step, and its takeover opens from it. */
    RUN,

    /** A prayer window about to close (stage 3, or while suspended). */
    WARNING,

    /** A silent card with nothing to answer (Jummah: the class ends in 5 minutes). */
    CARD,

    /** A protocol offered (the reset): a full-intensity card with START and NOT NOW ([Input.AnswerOffer]). */
    OFFER,

    /** A 10-minute promise: its line, with DONE ([Input.Confirm]). */
    PROMISE,
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

    /**
     * Ring VIGIL's wake alarm now (ring [ring] of the alarm due at [scheduledAt]) until [stopAt], then stop and report
     * [Input.WakeAlarmUnanswered]. A full-screen alarm on the alarm stream; its dismissal is [Input.WakeAlarm].
     */
    data class RingWakeAlarm(val scheduledAt: Instant, val ring: Int, val stopAt: Instant, val test: Boolean = false) : Effect

    /** Stop a ringing wake alarm (the engine counted it unanswered, or it is over). */
    data object StopWakeAlarm : Effect

    /**
     * Do Not Disturb on (the Jummah lock starts) or off (it ends, whatever ends it: DONE, its time, LEAVE, the kill
     * switch, the day end). Off restores what the user had before; [Machine.dndWanted] says whether it should be on.
     */
    data class SetDnd(val on: Boolean) : Effect

    /** Audio control: [action] play/stop, [sound] `white_noise` ([WHITE_NOISE]; M8 adds more sounds and uploaded files). */
    data class Audio(val action: String, val sound: String? = null) : Effect {
        companion object {
            const val PLAY = "play"
            const val STOP = "stop"
            const val WHITE_NOISE = "white_noise"
        }
    }
}

/** The result of [Machine.step]: the new state to persist, journal entries to append, and effects to run. */
data class Outcome(val state: MachineState, val entries: List<Entry>, val effects: List<Effect>)
