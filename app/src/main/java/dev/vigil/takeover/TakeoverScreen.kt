package dev.vigil.takeover

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.unit.dp
import dev.vigil.R
import dev.vigil.core.engine.Engine
import dev.vigil.core.engine.Ticket
import dev.vigil.core.plan.CheckInAnswer
import dev.vigil.core.plan.Directive
import dev.vigil.core.plan.Rating
import dev.vigil.ui.theme.Palette
import kotlinx.coroutines.delay

/** How a step tap was received by the engine. */
enum class StepResult { NEXT, COMPLETED, IGNORED }

/** What the user did with "I'm not okay today" while this takeover was showing. */
enum class BadDayResult {
    BYPASSED,
    KEPT,

    /** A TEST preview: nothing was switched on. */
    PREVIEW,
}

/** What the takeover screen reports. Each call goes straight to the engine; the screen decides nothing. */
interface TakeoverCallbacks {
    /** The current step appeared on screen: its min_seconds lock starts now. */
    fun stepShown(takeoverId: String)

    /** The current step was held to the end. */
    fun stepDone(takeoverId: String, index: Int): StepResult

    /** LEAVE was held to the end. */
    fun dismiss(takeoverId: String)

    /** NOT OKAY was held to the end. */
    fun notOkay(takeoverId: String): BadDayResult

    /** CALL was tapped: the takeover steps aside and the dialer opens. Never blocked. */
    fun call()

    /** The hidden developer kill switch (seven taps on the eye). */
    fun suspend(minutes: Long)

    /** True when the engine wants EASY / OK / HARD for this just-completed takeover. */
    fun pendingRatingFor(takeoverId: String): Boolean

    /** The difficulty report after a completion; null = skipped. */
    fun rate(takeoverId: String, rating: Rating?)

    /** The morning check-in was answered. */
    fun answerCheckIn(takeoverId: String, answer: CheckInAnswer)

    /** The takeover has left the screen; show the next one or close. */
    fun finished()
}

/** Where the takeover is in its sequence. */
private enum class Phase {
    /** Total silence before anything shows. */
    BLACK,

    /** The red slit appears with each heartbeat. */
    BEAT,

    /** Tiers 2-3: the slit held still under the swell. */
    SWELL,

    /** The opening frames. */
    OPENING,

    /** The open eye in silence. */
    HOLD,

    /** The directive line (title and why), or the check-in's "YESTERDAY WAS HARD.". */
    LINE,

    /** The current step, or the check-in's question. */
    STEP,

    /** Completed or answered: a still moment, the eye closes slowly, then black. */
    CLOSING,

    /** EASY / OK / HARD on black. */
    RATING,

    /** A short closing text on black (left, or a lighter day). */
    FAREWELL,

    /** Handed over: nothing more happens here. */
    DONE,
}

/** The intro's timing per tier. */
private class Timing(val black: Long, val beats: Int, val swell: Long, val hold: Long)

private fun timingFor(tier: Int): Timing = when {
    tier >= 3 -> Timing(black = 1_600, beats = 2, swell = 1_800, hold = 2_500)
    tier == 2 -> Timing(black = 900, beats = 1, swell = 1_400, hold = 1_800)
    else -> Timing(black = 300, beats = 0, swell = 0, hold = 600)
}

/**
 * One takeover, a genjutsu: one element at a time. Black, the red slit with the heartbeat, the eye opens, then
 * below it one text slot shows the directive line and then, replacing it, the current step. A step is confirmed
 * by holding anywhere above the safety strip; the pupil contracts while it is held. Hard cuts, long holds, no
 * checkboxes, progress bars or counters.
 *
 * [initial] is the ticket as it was when the takeover appeared; [live] is the same ticket as the engine
 * currently has it (steps done, lock start), or null once the engine has closed it. A takeover that was already
 * under way (the activity was recreated, or it comes back after being left) starts at the open eye and its
 * current step.
 */
