package dev.vigil.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.core.common.WallClock
import dev.vigil.engine.Vigil
import dev.vigil.takeover.TakeoverText
import dev.vigil.takeover.TakeoverType
import dev.vigil.ui.rememberNow
import dev.vigil.ui.theme.Palette
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * The wake alarm's full screen: over the lock screen, turning the screen on. The time, WAKE UP, and one big button
 * held for 3 seconds (no snooze: the point is to get up; the phone is across the room). Dismissing tells the engine
 * ([Vigil.wakeAlarmDismissed]), stops the ringing and opens the morning's takeover. CALL is always there. Leaving the
 * screen (power button, home) does not stop the alarm: only the hold does, or the engine's ring time running out.
 */
class WakeAlarmActivity : ComponentActivity() {
    private var test by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        test = intent.getBooleanExtra(EXTRA_TEST, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent { AlarmScreen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        test = intent.getBooleanExtra(EXTRA_TEST, false)
    }

    private fun dismiss() {
        val morning = try {
            Vigil.wakeAlarmDismissed(test)
        } catch (e: Exception) {
            Log.e(TAG, "Dismissing the alarm failed", e)
            null
        }
        WakeAlarmService.stop(this)
        // The morning has only a pool lock, so its takeover is opened here, on waking.
        if (morning != null) Vigil.openTakeover(this, morning)
        finish()
    }

    /** Calls are never blocked: the emergency dialer on a locked phone, the normal one otherwise. */
    private fun call() {
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val emergency = Intent(EMERGENCY_DIAL_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = (locked && runCatching { startActivity(emergency) }.isSuccess) || runCatching { startActivity(dial) }.isSuccess
        if (!opened || locked) moveTaskToBack(true)
    }

    @Composable
    private fun AlarmScreen() {
        val ringing by WakeAlarmService.ringing.collectAsState()
        val state by Vigil.state.collectAsState()
        val now = rememberNow(5_000L)
        val alarm = state.wakeAlarm
        // Nothing left to dismiss (a TEST ring that ran out, or the morning already started at an unlock): close.
        var rang by remember { mutableStateOf(false) }
        LaunchedEffect(ringing) { if (ringing) rang = true }
        val over = if (test) rang && !ringing else alarm.ringingFor == null && alarm.morningAtUnlock == null
        LaunchedEffect(over) { if (over) finish() }
        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.Black)
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            TakeoverText(WallClock.hhmm(Instant.ofEpochMilli(now), Graph.zone), TakeoverType.Timer)
            Spacer(Modifier.height(16.dp))
            TakeoverText(if (test) "WAKE UP · TEST" else "WAKE UP", TakeoverType.Title)
            Spacer(Modifier.height(16.dp))
            val again = alarm.nextRingAt
            val line = when {
                ringing -> "FEET ON THE FLOOR. HOLD THE BUTTON FOR 3 SECONDS."
                again != null -> "IT RINGS AGAIN AT ${WallClock.hhmm(again, Graph.zone)}. HOLD TO START THE MORNING."
                else -> "HOLD THE BUTTON TO START THE MORNING."
            }
            TakeoverText(line, TakeoverType.Small)
            Spacer(Modifier.weight(1f))
            HoldToWake(onDone = ::dismiss)
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                TakeoverText(
                    "CALL",
                    TakeoverType.Strip,
                    Modifier
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                if (waitForUpOrCancellation() != null) call()
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 14.dp),
                )
            }
        }
    }

    companion object {
        private const val TAG = "VigilWakeAlarm"
        private const val EXTRA_TEST = "test"
        private const val EMERGENCY_DIAL_ACTION = "com.android.phone.EmergencyDialer.DIAL"

        fun intent(context: Context, test: Boolean): Intent =
            Intent(context, WakeAlarmActivity::class.java)
                .putExtra(EXTRA_TEST, test)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

/** The big I'M UP button: held for [HOLD_MS] it fires; while held it fills with red from the left; letting go resets it. */
@Composable
private fun HoldToWake(onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    var heldSince by remember { mutableLongStateOf(0L) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(heldSince) {
        val since = heldSince
        if (since == 0L) {
            progress = 0f
            return@LaunchedEffect
        }
        while (heldSince == since) {
            val p = (SystemClock.uptimeMillis() - since).toFloat() / HOLD_MS
            if (p >= 1f) {
                heldSince = 0L
                done()
                break
            }
            progress = p
            delay(30)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .border(1.dp, Palette.White, RectangleShape)
            .drawBehind { if (progress > 0f) drawRect(Palette.Red, size = Size(size.width * progress, size.height)) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    heldSince = SystemClock.uptimeMillis()
                    waitForUpOrCancellation()
                    heldSince = 0L
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        TakeoverText(if (heldSince != 0L) "KEEP HOLDING" else "HOLD · I'M UP", TakeoverType.Choice)
    }
}

private const val HOLD_MS = 3_000L
