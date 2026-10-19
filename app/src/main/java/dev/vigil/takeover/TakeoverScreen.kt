package dev.vigil.takeover

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.unit.dp
import dev.vigil.R
import dev.vigil.core.common.WallClock
import dev.vigil.core.journal.Entry
import dev.vigil.core.journal.EntryType
import dev.vigil.core.machine.CallView
import dev.vigil.core.machine.StepView
import dev.vigil.core.protocol.BailOutConfig
import dev.vigil.core.protocol.Intensity
import dev.vigil.core.protocol.Protocol
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.Step
import dev.vigil.core.protocol.StepType
import dev.vigil.core.protocol.TakeoverConfig
import dev.vigil.core.run.JummahPart
import dev.vigil.core.run.Run
import dev.vigil.core.run.RunPhase
import dev.vigil.engine.WhitelistApp
import dev.vigil.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** What the takeover screen reads and reports. Each call goes straight to the engine; the screen decides nothing. */
internal interface TakeoverCallbacks {
    /** The current step of run [runId] with its locks and timers (read again often: locks run out with time). */
    fun stepView(runId: String): StepView?

    /** Run [runId] as the engine has it now, owning the screen or not; null once it is over. */
    fun run(runId: String): Run?

    /**
     * How run [runId] ended once it is over: its last [RUN_ENDINGS] journal entry, or null when none is found (yet).
     * Blocking: reads the journal, so it is called off the main thread.
     */
    fun ending(runId: String): Entry?

    /** Step [index] of run [runId] was finished ([text] for a text step). The engine ignores a locked or stale one. */
    fun stepDone(runId: String, index: Int, text: String?)

    /** A prayer's done step was held: it was prayed. */
    fun confirm(runId: String)

    /** NOT OKAY was held to the end: one rung down the ladder (a prayer's lock shortens). */
    fun notOkay(runId: String)

    /** LEAVE was held to the end: the run is left early (logged, the pool penalty follows). */
    fun bailOut(runId: String)

    /** ON IT (a promise: the pool lock stays) or NOT YET (I'M AT UNI: it asks again later): the asking takeover closes. */
    fun dismiss(runId: String)

    /** SNOOZE on a promise that came due. */
    fun snooze(runId: String)

    /** The minutes SNOOZE gives promise [runId] now, or null when no snooze is offered. */
    fun snoozeMinutes(runId: String): Int?

    /**
     * The wind-down's GOING TO SLEEP step, after CONFIRM ISHA was answered: the day ends ([ishaPrayed]: Isha was prayed,
     * so it counts).
     */
    fun goingToSleep(ishaPrayed: Boolean)

    /** A timer or the lock ran out on the clock: bring the engine up to date (its alarm does too). */
    fun tick()

    /** CALL was tapped: the takeover steps aside and the dialer opens. Never blocked. */
    fun call()

    /** MESSAGE on a call card's short version: WhatsApp (or the messaging app) opens. */
    fun message()

    /** The APPS button's apps, resolved on this phone. Blocking: called off the main thread. */
    fun apps(): List<WhitelistApp>

    /** An app of the APPS sheet was picked: the takeover leaves for it. */
    fun openApp(app: WhitelistApp)

    /** Seven taps on the eye: the hidden kill switch (a 5-minute wait, then VIGIL suspends for an hour). */
    fun killSwitch()

    /** A text step is being typed: the display stays on (the keyboard's keys never reach the activity). */
    fun typing()

    /** This takeover's intro has played to the open eye: coming back to it later skips the intro. */
    fun introSeen()

    /** LOG SET on gym run [runId]'s workout ([restS]: the rest set for this exercise, when it was changed). */
    fun logSet(runId: String, exercise: String, reps: Int, weight: Double, restS: Int?)

    /** FINISH WORKOUT on gym run [runId]. */
    fun finishWorkout(runId: String)

    /** A timer marked `chime` ran out, or the gym's rest did: the soft chime, unless QUIET ([loud]: the alarm stream). */
    fun chime(loud: Boolean)

    /** The takeover has left the screen; show the next one or close. */
    fun finished()
}

/** Where the takeover is in its sequence. */
private enum class Phase {
    /** Total silence before anything shows. */
    BLACK,

    /** The red slit appears with each heartbeat. */
    BEAT,

    /** Full intensity: the slit held still under the swell. */
    SWELL,

    /** The opening frames. */
    OPENING,

    /** The open eye in silence. */
    HOLD,

    /** The protocol's title. */
    LINE,

    /** The steps: one at a time (stage 1) or as a checklist (stage 2). */
    STEP,

    /** Finished: a still moment, the eye filled and closing slowly, then black. */
    CLOSING,

    /** A short closing text on black (left early, or the lock ended first). */
    FAREWELL,

    /** Handed over: nothing more happens here. */
    DONE,
}

/** The intro's timing per tier (intensity). */
private class Timing(val black: Long, val beats: Int, val swell: Long, val hold: Long)