@Composable
fun TakeoverScreen(
    initial: Ticket,
    live: Ticket?,
    directive: Directive,
    fx: TakeoverFx,
    callbacks: TakeoverCallbacks,
) {
    val id = initial.takeoverId
    val checkIn = directive.id == Engine.CHECK_IN_ID
    val timing = remember(directive.tier) { timingFor(directive.tier) }
    val openFrames = integerResource(R.integer.eye_open_frames)
    val cb by rememberUpdatedState(callbacks)
    val currentLive by rememberUpdatedState(live)
    val currentDirective by rememberUpdatedState(directive)

    // Survives the activity being recreated: the intro is not played twice.
    var introSeen by rememberSaveable { mutableStateOf(false) }
    val resume = remember { introSeen || initial.stepsDone > 0 || initial.stepShownAtMs != null }

    var phase by remember { mutableStateOf(if (resume) Phase.STEP else Phase.BLACK) }
    var eyeMode by remember { mutableStateOf(if (resume) EyeMode.OPEN else EyeMode.HIDDEN) }
    var eyeModeAt by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    // When the vignette and the tears started; 0 = off.
    var effectsAt by remember { mutableLongStateOf(if (resume) SystemClock.uptimeMillis() else 0L) }
    var stepIndex by remember { mutableIntStateOf(initial.stepsDone) }
    var stepVisible by remember { mutableStateOf(false) }
    var stepReady by remember { mutableStateOf(false) }
    // The first step after a resume appears at once; every other one after a moment of nothing.
    var gapless by remember { mutableStateOf(resume) }
    // When the current hold-to-confirm began; 0 = not held.
    var holdStartedAt by remember { mutableLongStateOf(0L) }
    // True once this screen is closing the takeover itself (completed, answered, left, lighter day).
    var ended by remember { mutableStateOf(false) }
    var eyeTaps by remember { mutableIntStateOf(0) }
    var farewell by remember { mutableStateOf("") }
    var farewellMs by remember { mutableLongStateOf(0L) }
    var note by remember { mutableStateOf<String?>(null) }

    val clock = rememberTakeoverClock()
    val tear = rememberTear(running = effectsAt != 0L)

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
        setEye(EyeMode.HIDDEN)
        farewell = text
        farewellMs = holdMs
        phase = Phase.FAREWELL
    }

    fun confirmStep() {
        val index = stepIndex
        fx.confirmStep()
        when (cb.stepDone(id, index)) {
            StepResult.NEXT -> {
                setEye(EyeMode.OPEN)
                stepVisible = false
                stepReady = false
                stepIndex = index + 1
            }
            StepResult.COMPLETED -> {
                ended = true
                fx.completed()
                effectsOff()
                stepVisible = false
                // The pupil stays at its pinpoint for the still moment before the eye closes.
                phase = Phase.CLOSING
            }
            // The engine did not take it (its lock had not quite run out there): let go and hold again.
            StepResult.IGNORED -> setEye(EyeMode.OPEN)
        }
    }

    fun answer(answer: CheckInAnswer) {
        if (ended || phase != Phase.STEP) return
        ended = true
        cb.answerCheckIn(id, answer)
        fx.completed()
        effectsOff()
        stepVisible = false
        phase = Phase.CLOSING
    }

    fun rate(rating: Rating?) {
        if (phase != Phase.RATING) return
        phase = Phase.DONE
        // The question may have run out (30 min) while the screen was away; then there is nothing to record.
        if (cb.pendingRatingFor(id)) cb.rate(id, rating)
        cb.finished()
    }

    fun leave() {
        if (ended) return
        ended = true
        cb.dismiss(id)
        fx.dismissed()
        closeWith(if (checkIn) "NOT ANSWERED. LOGGED." else "ABANDONED. LOGGED.", FAREWELL_LEFT_MS)
    }

    fun notOkay() {
        if (ended) return
        ended = true
        when (cb.notOkay(id)) {
            BadDayResult.BYPASSED -> {
                fx.stop()
                closeWith("OKAY. TODAY IS LIGHTER.\nTHIS IS NOT A FAILURE.", FAREWELL_LIGHTER_MS)
            }
            BadDayResult.KEPT -> {
                ended = false
                note = "THIS ONE IS ON YOUR ESSENTIALS LIST. IT STAYS."
            }
            BadDayResult.PREVIEW -> {
                ended = false
                note = "PREVIEW: NOTHING CHANGED."
            }
        }
    }

    val canHold by rememberUpdatedState(phase == Phase.STEP && stepVisible && stepReady && !checkIn && !ended)
    val onConfirm by rememberUpdatedState({ confirmStep() })

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
                introSeen = true
                phase = Phase.HOLD
            }
            Phase.HOLD -> {
                delay(timing.hold)
                fx.droneIn()
                effectsAt = SystemClock.uptimeMillis()
                phase = Phase.LINE
            }
            Phase.LINE -> {
                val chars = directive.title.length + directive.why.length
                delay(if (checkIn) CHECK_IN_LINE_MS else LINE_MS + LINE_PER_CHAR_MS * chars)
                phase = Phase.STEP
            }
            Phase.CLOSING -> {
                delay(STILL_MS)
                setEye(EyeMode.CLOSING)
                delay(openFrames * EYE_CLOSE_FRAME_MS)
                setEye(EyeMode.HIDDEN)
                delay(BLACK_AFTER_MS)
                if (!checkIn && cb.pendingRatingFor(id)) {
                    phase = Phase.RATING
                } else {
                    phase = Phase.DONE
                    cb.finished()
                }
            }
            Phase.RATING -> {
                // Asked while the screen was away and since expired: never show a question that no longer counts.
                if (!cb.pendingRatingFor(id)) {
                    phase = Phase.DONE
                    cb.finished()
                    return@LaunchedEffect
                }
                delay(RATING_TIMEOUT_MS)
                rate(null)
            }
            Phase.FAREWELL -> {
                delay(farewellMs)
                phase = Phase.DONE
                cb.finished()
            }
            Phase.STEP, Phase.DONE -> Unit
        }
    }

    // Picked up mid-way: straight to the open eye with the drone under it.
    LaunchedEffect(Unit) {
        if (resume) fx.droneIn()
    }

    // Each step (and the check-in's question) cuts in after a moment of nothing; its lock starts when it shows.
    LaunchedEffect(phase, stepIndex) {
        if (phase != Phase.STEP) return@LaunchedEffect
        stepVisible = false
        stepReady = false
        if (gapless) gapless = false else delay(GAP_MS)
        stepVisible = true
        if (currentLive != null) cb.stepShown(id)
        fx.waiting()
    }

    // While the step's lock runs its text is dim and holding does nothing; then it turns white with one tick.
    LaunchedEffect(phase, stepIndex, stepVisible) {
        if (phase != Phase.STEP || !stepVisible || checkIn) return@LaunchedEffect
        val index = stepIndex
        var wasLocked = false
        while (true) {
            val ticket = currentLive
            // Null until the engine's ticket shows this step (the screen can be a frame ahead of it).
            val left = if (ticket != null && ticket.stepsDone == index && ticket.stepShownAtMs != null) {
                Engine.lockRemainingSeconds(ticket, currentDirective, System.currentTimeMillis())
            } else {
                null
            }
            if (left != null && left > 0) wasLocked = true
            if (left == 0L) {
                stepReady = true
                if (wasLocked) fx.stepReady()
                break
            }
            delay(LOCK_POLL_MS)
        }
    }

    // The engine closed this takeover for another reason (timed out, kill switch, quiet mode, a bad day from elsewhere).
    LaunchedEffect(live == null) {
        if (live == null && !ended) {
            ended = true
            fx.stop()
            phase = Phase.DONE
            cb.finished()
        }
    }

    LaunchedEffect(note) {
        if (note != null) {
            delay(NOTE_MS)
            note = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // The negative flash turns the whole screen white for its frames.
            .drawBehind { if (eyeNegative(eyeMode, clock.longValue - eyeModeAt)) drawRect(Palette.White) }
            .genjutsu(
                tear = { tear.value },
                vignette = { if (effectsAt != 0L) vignetteAlpha(clock.longValue - effectsAt) else 0f },
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
                    Phase.RATING -> Centered {
                        Choices(listOf("EASY" to Rating.EASY, "OK" to Rating.OK, "HARD" to Rating.HARD)) { rate(it) }
                        Spacer(Modifier.height(48.dp))
                        TakeoverText(
                            "SKIP",
                            TakeoverType.Strip,
                            Modifier
                                .clickable(remember { MutableInteractionSource() }, indication = null) { rate(null) }
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                        )
                    }
                    Phase.FAREWELL -> Centered {
                        TakeoverText(farewell, TakeoverType.Step, Modifier.padding(horizontal = 24.dp))
                    }
                    Phase.DONE -> Unit
                    else -> {
                        // A little air above the eye, in proportion to the screen.
                        Spacer(Modifier.fillMaxHeight(EYE_TOP_SHARE))
                        Box(
                            Modifier
                                // Square where there is room; on a short screen the eye gives way to the text under it.
                                .weight(1f, fill = false)
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .pointerInput(Unit) {
                                    // Only watches for taps (the kill switch); the hold above still sees them.
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val up = waitForUpOrCancellation()
                                        if (up != null && up.uptimeMillis - down.uptimeMillis < EYE_TAP_MS) eyeTaps++
                                    }
                                },
                        ) {
                            Eye(
                                mode = eyeMode,
                                modeStartedAt = eyeModeAt,
                                tier = directive.tier,
                                clock = clock,
                                modifier = Modifier.fillMaxSize(),
                                contract = {
                                    when {
                                        phase == Phase.CLOSING -> 1f
                                        holdStartedAt == 0L -> 0f
                                        else -> ((clock.longValue - holdStartedAt).toFloat() / CONFIRM_HOLD_MS).coerceIn(0f, 1f)
                                    }
                                },
                            )
                        }
                        // The one text slot under the eye. Its room is kept first, so a long step is never cut off.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = TEXT_SLOT_MIN)
                                .padding(start = 24.dp, end = 24.dp, top = 8.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Slot(
                                phase = phase,
                                checkIn = checkIn,
                                directive = directive,
                                stepIndex = stepIndex,
                                stepVisible = stepVisible,
                                stepReady = stepReady,
                                onAnswer = { answer(it) },
                            )
                        }
                    }
                }
            }

            if (eyeTaps >= KILL_SWITCH_TAPS) {
                // The developer kill switch, reachable even while a takeover holds the screen.
                TakeoverText(
                    "SUSPEND 1 HOUR",
                    TakeoverType.Strip,
                    Modifier
                        .fillMaxWidth()
                        .clickable(remember { MutableInteractionSource() }, indication = null) { cb.suspend(60) }
                        .padding(vertical = 12.dp),
                    color = Palette.White,
                )
            }
            note?.let {
                TakeoverText(it, TakeoverType.Strip, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), color = TakeoverType.Dim)
            }
            SafetyStrip(
                holds = (phase == Phase.LINE || phase == Phase.STEP) && !ended,
                onCall = { cb.call() },
                onNotOkay = { notOkay() },
                onLeave = { leave() },
            )
        }
    }
}

