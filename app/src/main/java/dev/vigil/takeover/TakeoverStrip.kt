package dev.vigil.takeover

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.vigil.engine.WhitelistApp
import dev.vigil.ui.components.holdProgress
import dev.vigil.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Always at the bottom, faint and small: CALL (a tap, always: the dialer), APPS (a tap: the whitelist's apps), NOT
 * OKAY (3 s hold, only when [stepDown]: it changes something, or says that nothing does) and LEAVE (10 s hold, unless
 * [leave] is false: there is nothing to leave). The holds show only while [holds]. A held word turns into a number
 * counting down; [onLeaveHeld] reports while LEAVE is held, so the honest line about its cost can show meanwhile. Each
 * word keeps its place whether or not the others show; NOT OKAY gets the widest share, so it stays on one line on a
 * narrow phone.
 */
@Composable
internal fun SafetyStrip(
    holds: Boolean,
    stepDown: Boolean,
    apps: Boolean,
    leave: Boolean = true,
    onCall: () -> Unit,
    onApps: () -> Unit,
    onNotOkay: () -> Unit,
    onLeave: () -> Unit,
    onLeaveHeld: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            TapWord("CALL", onCall)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (apps) TapWord("APPS", onApps)
        }
        Box(Modifier.weight(1.5f), contentAlignment = Alignment.Center) {
            if (holds && stepDown) HoldWord("NOT OKAY", NOT_OKAY_HOLD_MS, onNotOkay)
        }
        Box(Modifier.weight(1.1f), contentAlignment = Alignment.CenterEnd) {
            if (holds && leave) HoldWord("LEAVE", LEAVE_HOLD_MS, onLeave, onLeaveHeld)
        }
    }
}

/** A word that acts on a tap. */
@Composable
private fun TapWord(label: String, onTap: () -> Unit) {
    TakeoverText(
        label,
        TakeoverType.Strip,
        Modifier
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .padding(horizontal = WORD_PAD, vertical = 14.dp),
    )
}

/**
 * A word that must be held for [holdMs]; while held it shows only the seconds left over a red bar that grows to the
 * end of the hold. Releasing early resets both. [onHolding] says when a hold starts and ends (also when the word goes
 * away mid-hold).
 */
@Composable
private fun HoldWord(label: String, holdMs: Long, onComplete: () -> Unit, onHolding: (Boolean) -> Unit = {}) {
    val complete by rememberUpdatedState(onComplete)
    val holding by rememberUpdatedState(onHolding)
    var heldSince by remember { mutableLongStateOf(0L) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(heldSince) {
        // This hold's start, read once: after an early release the state is already 0 before this loop is cancelled.
        val since = heldSince
        if (since == 0L) {
            progress = 0f
            return@LaunchedEffect
        }
        while (heldSince == since) {
            val held = SystemClock.uptimeMillis() - since
            val remaining = holdMs - held
            if (remaining <= 0) {
                heldSince = 0L
                progress = 0f
                complete()
                break
            }
            secondsLeft = ((remaining + 999) / 1000).toInt()
            progress = held.toFloat() / holdMs
            delay(HOLD_POLL_MS)
        }
    }

    DisposableEffect(Unit) {
        onDispose { holding(false) }
    }

    val held = heldSince != 0L
    // The label always takes its space (invisible while held), so the target never shrinks under the finger
    // when the countdown replaces it; a press that slid off a shrinking target would cancel itself.
    // While held, a red bar under the word grows to its full width at the end of the hold.
    Box(
        Modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    secondsLeft = ((holdMs + 999) / 1000).toInt()
                    heldSince = SystemClock.uptimeMillis()
                    holding(true)
                    waitForUpOrCancellation()
                    heldSince = 0L
                    holding(false)
                }
            }
            .padding(horizontal = WORD_PAD, vertical = 14.dp)
            .holdProgress({ progress }),
        contentAlignment = Alignment.Center,
    ) {
        TakeoverText(label, TakeoverType.Strip, color = if (held) Color.Transparent else TakeoverType.Faint)
        if (held) TakeoverText(secondsLeft.toString(), TakeoverType.Strip, color = Palette.White)
    }
}

/**
 * The APPS sheet over the takeover: the whitelist's apps (usable during every lock), loaded off the main thread.
 * Picking one leaves the takeover for that app; CLOSE (or Back) returns to the takeover.
 */
@Composable
internal fun AppsSheet(load: () -> List<WhitelistApp>, onOpen: (WhitelistApp) -> Unit, onClose: () -> Unit) {
    val loader by rememberUpdatedState(load)
    val apps by produceState<List<WhitelistApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { loader() }.getOrDefault(emptyList()) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Black)
            // Takes every touch, so nothing under the sheet reacts to it.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TakeoverText("APPS", TakeoverType.Title, Modifier.padding(top = 24.dp))
        Spacer(Modifier.height(8.dp))
        TakeoverText("USABLE DURING EVERY LOCK", TakeoverType.Small)
        Spacer(Modifier.height(32.dp))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val list = apps
            if (list == null) {
                TakeoverText("...", TakeoverType.Small)
            } else if (list.isEmpty()) {
                TakeoverText("NONE OF THEM IS ON THIS PHONE", TakeoverType.Small)
            } else {
                for (app in list) {
                    key(app.packageName) {
                        TakeoverText(
                            app.label,
                            TakeoverType.Choice,
                            Modifier
                                .fillMaxWidth()
                                .clickable(remember { MutableInteractionSource() }, indication = null) { onOpen(app) }
                                .padding(vertical = 16.dp),
                        )
                    }
                }
            }
        }
        TakeoverText(
            "CLOSE",
            TakeoverType.Strip,
            Modifier
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}

private const val NOT_OKAY_HOLD_MS = 3_000L
private const val LEAVE_HOLD_MS = 10_000L
private const val HOLD_POLL_MS = 30L
private val WORD_PAD = 8.dp