private fun timingFor(tier: Int): Timing = when {
    tier >= 3 -> Timing(black = 1_600, beats = 2, swell = 1_800, hold = 2_500)
    tier == 2 -> Timing(black = 900, beats = 1, swell = 1_400, hold = 1_800)
    else -> Timing(black = 300, beats = 0, swell = 0, hold = 600)
}

/**
 * The takeover of one protocol run, a genjutsu: black, the red slit with the heartbeat, the eye opens, the
 * protocol's title, then its steps. Stage 1 shows one step at a time by its type (a check or done step is held for
 * 1.5 s anywhere above the strip once its lock is over; a timer counts itself down and ends by itself; a text step
 * is typed and saved); stage 2 shows the steps as a plain checklist without timers. The eye fills with red as the run
 * goes on, late and in coarse steps. At the bottom: CALL, APPS, NOT OKAY and LEAVE. Hard cuts, long holds.
 *
 * Intensity: full plays the whole intro with sound, vibration and Glyph lights (the Glyph off after
 * [TakeoverConfig.glyphOffAfterS]); subtle is a quiet screen (a short intro, no sound, no Glyph, no screen effects);
 * full then subtle turns subtle after [TakeoverConfig.fullSeconds]. Both count from [shownSinceMs] (elapsed realtime),
 * when this takeover first appeared. Silence (QUIET) is the activity's business: it builds [fx] silent or mutes it.
 *
 * A promise that came due shows exactly its line with DONE, SNOOZE (once) and ON IT instead of its step; a call card
 * shows who to call big, with CALL (or MESSAGE in its short version), DONE once a call has unlocked it, and NOT NOW;
 * Jummah's I'M AT UNI step also offers NOT YET, and its lock says until when the phone is silent; a reset's last step
 * says what re-entering does. Jummah, the promise and a call's short version have no shorter version: NOT OKAY only
 * says so.
 *
 * [initial] is the run as it was when this takeover appeared; [live] is the same run as the engine has it now while
 * it owns the screen, or null once it does not. [steps] are [live]'s steps (its version on its weekday); [todo] is a
 * line under them (the morning: today's first to-do). The wind-down's GOING TO SLEEP step with Isha still open asks
 * CONFIRM ISHA first ([StepView.ishaToConfirm]); the answer goes to [TakeoverCallbacks.goingToSleep]. A takeover
 * that was already under way (its intro was seen ([introSeen]), its run has moved on, or it first appeared a while
 * ago) starts at the open eye and its current step. When the run stops owning the screen the takeover ends as the
 * engine ended it: the filled eye closes for a finished run; a lock that ran out, a missed window or a run left from
 * elsewhere says so in a line; then [TakeoverCallbacks.finished].
 */
