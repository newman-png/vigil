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
import dev.vigil.core.protocol.TakeoverConfig
import dev.vigil.core.run.Run
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * A wellness nudge (design M8): brief and plain. One heartbeat as it appears, then one big line (drink water, look far
 * away, stand up) and its seconds counting down; at zero it says DONE and the engine ends it. NOT OKAY or LEAVE end it at
 * once, at no cost (it is never a miss). CALL and APPS stay at the bottom; seven taps on the small eye open the kill
 * switch. [live] is the nudge while it owns the screen; once it does not, the screen hands over
 * ([TakeoverCallbacks.finished]).
 */
@Composable
internal fun NudgeScreen(
    initial: Run,
    live: Run?,
    takeover: TakeoverConfig,
    fx: TakeoverFx,
    callbacks: TakeoverCallbacks,
) {
    val id = initial.id
    val cb by rememberUpdatedState(callbacks)
    val currentLive by rememberUpdatedState(live)
    val line = initial.label ?: callbacks.stepView(id)?.step?.text ?: ""
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var farewell by remember { mutableStateOf<String?>(null) }
    var appsOpen by remember { mutableStateOf(false) }
    var eyeTaps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    val ends = initial.lockEndsAt

    LaunchedEffect(Unit) {
        // A moment for the sound to load, then one heartbeat; the Glyph lights go off after their time.
        delay(START_MS)
        fx.heartbeat()
        cb.introSeen()
        delay(takeover.glyphOffAfterS * 1000L)
        fx.glyphOff()
    }

    // The clock, and the engine brought up to date when the seconds are over (its alarm does the same).
    LaunchedEffect(Unit) {
        var ticked = false
        while (true) {
            nowSec = System.currentTimeMillis() / 1000
            if (!ticked && ends != null && nowSec >= ends.epochSecond + 1 && currentLive != null) {
                ticked = true
                cb.tick()
            }
            delay(POLL_MS)
        }
    }

    // The nudge is over: a short word, then hand over.
    LaunchedEffect(Unit) {
        snapshotFlow { currentLive == null }.first { it }
        if (farewell == null) {
            fx.completed()
            farewell = "DONE."
        }
        delay(FAREWELL_MS)
        cb.finished()
    }

    /** NOT OKAY or LEAVE: the nudge ends now, at no cost. */
    fun endNow(act: (String) -> Unit) {
        val r = currentLive ?: return
        if (farewell != null) return
        farewell = "OKAY. THIS COSTS NOTHING."
        fx.dismissed()
        act(r.id)
    }

    BackHandler(enabled = appsOpen) { appsOpen = false }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val left = farewell
                if (left != null) {
                    TakeoverText(left, TakeoverType.Step)
                } else {
                    // The small eye: seven taps open the kill switch's 5-minute wait.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(NUDGE_EYE_HEIGHT)
                            .alpha(NUDGE_EYE_ALPHA)
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
                    TakeoverText(line, TakeoverType.Title)
                    Spacer(Modifier.height(32.dp))
                    val seconds = ends?.let { secondsUntil(it, nowSec) } ?: 0L
                    if (seconds > 0) {
                        TakeoverText(countdown(seconds), TakeoverType.BigTimer)
                    } else {
                        // Over: DONE (the engine ends it at its time anyway; a tap only brings it up to date).
                        TakeoverText(
                            "DONE",
                            TakeoverType.Choice,
                            Modifier
                                .clickable(remember { MutableInteractionSource() }, indication = null) { cb.confirm(id) }
                                .padding(16.dp),
                        )
                    }
                    if (initial.test) {
                        Spacer(Modifier.height(24.dp))
                        TakeoverText("TEST: NOTHING COUNTS", TakeoverType.Small)
                    }
                }
            }
            SafetyStrip(
                holds = live != null && farewell == null,
                stepDown = true,
                apps = farewell == null,
                onCall = { cb.call() },
                onApps = { appsOpen = true },
                onNotOkay = { endNow { cb.notOkay(it) } },
                onLeave = { endNow { cb.bailOut(it) } },
                onLeaveHeld = {},
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

private const val START_MS = 500L
private const val POLL_MS = 250L
private const val FAREWELL_MS = 1_500L
private const val TAP_MS = 400L
private const val TAP_GAP_MS = 1_500L
private const val KILL_SWITCH_TAPS = 7
private val NUDGE_EYE_HEIGHT = 96.dp
private const val NUDGE_EYE_ALPHA = 0.6f
