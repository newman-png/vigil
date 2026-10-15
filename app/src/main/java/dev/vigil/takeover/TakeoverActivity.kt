package dev.vigil.takeover

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.vigil.Graph
import dev.vigil.core.engine.Engine
import dev.vigil.core.plan.CheckInAnswer
import dev.vigil.core.plan.Rating
import dev.vigil.engine.Vigil
import dev.vigil.ui.theme.Palette

/**
 * The full-screen takeover. Shows over the lock screen, turns the screen on and ignores Back.
 * It renders whatever the engine says is active and reports taps back to it; it never decides anything.
 */
class TakeoverActivity : ComponentActivity() {
    /** True while a closing screen has handed over and the activity is finishing on purpose. */
    private var closing = false

    /** Sound, vibration and lights of the takeover on screen, paused whenever the activity is not visible. */
    private var fx: TakeoverFx? = null

    /** The takeover this screen is playing (its ending may still run after the engine has moved on to the next one). */
    private var onScreenId: String? = null

    private val handler = Handler(Looper.getMainLooper())

    // An unattended takeover lets the display sleep after a while instead of keeping it lit for up to 90 minutes.
    private val letScreenSleep = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        keepScreenOn()
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        hideSystemBars()
        TakeoverLauncher.register(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent { Root() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        TakeoverLauncher.visible = true
        TakeoverLauncher.onVisible(this)
        keepScreenOn()
        fx?.resume()
    }

    override fun onStop() {
        super.onStop()
        TakeoverLauncher.visible = false
        handler.removeCallbacks(letScreenSleep)
        // Nothing keeps sounding, vibrating or glowing behind other apps, a call or a dark screen.
        fx?.pause()
        if (closing || isChangingConfigurations) return
        // Left on a locked or dark phone: step back, so the next screen-on shows the plain lock screen (and its
        // emergency button). An unfinished takeover comes back after unlock, as the launcher's rules say.
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val awake = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (locked || !awake) moveTaskToBack(true)
        // Left while the previous takeover's ending was still playing: the next one has never been on screen, so it
        // is still a new directive (it may wake the screen), not one the user walked away from.
        val active = Graph.state.current.active?.takeoverId
        if (active != null && active != onScreenId) TakeoverLauncher.ensureShowing(this) else TakeoverLauncher.onEscaped(this)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        keepScreenOn()
    }

    override fun onDestroy() {
        handler.removeCallbacks(letScreenSleep)
        super.onDestroy()
    }

    /** Keeps the display on for [KEEP_SCREEN_ON_MS] after the takeover appears or is touched. */
    private fun keepScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(letScreenSleep)
        handler.postDelayed(letScreenSleep, KEEP_SCREEN_ON_MS)
    }

    /** The engine closed the takeover while it was behind other apps (timed out, kill switch, bad day elsewhere). */
    fun finishIfHidden() {
        if (!TakeoverLauncher.visible) close()
    }

    @Composable
    private fun Root() {
        val live by Graph.state.state.collectAsState()
        val plans by Graph.plans.snapshot.collectAsState()
        var shown by remember { mutableStateOf(Graph.state.current.active) }
        val ticket = shown
        SideEffect { onScreenId = ticket?.takeoverId }

        Box(
            Modifier
                .fillMaxSize()
                .background(Palette.Black)
                .crtScanlines(),
        ) {
            if (ticket == null) {
                LaunchedEffect(Unit) { close() }
                return@Box
            }
            // Re-read after an import, so the screen and the engine always agree on the steps.
            val directive = remember(ticket.takeoverId, plans) { Engine.directiveFor(Graph.env(), ticket) }
            if (directive == null) {
                LaunchedEffect(ticket.takeoverId) { close() }
                return@Box
            }
            key(ticket.takeoverId) {
                val fx = remember {
                    val s = Graph.settings
                    TakeoverFx(this@TakeoverActivity, directive.tier, ticket.silent, s.soundEnabled, s.vibrationEnabled, s.glyphEnabled)
                }
                DisposableEffect(Unit) {
                    this@TakeoverActivity.fx = fx
                    fx.start()
                    onDispose {
                        if (this@TakeoverActivity.fx === fx) this@TakeoverActivity.fx = null
                        fx.release()
                    }
                }
                val liveTicket = live.active?.takeIf { it.takeoverId == ticket.takeoverId }
                // Quiet mode switched on while this takeover shows: the engine marks it silent.
                LaunchedEffect(liveTicket?.silent) {
                    if (liveTicket?.silent == true && !ticket.silent) fx.mute()
                }
                // The screen keeps its text clear of cutouts itself, so its flashes and effects cover the whole display.
                TakeoverScreen(
                    initial = ticket,
                    live = liveTicket,
                    directive = directive,
                    fx = fx,
                    callbacks = callbacks(onFinished = {
                        val next = Graph.state.current.active
                        if (next != null && next.takeoverId != ticket.takeoverId) shown = next else close()
                    }),
                )
            }
        }
    }

    private fun callbacks(onFinished: () -> Unit) = object : TakeoverCallbacks {
        override fun stepShown(takeoverId: String) = Vigil.stepShown(takeoverId)
        override fun stepDone(takeoverId: String, index: Int) = Vigil.stepDone(takeoverId, index)
        override fun dismiss(takeoverId: String) = Vigil.dismiss(takeoverId)
        override fun notOkay(takeoverId: String) = Vigil.notOkay(takeoverId)
        override fun call() {
            fx?.pause()
            openDialer()
        }
        override fun suspend(minutes: Long) = TakeoverLauncher.suspend(minutes)
        override fun pendingRatingFor(takeoverId: String) = Vigil.pendingRatingFor(takeoverId)
        override fun rate(takeoverId: String, rating: Rating?) = Vigil.rate(takeoverId, rating)
        override fun answerCheckIn(takeoverId: String, answer: CheckInAnswer) = Vigil.answerCheckIn(takeoverId, answer)
        override fun finished() = onFinished()
    }

    private fun close() {
        if (closing) return
        closing = true
        if (Graph.state.current.active == null) TakeoverLauncher.onClosed(this)
        finish()
    }

    /**
     * Calls are never blocked: the takeover steps aside (it also moves behind the lock screen, whose own
     * emergency button is then reachable) and the dialer opens: the emergency dialer when the phone is
     * locked, the normal one otherwise.
     */
    private fun openDialer() {
        TakeoverLauncher.stepAsideForCall()
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val emergency = Intent(EMERGENCY_DIAL_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = (locked && runCatching { startActivity(emergency) }.isSuccess) ||
            runCatching { startActivity(dial) }.isSuccess
        if (!opened || locked) moveTaskToBack(true)
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        /** The phone app's emergency dialer, the same screen the lock screen's "Emergency call" opens. */
        private const val EMERGENCY_DIAL_ACTION = "com.android.phone.EmergencyDialer.DIAL"

        /** How long the display stays on after the takeover appears or is last touched. */
        private const val KEEP_SCREEN_ON_MS = 10 * 60_000L

        fun intent(context: android.content.Context): Intent =
            Intent(context, TakeoverActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