@Composable
internal fun TakeoverScreen(
    initial: Run,
    live: Run?,
    protocol: Protocol?,
    intensity: Intensity,
    steps: List<Step>,
    todo: String?,
    penaltyToday: Int,
    bailOut: BailOutConfig,
    takeover: TakeoverConfig,
    shownSinceMs: Long,
    introSeen: Boolean,
    fx: TakeoverFx,
    callbacks: TakeoverCallbacks,
) {
    val id = initial.id
    val tier = tierFor(intensity)
    val timing = remember(tier) { timingFor(tier) }
    val openFrames = integerResource(R.integer.eye_open_frames)
    val cb by rememberUpdatedState(callbacks)
    val currentLive by rememberUpdatedState(live)
    // The run as last seen while it owned the screen: its ending still shows its title and lines.
    val last = remember { arrayOf(initial) }
    if (live != null) last[0] = live
    val run = live ?: last[0]
    // A call card says who: CALL MOM.
    val title = (protocol?.title ?: initial.protocol) + (initial.label?.takeIf { initial.call != null }?.let { " $it" } ?: "")
    // A call card is answered with its own words (CALL, DONE, NOT NOW), never as a checklist.
    val checklist = run.stage >= 2 && run.call == null
    // A call card's short version: DONE shows once MESSAGE was tapped.
    var messaged by rememberSaveable { mutableStateOf(false) }

    fun sinceShown(): Long = SystemClock.elapsedRealtime() - shownSinceMs

    // Survives the activity being recreated: the intro is not played twice.
    var introPlayed by rememberSaveable { mutableStateOf(false) }
    val resume = remember {
        // A returned prayer takeover is a new takeover of a run that has moved on: it plays its intro.
        val movedOn = initial.phase == RunPhase.ACTIVE && (initial.step > 0 || initial.steppedDown || initial.shortened)
        introPlayed || introSeen || movedOn || sinceShown() > RESUME_AFTER_MS
    }

    var phase by remember { mutableStateOf(if (resume) Phase.STEP else Phase.BLACK) }
    var eyeMode by remember { mutableStateOf(if (resume) EyeMode.OPEN else EyeMode.HIDDEN) }
    var eyeModeAt by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    // When the vignette and the tears started; 0 = off. They show only while the takeover is at full intensity.
    var effectsAt by remember { mutableLongStateOf(if (resume) SystemClock.uptimeMillis() else 0L) }
    var quietScreen by remember {
        mutableStateOf(
            intensity == Intensity.SUBTLE ||
                (intensity == Intensity.FULL_THEN_SUBTLE && sinceShown() >= takeover.fullSeconds * 1000L),
        )
    }
    // The current step as the engine has it, read again every POLL_MS; and the clock the countdowns read.
    var view by remember { mutableStateOf(callbacks.stepView(id)) }
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var stepVisible by remember { mutableStateOf(false) }
    // The first step after a resume appears at once; every other one after a moment of nothing.
    var gapless by remember { mutableStateOf(resume) }
    // When the current hold-to-confirm began; 0 = not held.
    var holdStartedAt by remember { mutableLongStateOf(0L) }
    // True once this screen is closing the takeover (finished, left, or the lock ended).
    var ended by remember { mutableStateOf(false) }
    // The eye's red fill (late and coarse; -1 = not set yet), and full once the run is done.
    var fillLevel by remember { mutableFloatStateOf(-1f) }
    var filledUp by remember { mutableStateOf(false) }
    var eyeTaps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var farewell by remember { mutableStateOf("") }
    var farewellMs by remember { mutableLongStateOf(0L) }
    var note by remember { mutableStateOf<String?>(null) }
    var leaveHeld by remember { mutableStateOf(false) }
    var appsOpen by remember { mutableStateOf(false) }
    // GOING TO SLEEP was held with Isha still open: CONFIRM ISHA shows over the takeover.
    var askIsha by remember { mutableStateOf(false) }

    val clock = rememberTakeoverClock()
    val tear = rememberTear(running = effectsAt != 0L && !quietScreen)

    fun setEye(mode: EyeMode) {
        eyeMode = mode
        eyeModeAt = SystemClock.uptimeMillis()
    }

    fun effectsOff() {
        effectsAt = 0L
    }

    fun closeWith(text: String, holdMs: Long) {
        effectsOff()
        holdStartedAt = 0L
        appsOpen = false
        askIsha = false
        setEye(EyeMode.HIDDEN)
        farewell = text
        farewellMs = holdMs
        phase = Phase.FAREWELL
    }

    /** Ends without words: the eye closes (filled, for a finished run), then black. */
    fun closeQuietly() {
        effectsOff()
        holdStartedAt = 0L
        appsOpen = false
        askIsha = false
        stepVisible = false
        if (eyeMode == EyeMode.HIDDEN) {
            phase = Phase.DONE
            cb.finished()
        } else {
            // The pupil stays at its pinpoint for the still moment before the eye closes.
            phase = Phase.CLOSING
        }
    }

    /** Step [index] is finished: a prayer's done step confirms the prayer, everything else is a step done. */
    fun finishStep(index: Int, type: StepType, text: String?) {
        val r = currentLive ?: return
        if (ended) return
        // GOING TO SLEEP would miss an open Isha: ask first (the answer finishes the step).
        val v = view
        if (v != null && v.index == index && v.goingToSleep && v.ishaToConfirm != null) {
            askIsha = true
            return
        }
        fx.confirmStep()
        if (type == StepType.DONE && r.prayer != null) cb.confirm(r.id) else cb.stepDone(r.id, index, text)
        view = cb.stepView(r.id)
    }

    fun confirmHeld() {
        setEye(EyeMode.OPEN)
        val v = view ?: return
        finishStep(v.index, v.step.type, null)
    }

    /** CONFIRM ISHA was answered: GOING TO SLEEP, with Isha counted when it was prayed. */
    fun sleepAfterIsha(prayed: Boolean) {
        val r = currentLive ?: return
        askIsha = false
        if (ended) return
        fx.confirmStep()
        cb.goingToSleep(prayed)
        view = cb.stepView(r.id)
    }

    fun notOkay() {
        val r = currentLive ?: return
        if (ended) return
        // Jummah and the promise: nothing changes, and the screen says so.
        if (!canStepDown(r, protocol)) {
            note = NO_STEP_DOWN_NOTE
            return
        }
        cb.notOkay(r.id)
        view = cb.stepView(r.id)
    }

    /** NOT YET (I'M AT UNI), ON IT or SNOOZE (a promise): the takeover closes with [text]; the run goes on. */
    fun closeAsking(text: String, act: (String) -> Unit) {
        val r = currentLive ?: return
        if (ended) return
        ended = true
        act(r.id)
        fx.dismissed()
        closeWith(text, FAREWELL_MS)
    }

    /** DONE on a promise that came due: kept (the eye fills and closes as for any finished run). */
    fun promiseDone() {
        val r = currentLive ?: return
        if (ended) return
        fx.confirmStep()
        cb.confirm(r.id)
    }

    fun leave() {
        val r = currentLive ?: return
        if (ended) return
        ended = true
        leaveHeld = false
        val text = leftText(r, protocol, bailOut, penaltyToday, Instant.now())
        cb.bailOut(r.id)
        fx.dismissed()
        closeWith(text, FAREWELL_MS)
    }

    val current = view
    // A reset's re-enter step as last shown: once it is done, its line (what comes next) is the goodbye.
    val reentry = remember { arrayOfNulls<String>(1) }
    current?.reentry?.let { reentry[0] = it }
    val holdable = current != null && current.canTap && current.step.type != StepType.TEXT && current.step.type != StepType.WORKOUT
    // A promise or a call card is answered with its own words (DONE, SNOOZE, ON IT; CALL, DONE, NOT NOW), never by holding.
    val canHold by rememberUpdatedState(
        phase == Phase.STEP && stepVisible && holdable && !checklist && !ended && !appsOpen && !askIsha && live != null &&
            run.promise == null && run.call == null,
    )
    val onConfirm by rememberUpdatedState({ confirmHeld() })

    // The sequence: each phase holds for its time, then cuts to the next.
    LaunchedEffect(phase) {
        when (phase) {
            Phase.BLACK -> {
                delay(timing.black)
                phase = Phase.BEAT
            }
            Phase.BEAT -> {
                if (timing.beats == 0) {
                    setEye(EyeMode.SLIT)
                    fx.tick()
                    delay(TIER1_SLIT_MS)
                } else {
                    for (beat in 1..timing.beats) {
                        setEye(EyeMode.SLIT)
                        fx.heartbeat()
                        if (beat < timing.beats) {
                            // The slit flashes with the beat, then waits for the next one in the dark.
                            delay(SLIT_FLASH_MS)
                            setEye(EyeMode.HIDDEN)
                            delay(BEAT_MS - SLIT_FLASH_MS)
                        } else {
                            delay(BEAT_MS)
                        }
                    }
                }
                phase = if (timing.swell > 0) Phase.SWELL else Phase.OPENING
            }
            Phase.SWELL -> {
                fx.swellStart()
                delay(timing.swell)
                phase = Phase.OPENING
            }
            Phase.OPENING -> {
                // Wait for a frame, so the first opening frame and the sudden silence land together.
                withFrameMillis { }
                setEye(EyeMode.OPENING)
                fx.eyeOpen()
                delay(openFrames * EYE_OPEN_FRAME_MS)
                setEye(EyeMode.OPEN)
                introPlayed = true
                cb.introSeen()
                phase = Phase.HOLD
            }
            Phase.HOLD -> {
                delay(timing.hold)
                fx.droneIn()
                effectsAt = SystemClock.uptimeMillis()
                phase = Phase.LINE
            }
            Phase.LINE -> {
                delay(LINE_MS + LINE_PER_CHAR_MS * title.length)
                phase = Phase.STEP
            }
            Phase.CLOSING -> {
                delay(STILL_MS)
                setEye(EyeMode.CLOSING)
                delay(openFrames * EYE_CLOSE_FRAME_MS)
                setEye(EyeMode.HIDDEN)
                delay(BLACK_AFTER_MS)
                phase = Phase.DONE
                cb.finished()
            }
            Phase.FAREWELL -> {
                delay(farewellMs)
                phase = Phase.DONE
                cb.finished()
            }
            Phase.STEP, Phase.DONE -> Unit
        }
    }

    // Full then subtle: subtle once the full seconds are over (the drone fades, the Glyph and the effects go).
    LaunchedEffect(Unit) {
        if (intensity != Intensity.FULL_THEN_SUBTLE) return@LaunchedEffect
        val left = takeover.fullSeconds * 1000L - sinceShown()
        if (left > 0) delay(left)
        quietScreen = true
        fx.subtle()
    }

    // Full: the Glyph lights go off a while after the takeover first appeared; sound and vibration go on.
    LaunchedEffect(Unit) {
        if (intensity == Intensity.SUBTLE) return@LaunchedEffect
        val left = takeover.glyphOffAfterS * 1000L - sinceShown()
        if (left > 0) delay(left)
        fx.glyphOff()
    }

    // Picked up mid-way: straight to the open eye, with the drone under it while the takeover is still full.
    LaunchedEffect(Unit) {
        if (resume && !quietScreen) fx.droneIn()
    }

    // The engine's view of the current step, read again and again (locks run out with time), and the clock. A timer
    // that ends itself and the lock's end are the engine's to apply; when one is due, the engine is brought up to
    // date (its alarm does the same), and the next step or the end of the run follows from its new state.
    LaunchedEffect(Unit) {
        val ticked = HashSet<Instant>()
        while (true) {
            val old = view
            val v = cb.stepView(id)
            val now = Instant.now()
            // A timer marked chime (a study round or break) ran out and the run moved on: the soft chime. Not when it was
            // left or stepped down early.
            val oldEnd = old?.timerEndsAt
            if (old != null && old.step.type == StepType.TIMER && old.step.chime && oldEnd != null && !now.isBefore(oldEnd) &&
                (v == null || v.index != old.index)
            ) {
                cb.chime(loud = intensity == Intensity.FULL)
            }
            if (v != old) view = v
            if (now.epochSecond != nowSec) nowSec = now.epochSecond
            if (currentLive != null && !ended && v != null) {
                val timerEnd = v.timerEndsAt?.takeIf { v.step.type == StepType.TIMER && !v.canTap }
                val due = listOfNotNull(timerEnd, v.lockEndsAt).firstOrNull { !now.isBefore(it) && it !in ticked }
                if (due != null) {
                    ticked += due
                    cb.tick()
                }
            }
            delay(POLL_MS)
        }
    }

    // The eye fills as the run goes on: late (one coarse step at a time, FILL_LAG_MS apart) and never quite full.
    LaunchedEffect(Unit) {
        while (true) {
            val r = currentLive
            if (r != null) {
                val target = coarse(runProgress(r, view, Instant.now()))
                fillLevel = when {
                    fillLevel < 0f -> (target - FILL_STEP).coerceAtLeast(0f)
                    target > fillLevel + FILL_EPSILON -> minOf(target, fillLevel + FILL_STEP)
                    else -> fillLevel
                }
            }
            delay(FILL_LAG_MS)
        }
    }

    // Each step cuts in after a moment of nothing (a new version starts again at its first step). The checklist
    // has no such gap.
    val stepKey = current?.let { "${run.version.label}:${it.index}" }
    LaunchedEffect(phase, stepKey) {
        if (phase != Phase.STEP) return@LaunchedEffect
        stepVisible = false
        if (gapless || checklist) gapless = false else delay(GAP_MS)
        stepVisible = true
        fx.waiting()
    }

    // A step that was locked turns white with one soft tick the moment it can be held.
    val canTapNow = current?.canTap == true
    val wasLocked = remember(stepKey) { booleanArrayOf(false) }
    LaunchedEffect(stepKey, canTapNow) {
        if (!canTapNow) {
            wasLocked[0] = true
        } else if (wasLocked[0]) {
            wasLocked[0] = false
            if (!checklist) fx.stepReady()
        }
    }

    // "I'M NOT OKAY" took effect: the version line under the step says what runs now; the note says what it cost.
    val ladder = run.version to run.shortened
    val firstLadder = remember { ladder }
    LaunchedEffect(ladder) {
        if (ladder != firstLadder) note = STEP_DOWN_NOTE
    }

    LaunchedEffect(note) {
        if (note != null) {
            delay(NOTE_MS)
            note = null
        }
    }

    // The run no longer owns the screen and this screen did not close it itself (by LEAVE). Still open: its lock ran
    // out first (or it was left from elsewhere). Over: the journal says how (finished by the last step, the
    // confirmation or a timer; missed when its window closed; dropped by the kill switch). Watched with snapshotFlow,
    // so nothing cancels the ending half-way.
    LaunchedEffect(Unit) {
        snapshotFlow { currentLive == null }.first { it }
        if (ended) return@LaunchedEffect
        ended = true
        holdStartedAt = 0L
        appsOpen = false
        askIsha = false
        leaveHeld = false
        if (eyeMode == EyeMode.CONTRACTING) setEye(EyeMode.OPEN)
        val open = cb.run(id)
        if (open != null) {
            fx.stop()
            if (open.phase == RunPhase.OPEN) closeWith(lockOverText(open), FAREWELL_MS) else closeQuietly()
            return@LaunchedEffect
        }
        val journal = cb
        var ending: Entry? = null
        var tries = 0
        while (ending == null && tries < ENDING_TRIES) {
            // The journal line follows the state change by a moment.
            if (tries++ > 0) delay(ENDING_RETRY_MS)
            ending = withContext(Dispatchers.IO) { runCatching { journal.ending(id) }.getOrNull() }
        }
        val text = ending?.let { endText(it) }
        when {
            ending?.type == EntryType.COMPLETED && reentry[0] != null -> {
                fx.completed()
                closeWith(reentry[0] ?: "", FAREWELL_MS)
            }
            ending?.type == EntryType.COMPLETED -> {
                fx.completed()
                filledUp = true
                closeQuietly()
            }
            text != null -> {
                fx.stop()
                closeWith(text, FAREWELL_MS)
            }
            else -> {
                fx.stop()
                closeQuietly()
            }
        }
    }

    BackHandler(enabled = appsOpen || askIsha) {
        appsOpen = false
        askIsha = false
    }

    val holds = (phase == Phase.LINE || phase == Phase.STEP) && !ended && live != null

    Box(
        Modifier
            .fillMaxSize()
            // The negative flash turns the whole screen white for its frames.
            .drawBehind { if (eyeNegative(eyeMode, clock.longValue - eyeModeAt)) drawRect(Palette.White) }
            .genjutsu(
                tear = { tear.value },
                vignette = { if (effectsAt != 0L && !quietScreen) vignetteAlpha(clock.longValue - effectsAt) else 0f },
            ),
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Everything above the safety strip: hold anywhere here to confirm a step.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            if (!canHold) return@awaitEachGesture
                            holdStartedAt = SystemClock.uptimeMillis()
                            setEye(EyeMode.CONTRACTING)
                            var released = false
                            withTimeoutOrNull(CONFIRM_HOLD_MS) {
                                waitForUpOrCancellation()
                                released = true
                            }
                            holdStartedAt = 0L
                            // Released early: the pupil snaps back.
                            if (!released && canHold) onConfirm() else if (eyeMode == EyeMode.CONTRACTING) setEye(EyeMode.OPEN)
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (phase) {
                    Phase.FAREWELL -> Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        TakeoverText(farewell, TakeoverType.Step, Modifier.padding(horizontal = 24.dp))
                    }
                    Phase.DONE -> Unit
                    // The gym's workout: its own panel in place of the eye, so every target is big.
                    else -> if (phase == Phase.STEP && live != null && !ended && current?.workout != null) {
                        val workout = current.workout
                        if (workout != null) {
                            WorkoutPanel(
                                workout = workout,
                                nowSec = { nowSec },
                                onLog = { exercise, reps, weight, rest ->
                                    fx.confirmStep()
                                    cb.logSet(id, exercise, reps, weight, rest)
                                    view = cb.stepView(id)
                                },
                                onFinish = {
                                    fx.confirmStep()
                                    cb.finishWorkout(id)
                                    view = cb.stepView(id)
                                },
                                onRestOver = { cb.chime(loud = intensity == Intensity.FULL) },
                                onTyping = { cb.typing() },
                            )
                        }
                    } else {
                        // A little air above the eye, in proportion to the screen.
                        Spacer(Modifier.fillMaxHeight(EYE_TOP_SHARE))
                        Box(
                            Modifier
                                // Square where there is room; on a short screen (or with the keyboard up) the eye
                                // gives way to the text under it.
                                .weight(1f, fill = false)
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .pointerInput(Unit) {
                                    // Only watches for taps (the kill switch); the hold above still sees them.
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val up = waitForUpOrCancellation()
                                        if (up != null && up.uptimeMillis - down.uptimeMillis < EYE_TAP_MS) {
                                            if (up.uptimeMillis - lastTapAt > EYE_TAP_GAP_MS) eyeTaps = 0
                                            lastTapAt = up.uptimeMillis
                                            eyeTaps++
                                            if (eyeTaps >= KILL_SWITCH_TAPS) {
                                                eyeTaps = 0
                                                cb.killSwitch()
                                            }
                                        }
                                    }
                                },
                        ) {
                            Eye(
                                mode = eyeMode,
                                modeStartedAt = eyeModeAt,
                                tier = tier,
                                clock = clock,
                                modifier = Modifier.fillMaxSize(),
                                contract = {
                                    when {
                                        phase == Phase.CLOSING -> 1f
                                        holdStartedAt == 0L -> 0f
                                        else -> ((clock.longValue - holdStartedAt).toFloat() / CONFIRM_HOLD_MS).coerceIn(0f, 1f)
                                    }
                                },
                                fill = { if (filledUp) 1f else fillLevel.coerceAtLeast(0f) },
                            )
                        }
                        // The text slot under the eye. Its room is kept first, so a long step is never cut off.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = TEXT_SLOT_MIN)
                                .padding(start = 24.dp, end = 24.dp, top = 8.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            when {
                                phase == Phase.LINE -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    TakeoverText(title, TakeoverType.Title)
                                    val below = lineBelowTitle(run)
                                    if (below != null) {
                                        Spacer(Modifier.height(16.dp))
                                        TakeoverText(below, TakeoverType.Small)
                                    }
                                }
                                phase != Phase.STEP || !stepVisible -> Unit
                                else -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    TakeoverText(if (run.test) "$title · TEST" else title, TakeoverType.Small)
                                    // What this block is for (the MY TIMES row, or what was typed at START STUDY), big.
                                    val label = current?.label
                                    if (label != null && run.kind == ProtocolKind.STUDY) {
                                        Spacer(Modifier.height(12.dp))
                                        TakeoverText(label, TakeoverType.Title)
                                    }
                                    Spacer(Modifier.height(20.dp))
                                    val promise = run.promise
                                    val call = current?.call
                                    if (call != null) {
                                        val step = current
                                        CallQuestion(
                                            view = call,
                                            messaged = messaged,
                                            onCall = { cb.call() },
                                            onMessage = {
                                                messaged = true
                                                cb.message()
                                            },
                                            onDone = { finishStep(step.index, step.step.type, null) },
                                            onNotNow = { closeAsking(notNowText(run, protocol)) { cb.dismiss(it) } },
                                        )
                                    } else if (promise != null) {
                                        val snooze = cb.snoozeMinutes(run.id)
                                        PromiseQuestion(
                                            line = promise.text,
                                            snoozeMinutes = snooze,
                                            onDone = { promiseDone() },
                                            onSnooze = { closeAsking("SNOOZED FOR ${snooze ?: 0} MIN.") { cb.snooze(it) } },
                                            onOnIt = { closeAsking("ON IT.\nTHE POOL STAYS LOCKED UNTIL DONE.") { cb.dismiss(it) } },
                                        )
                                    } else if (checklist) {
                                        Checklist(
                                            steps = steps,
                                            current = current?.index ?: run.step,
                                            onTap = { index, type -> finishStep(index, type, null) },
                                            onTyping = { cb.typing() },
                                            onSave = { index, text -> finishStep(index, StepType.TEXT, text) },
                                        )
                                    } else if (current != null) {
                                        SingleStep(
                                            view = current,
                                            nowSec = { nowSec },
                                            onTyping = { cb.typing() },
                                            onSave = { text -> finishStep(current.index, StepType.TEXT, text) },
                                        )
                                        // I'M AT UNI is asked again later: NOT YET closes it until then.
                                        if (current.arrival && run.asking) {
                                            Spacer(Modifier.height(12.dp))
                                            val repeat = protocol?.intParam("arrive_repeat_min") ?: 10
                                            QuestionChoice("NOT YET", TakeoverType.Dim) {
                                                closeAsking("NOT YET.\nIT ASKS AGAIN IN $repeat MIN.") { cb.dismiss(it) }
                                            }
                                        }
                                    }
                                    // The Jummah lock: until when the phone is silent.
                                    val silentUntil = current?.lockEndsAt?.takeIf { run.jummah?.part == JummahPart.LOCK }
                                    if (silentUntil != null) {
                                        Spacer(Modifier.height(20.dp))
                                        TakeoverText(
                                            "SILENT UNTIL ${WallClock.hhmm(silentUntil, ZoneId.systemDefault())} · ${countdown(secondsUntil(silentUntil, nowSec))}",
                                            TakeoverType.Small,
                                        )
                                    }
                                    val version = versionLine(run, protocol)
                                    if (version != null) {
                                        Spacer(Modifier.height(20.dp))
                                        TakeoverText(version, TakeoverType.Small)
                                    }
                                    if (todo != null) {
                                        Spacer(Modifier.height(12.dp))
                                        TakeoverText(todo, TakeoverType.Small)
                                    }
                                    // A meal's short or minimal version: the simple meals of its fallback list.
                                    val fallback = current?.fallback.orEmpty()
                                    if (fallback.isNotEmpty()) {
                                        Spacer(Modifier.height(12.dp))
                                        TakeoverText("SIMPLE MEALS: " + fallback.joinToString(" · "), TakeoverType.Small)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // While LEAVE is held: what leaving costs. Otherwise a short note (the version NOT OKAY switched to).
            val honest = if (leaveHeld && holds) leaveLine(run, protocol, bailOut, penaltyToday, Instant.now()) else null
            val line = honest ?: note
            if (line != null) {
                TakeoverText(
                    line,
                    TakeoverType.Strip,
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    color = if (honest != null) Palette.White else TakeoverType.Dim,
                )
            }
            SafetyStrip(
                holds = holds,
                stepDown = canStepDown(run, protocol) || saysNoStepDown(run),
                apps = !ended,
                // The I'M AT UNI ask and a call card lock nothing: NOT YET and NOT NOW close them.
                leave = !(run.jummah != null && run.asking) && run.call == null,
                onCall = { cb.call() },
                onApps = { appsOpen = true },
                onNotOkay = { notOkay() },
                onLeave = { leave() },
                onLeaveHeld = { leaveHeld = it },
            )
        }

        if (askIsha) {
            IshaQuestion(
                onPrayed = { sleepAfterIsha(prayed = true) },
                onNotYet = { askIsha = false },
                onSleepAnyway = { sleepAfterIsha(prayed = false) },
            )
        }

        if (appsOpen) {
            AppsSheet(
                load = { cb.apps() },
                onOpen = { app ->
                    appsOpen = false
                    cb.openApp(app)
                },
                onClose = { appsOpen = false },
            )
        }
    }
}

/**
 * A promise that came due: exactly the line the user typed (as typed), then DONE, SNOOZE [snoozeMinutes] MIN (only
 * when offered) and ON IT.
 */
@Composable
private fun PromiseQuestion(line: String, snoozeMinutes: Int?, onDone: () -> Unit, onSnooze: () -> Unit, onOnIt: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(line, style = TakeoverType.Title)
        Spacer(Modifier.height(32.dp))
        QuestionChoice("DONE", Palette.White, onDone)
        if (snoozeMinutes != null) QuestionChoice("SNOOZE $snoozeMinutes MIN", TakeoverType.Dim, onSnooze)
        QuestionChoice("ON IT", TakeoverType.Dim, onOnIt)
    }
}

