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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.vigil.alarm.WhiteNoiseService
import dev.vigil.core.common.WallClock
import dev.vigil.core.protocol.BailOutConfig
import dev.vigil.core.protocol.Protocol
import dev.vigil.core.run.Run
import dev.vigil.engine.WhitelistApp
import dev.vigil.ui.rememberNow
import dev.vigil.ui.theme.Palette
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/**
 * The night lock (the sleep protocol's run in phase night): a dark, silent, very dim screen. The time, SLEEP, when
 * the wake alarm rings, tomorrow's first to-do and the WHITE NOISE switch; at the bottom CALL, APPS and LEAVE (a 10 s
 * hold, the usual penalty). Seven taps on the closed eye open the kill switch. No intro, no sound, no vibration, no
 * Glyph. [live] is the night run while it owns the screen; once it does not (the alarm was answered, Fajr took the
 * screen, LEAVE) the screen hands over with [onFinished].
 */
@Composable
internal fun NightScreen(
    initial: Run,
    live: Run?,
    protocol: Protocol?,
    alarmAt: Instant?,
    firstTodo: String?,
    zone: ZoneId,
    bailOut: BailOutConfig,
    penaltyToday: Int,
    onCall: () -> Unit,
    loadApps: () -> List<WhitelistApp>,
    onOpenApp: (WhitelistApp) -> Unit,
    onKillSwitch: () -> Unit,
    onLeave: (String) -> Unit,
    onFinished: () -> Unit,
) {
    val now = rememberNow(10_000L)
    val finished by rememberUpdatedState(onFinished)
    var appsOpen by remember { mutableStateOf(false) }
    var leaveHeld by remember { mutableStateOf(false) }
    var farewell by remember { mutableStateOf<String?>(null) }
    var eyeTaps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    val run = live ?: initial

    // The night lock is over or another takeover has the screen: hand over (after the farewell line, when left).
    LaunchedEffect(live == null) {
        if (live != null) return@LaunchedEffect
        if (farewell != null) delay(NIGHT_FAREWELL_MS)
        finished()
    }

    BackHandler(enabled = appsOpen) { appsOpen = false }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val left = farewell
                if (left != null) {
                    TakeoverText(left, TakeoverType.Step, color = TakeoverType.Dim)
                } else {
                    // The closed eye, dim: seven taps open the kill switch's 5-minute wait.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(EYE_HEIGHT)
                            .alpha(EYE_ALPHA)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val up = waitForUpOrCancellation()
                                    if (up != null && up.uptimeMillis - down.uptimeMillis < NIGHT_TAP_MS) {
                                        if (up.uptimeMillis - lastTapAt > NIGHT_TAP_GAP_MS) eyeTaps = 0
                                        lastTapAt = up.uptimeMillis
                                        eyeTaps++
                                        if (eyeTaps >= NIGHT_KILL_SWITCH_TAPS) {
                                            eyeTaps = 0
                                            onKillSwitch()
                                        }
                                    }
                                }
                            },
                    ) {
                        Eye(
                            mode = EyeMode.SLIT,
                            modeStartedAt = 0L,
                            tier = 1,
                            clock = remember { mutableLongStateOf(0L) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    TakeoverText(WallClock.hhmm(Instant.ofEpochMilli(now), zone), TakeoverType.Timer, color = TakeoverType.Faint)
                    Spacer(Modifier.height(12.dp))
                    TakeoverText("SLEEP", TakeoverType.Title, color = TakeoverType.Dim)
                    Spacer(Modifier.height(20.dp))
                    val alarm = alarmAt?.let { "ALARM ${WallClock.hhmm(it, zone)}" } ?: "UNTIL YOUR ALARM"
                    TakeoverText(alarm, TakeoverType.Small, color = TakeoverType.Faint)
                    if (firstTodo != null) {
                        Spacer(Modifier.height(8.dp))
                        TakeoverText("TOMORROW: $firstTodo", TakeoverType.Small, color = TakeoverType.Faint)
                    }
                    Spacer(Modifier.height(28.dp))
                    NoiseSwitch(dim = true)
                }
            }
            val honest = if (leaveHeld && farewell == null) leaveLine(run, protocol, bailOut, penaltyToday, Instant.now()) else null
            if (honest != null) {
                TakeoverText(honest, TakeoverType.Strip, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), color = Palette.White)
            }
            SafetyStrip(
                holds = live != null && farewell == null,
                stepDown = false,
                apps = farewell == null,
                onCall = onCall,
                onApps = { appsOpen = true },
                onNotOkay = {},
                onLeave = {
                    val r = live
                    if (r != null && farewell == null) {
                        leaveHeld = false
                        farewell = leftText(r, protocol, bailOut, penaltyToday, Instant.now())
                        onLeave(r.id)
                    }
                },
                onLeaveHeld = { leaveHeld = it },
            )
        }
        if (appsOpen) {
            AppsSheet(
                load = loadApps,
                onOpen = { app ->
                    appsOpen = false
                    onOpenApp(app)
                },
                onClose = { appsOpen = false },
            )
        }
    }
}

/**
 * WHITE NOISE ON / OFF: a tap starts or stops white noise made in code ([WhiteNoiseService]: it keeps playing with the
 * screen locked, stops at the wake alarm and after 8 hours). On the wind-down's lights-low step and the night lock.
 */
@Composable
internal fun NoiseSwitch(dim: Boolean = false) {
    val context = LocalContext.current
    val playing by WhiteNoiseService.playing.collectAsState()
    val color = when {
        playing -> if (dim) TakeoverType.Dim else Palette.White
        else -> TakeoverType.Faint
    }
    TakeoverText(
        if (playing) "WHITE NOISE · ON" else "WHITE NOISE · OFF",
        TakeoverType.Strip,
        Modifier
            .clickable(remember { MutableInteractionSource() }, indication = null) { WhiteNoiseService.set(context, !playing) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        color = color,
    )
}

private val EYE_HEIGHT = 96.dp
private const val EYE_ALPHA = 0.5f
private const val NIGHT_TAP_MS = 400L
private const val NIGHT_TAP_GAP_MS = 1_500L
private const val NIGHT_KILL_SWITCH_TAPS = 7
private const val NIGHT_FAREWELL_MS = 3_200L