/** What the one text slot under the eye shows: nothing, the directive line, or the current step. */
@Composable
private fun Slot(
    phase: Phase,
    checkIn: Boolean,
    directive: Directive,
    stepIndex: Int,
    stepVisible: Boolean,
    stepReady: Boolean,
    onAnswer: (CheckInAnswer) -> Unit,
) {
    when {
        phase == Phase.LINE && checkIn -> TakeoverText(CHECK_IN_LINE, TakeoverType.Title)
        phase == Phase.LINE -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TakeoverText(directive.title, TakeoverType.Title)
            if (directive.why.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                TakeoverText(directive.why, TakeoverType.Small)
            }
        }
        phase != Phase.STEP || !stepVisible -> Unit
        checkIn -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TakeoverText(directive.steps.firstOrNull()?.text ?: CHECK_IN_QUESTION, TakeoverType.Title)
            Spacer(Modifier.height(36.dp))
            Choices(listOf("BETTER" to CheckInAnswer.BETTER, "SAME" to CheckInAnswer.SAME, "WORSE" to CheckInAnswer.WORSE), onAnswer)
        }
        else -> {
            val text = directive.steps.getOrNull(stepIndex)?.text ?: return
            TakeoverText(text, TakeoverType.Step, color = if (stepReady) Palette.White else TakeoverType.Dim)
        }
    }
}