/**
 * A call card: who to call, big; until when; CALL (the dialer; no contact is stored) or, in the short version, MESSAGE;
 * DONE once a call of the minimum length has unlocked it (in the short version once MESSAGE was tapped); NOT NOW.
 */
@Composable
private fun CallQuestion(
    view: CallView,
    messaged: Boolean,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onDone: () -> Unit,
    onNotNow: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TakeoverText(view.who, TakeoverType.Timer)
        Spacer(Modifier.height(8.dp))
        TakeoverText("BY ${WallClock.hhmm(view.dueAt, ZoneId.systemDefault())}", TakeoverType.Small)
        Spacer(Modifier.height(24.dp))
        if (view.message) QuestionChoice("MESSAGE", Palette.White, onMessage) else QuestionChoice("CALL", Palette.White, onCall)
        if (view.done && (!view.message || messaged)) QuestionChoice("DONE", Palette.White, onDone)
        QuestionChoice("NOT NOW", TakeoverType.Dim, onNotNow)
        Spacer(Modifier.height(12.dp))
        val hint = when {
            view.message && !messaged -> "SEND A MESSAGE INSTEAD. DONE COMES AFTER MESSAGE."
            view.message -> "SENT IT? TAP DONE."
            view.done -> "CALLED. TAP DONE."
            view.longestCallS > 0 -> "LONGEST CALL ${(view.longestCallS + 59) / 60} MIN. DONE COMES AFTER ${view.minCallMin} MIN."
            else -> "DONE COMES AFTER A CALL OF ${view.minCallMin} MIN."
        }
        TakeoverText(hint, TakeoverType.Small)
        if (view.returned) TakeoverText("IT CAME BACK: NOTHING DONE YET.", TakeoverType.Small)
    }
}

