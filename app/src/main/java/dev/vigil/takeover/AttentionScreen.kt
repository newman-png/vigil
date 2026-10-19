package dev.vigil.takeover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.vigil.core.journal.Entry
import dev.vigil.core.protocol.BailOutConfig
import dev.vigil.core.protocol.Protocol
import dev.vigil.core.protocol.StepType
import dev.vigil.core.run.Run
import dev.vigil.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * The attention reset: a very plain takeover. One heartbeat as it appears (full intensity; nothing under QUIET), then
 * only the step: face a wall, phone face-down (a tap on the screen each), then LOOK AT ONE SPOT and the countdown, with
 * nothing else to touch. The look ends by itself with a soft chime ([TakeoverCallbacks.chime]; QUIET silences it).
 * CALL, APPS, NOT OKAY (the 3-minute version; after it, it says there is no shorter one) and LEAVE stay at the bottom;
 * seven taps on the small eye open the kill switch. [live] is the run while it owns the screen; once it does not, a
 * short line says how it ended and the screen hands over ([TakeoverCallbacks.finished]).
 */
@Composable
internal fun AttentionScreen(
    initial: Run,
    live: Run?,
    protocol: Protocol?,
    penaltyToday: Int,
    bailOut: BailOutConfig,
    fx: TakeoverFx,
    callbacks: TakeoverCallbacks,
) {
    val id = initial.id
    val cb by rememberUpdatedState(callbacks)
    val currentLive by rememberUpdatedState(live)
    val last = remember { arrayOf(initial) }
    if (live != null) last[0] = live
    val run = live ?: last[0]
    var view by remember { mutableStateOf(callbacks.stepView(id)) }
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var farewell by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var leaveHeld by remember { mutableStateOf(false) }
    var appsOpen by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var eyeTaps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        // A moment for the sound to load, then one heartbeat.
        delay(START_MS)
        fx.heartbeat()
        cb.introSeen()
    }

    // The engine's step, read again and again; a timer that ran out brings the engine up to date. The look's chime
    // sounds when it has run out and the screen moved on (not when it was left early).
    LaunchedEffect(Unit) {
        val ticked = HashSet<Instant>()
        while (true) {
            val old = view
            val v = cb.stepView(id)
            val now = Instant.now()
            val oldEnd = old?.timerEndsAt
            if (old != null && old.step.type == StepType.TIMER && old.step.chime && oldEnd != null && !now.isBefore(oldEnd) &&
                (v == null || v.index != old.index)
            ) {
                cb.chime(loud = true)
            }
            if (v != old) view = v
            if (now.epochSecond != nowSec) nowSec = now.epochSecond
            val timerEnd = v?.timerEndsAt?.takeIf { v.step.type == StepType.TIMER && !v.canTap }
            if (currentLive != null && !leaving && timerEnd != null && !now.isBefore(timerEnd) && timerEnd !in ticked) {
                ticked += timerEnd
                cb.tick()
            }
            delay(POLL_MS)
        }
    }

    // The run no longer owns the screen: say how it ended (done, or left), then hand over.
    LaunchedEffect(Unit) {
        snapshotFlow { currentLive == null }.first { it }
        if (farewell == null) {
            var ending: Entry? = null
            var tries = 0
            while (ending == null && tries++ < ENDING_TRIES) {
                if (tries > 1) delay(ENDING_RETRY_MS)
                ending = withContext(Dispatchers.IO) { runCatching { cb.ending(id) }.getOrNull() }
            }
            farewell = ending?.let { endText(it) } ?: cb.run(id)?.let { lockOverText(it) } ?: "DONE."
        }
        delay(FAREWELL_MS)
        cb.finished()
    }

    LaunchedEffect(note) {
        if (note != null) {
            delay(NOTE_MS)
            note = null
        }
    }

    BackHandler(enabled = appsOpen) { appsOpen = false }

    val current = view
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // A tap anywhere here finishes a check step (or ends the look where the stage allows it).
                    .clickable(remember { MutableInteractionSource() }, indication = null, enabled = current?.canTap == true && farewell == null && live != null) {
                        val v = view ?: return@clickable
                        fx.confirmStep()
                        cb.stepDone(id, v.index, null)
                        view = cb.stepView(id)
                    }
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val left = farewell
                if (left != null || current == null) {
                    TakeoverText(left ?: "", TakeoverType.Step)
                } else {
                    // The small eye: seven taps open the kill switch's 5-minute wait.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(ATTENTION_EYE_HEIGHT)
                            .alpha(ATTENTION_EYE_ALPHA)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val up = waitForUpOrCancellation()
                                    if (up != null && up.uptimeMillis - down.uptimeMillis < TAP_MS) {
                                        if (up.uptimeMillis - lastTapAt > TAP_GAP_MS) eyeTaps = 0
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
                            mode = EyeMode.OPEN,
                            modeStartedAt = 0L,
                            tier = 3,
                            clock = remember { mutableLongStateOf(0L) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(40.dp))
                    val ends = current.timerEndsAt
                    if (current.step.type == StepType.TIMER && ends != null) {
                        TakeoverText("LOOK AT ONE SPOT", TakeoverType.Title)
                        Spacer(Modifier.height(32.dp))
                        TakeoverText(countdown(secondsUntil(ends, nowSec)), TakeoverType.BigTimer)
                        if (current.canTap) {
                            Spacer(Modifier.height(24.dp))
                            TakeoverText("TAP TO END", TakeoverType.Strip)
                        }
                    } else {
                        TakeoverText(current.step.text, TakeoverType.Step)
                        Spacer(Modifier.height(32.dp))
                        TakeoverText(if (current.canTap) "TAP WHEN READY" else "", TakeoverType.Strip)
                    }
                    versionLine(run, protocol)?.let {
                        Spacer(Modifier.height(24.dp))
                        TakeoverText(it, TakeoverType.Small)
                    }
                }
            }
            val honest = if (leaveHeld && live != null && farewell == null) leaveLine(run, protocol, bailOut, penaltyToday, Instant.now()) else null
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
                holds = live != null && farewell == null,
                stepDown = true,
                apps = farewell == null,
                onCall = { cb.call() },
                onApps = { appsOpen = true },
                onNotOkay = {
                    val r = currentLive
                    if (r != null && farewell == null) {
                        if (canStepDown(r, protocol)) {
                            cb.notOkay(r.id)
                            view = cb.stepView(r.id)
                            note = STEP_DOWN_NOTE
                        } else {
                            note = NO_STEP_DOWN_NOTE
                        }
                    }
                },
                onLeave = {
                    val r = currentLive
                    if (r != null && farewell == null) {
                        leaving = true
                        leaveHeld = false
                        farewell = leftText(r, protocol, bailOut, penaltyToday, Instant.now())
                        fx.dismissed()
                        cb.bailOut(r.id)
                    }
                },
                onLeaveHeld = { leaveHeld = it },
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

private const val START_MS = 700L
private const val POLL_MS = 250L
private const val FAREWELL_MS = 2_500L
private const val NOTE_MS = 6_000L
private const val ENDING_TRIES = 4
private const val ENDING_RETRY_MS = 250L
private const val TAP_MS = 400L
private const val TAP_GAP_MS = 1_500L
private const val KILL_SWITCH_TAPS = 7
private val ATTENTION_EYE_HEIGHT = 120.dp
private const val ATTENTION_EYE_ALPHA = 0.6f