/** A row of one-tap words. */
@Composable
private fun <T> Choices(choices: List<Pair<String, T>>, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        for ((label, value) in choices) {
            TakeoverText(
                label,
                TakeoverType.Choice,
                Modifier
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onPick(value) }
                    .padding(horizontal = 10.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

/**
 * Always at the bottom, faint and small: CALL (a tap, always), NOT OKAY (3 s hold) and LEAVE (10 s hold), the
 * last two only when [holds]. A held word turns into a number counting down.
 */
@Composable
private fun SafetyStrip(holds: Boolean, onCall: () -> Unit, onNotOkay: () -> Unit, onLeave: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            TakeoverText(
                "CALL",
                TakeoverType.Strip,
                Modifier
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onCall)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (holds) HoldWord("NOT OKAY", NOT_OKAY_HOLD_MS, onNotOkay)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (holds) HoldWord("LEAVE", LEAVE_HOLD_MS, onLeave)
        }
    }
}

/** A word that must be held for [holdMs]; while held it shows only the seconds left. Releasing early resets it. */
@Composable
private fun HoldWord(label: String, holdMs: Long, onComplete: () -> Unit) {
    val complete by rememberUpdatedState(onComplete)
    var heldSince by remember { mutableLongStateOf(0L) }
    var secondsLeft by remember { mutableIntStateOf(0) }

    LaunchedEffect(heldSince) {
        // This hold's start, read once: after an early release the state is already 0 before this loop is cancelled.
        val since = heldSince
        if (since == 0L) return@LaunchedEffect
        while (heldSince == since) {
            val remaining = holdMs - (SystemClock.uptimeMillis() - since)
            if (remaining <= 0) {
                heldSince = 0L
                complete()
                break
            }
            secondsLeft = ((remaining + 999) / 1000).toInt()
            delay(HOLD_POLL_MS)
        }
    }

    val held = heldSince != 0L
    // The label always takes its space (invisible while held), so the target never shrinks under the finger
    // when the countdown replaces it; a press that slid off a shrinking target would cancel itself.
    Box(
        Modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    secondsLeft = ((holdMs + 999) / 1000).toInt()
                    heldSince = SystemClock.uptimeMillis()
                    waitForUpOrCancellation()
                    heldSince = 0L
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        TakeoverText(label, TakeoverType.Strip, color = if (held) Color.Transparent else TakeoverType.Faint)
        if (held) TakeoverText(secondsLeft.toString(), TakeoverType.Strip, color = Palette.White)
    }
}

private const val CHECK_IN_LINE = "YESTERDAY WAS HARD."
private const val CHECK_IN_QUESTION = "HOW IS TODAY?"

private const val BEAT_MS = 900L
private const val SLIT_FLASH_MS = 350L
private const val TIER1_SLIT_MS = 700L
private const val LINE_MS = 3_000L
private const val LINE_PER_CHAR_MS = 30L
private const val CHECK_IN_LINE_MS = 2_000L
private const val GAP_MS = 400L
private const val LOCK_POLL_MS = 100L
private const val CONFIRM_HOLD_MS = 1_500L
private const val STILL_MS = 600L
private const val BLACK_AFTER_MS = 1_200L
private const val RATING_TIMEOUT_MS = 30_000L
private const val FAREWELL_LEFT_MS = 2_600L
private const val FAREWELL_LIGHTER_MS = 4_500L
private const val NOTE_MS = 6_000L
private const val NOT_OKAY_HOLD_MS = 3_000L
private const val LEAVE_HOLD_MS = 10_000L
private const val HOLD_POLL_MS = 50L
private const val EYE_TAP_MS = 400L
private const val KILL_SWITCH_TAPS = 7
private val TEXT_SLOT_MIN = 200.dp
private const val EYE_TOP_SHARE = 0.07f