/** What NOT NOW on a call card says: when it comes back (once), or that its notification keeps it until the deadline. */
private fun notNowText(run: Run, protocol: Protocol?): String {
    val c = run.call ?: return "NOT NOW."
    val zone = ZoneId.systemDefault()
    val back = c.cardAt?.plus(Duration.ofMinutes((protocol?.intParam("return_after_min") ?: 30).toLong()))
        ?.takeIf { run.stage == 1 && !run.returned && !run.confirmed && it.isAfter(Instant.now()) }
    return if (back != null) "NOT NOW.\nIT COMES BACK ONCE AT ${WallClock.hhmm(back, zone)}." else
        "NOT NOW.\nIT STAYS ON ITS NOTIFICATION UNTIL ${WallClock.hhmm(c.dueAt, zone)}."
}

/**
 * CONFIRM ISHA over the takeover, at GOING TO SLEEP with Isha still open: I PRAYED ISHA (it counts, and the day ends),
 * NOT YET (back to the step: pray first), or SLEEP WITHOUT IT (Isha is missed, and the day ends).
 */
@Composable
private fun IshaQuestion(onPrayed: () -> Unit, onNotYet: () -> Unit, onSleepAnyway: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Black)
            // Takes every touch, so nothing under it reacts.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TakeoverText("ISHA IS NOT CONFIRMED", TakeoverType.Title)
        Spacer(Modifier.height(12.dp))
        TakeoverText("DID YOU PRAY IT?", TakeoverType.Small)
        Spacer(Modifier.height(40.dp))
        QuestionChoice("I PRAYED ISHA", Palette.White, onPrayed)
        QuestionChoice("NOT YET", TakeoverType.Dim, onNotYet)
        QuestionChoice("SLEEP WITHOUT IT", TakeoverType.Faint, onSleepAnyway)
    }
}

@Composable
private fun QuestionChoice(label: String, color: Color, onTap: () -> Unit) {
    TakeoverText(
        label,
        TakeoverType.Choice,
        Modifier
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .padding(vertical = 16.dp),
        color = color,
    )
}

/** The small line under the title, when there is something to say before the steps. */
private fun lineBelowTitle(run: Run): String? = when {
    run.test -> "TEST RUN: NOTHING COUNTS"
    run.jummah?.part == JummahPart.LOCK -> "THE PHONE IS SILENT"
    run.jummah?.part == JummahPart.TRAVEL -> "ARE YOU AT UNI?"
    run.promise != null -> "YOU SAID YOU WOULD"
    run.call != null -> "TIME TO CALL"
    run.phase == RunPhase.RETURNED -> run.windowEndsAt
        ?.let { "NOT CONFIRMED. THE WINDOW CLOSES AT ${WallClock.hhmm(it, ZoneId.systemDefault())}" }
        ?: "NOT CONFIRMED YET"
    run.stage >= 2 -> "GUIDED"
    else -> null
}

private const val BEAT_MS = 900L
private const val SLIT_FLASH_MS = 350L
private const val TIER1_SLIT_MS = 700L
private const val LINE_MS = 2_400L
private const val LINE_PER_CHAR_MS = 40L
private const val GAP_MS = 400L
private const val POLL_MS = 250L
private const val CONFIRM_HOLD_MS = 1_500L
private const val STILL_MS = 600L
private const val BLACK_AFTER_MS = 1_200L
private const val FAREWELL_MS = 3_200L
private const val NOTE_MS = 6_000L
private const val EYE_TAP_MS = 400L
private const val EYE_TAP_GAP_MS = 1_500L
private const val KILL_SWITCH_TAPS = 7
private val TEXT_SLOT_MIN = 200.dp
private const val EYE_TOP_SHARE = 0.07f

/** A takeover first shown longer ago than this starts at its current step, without the intro. */
private const val RESUME_AFTER_MS = 90_000L

/** How often, and how far apart, the journal is read for how a run ended. */
private const val ENDING_TRIES = 4
private const val ENDING_RETRY_MS = 250L

/** One coarse step of the eye's fill, and how long it waits before the next one. */
private const val FILL_STEP = 1f / FILL_STEPS
private const val FILL_LAG_MS = 15_000L
private const val FILL_EPSILON = 0.001f
