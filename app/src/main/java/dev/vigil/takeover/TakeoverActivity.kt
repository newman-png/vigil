package dev.vigil.takeover

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.vigil.Graph
import dev.vigil.core.journal.Entry
import dev.vigil.core.machine.StepView
import dev.vigil.core.protocol.Intensity
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.run.Run
import dev.vigil.core.run.RunPhase
import dev.vigil.engine.Device
import dev.vigil.engine.Vigil
import dev.vigil.engine.WhitelistApp
import dev.vigil.guard.GuardScreens
import dev.vigil.ui.theme.Palette

/**
 * The full-screen takeover of the run that owns the screen ([Vigil.activeRun]), or, when none does, of a run without a
 * screen lock that was asked for ([EXTRA_RUN]: the morning, from its notification or the wake alarm). Shows over the
 * lock screen, turns the screen on and ignores Back. It renders the run as the engine has it and reports taps back to
 * it; it never decides anything. When nothing is left to show it finishes. The night lock gets its own dark, silent,
 * dim screen ([NightScreen]) that never turns the screen on and lets it sleep.
 */
class TakeoverActivity : ComponentActivity() {
    /** True while a closing screen has handed over and the activity is finishing on purpose. */
    private var closing = false

    /** Sound, vibration and lights of the takeover on screen, paused whenever the activity is not visible. */
    private var fx: TakeoverFx? = null

    /** The takeover this screen is playing ([takeoverKey]); its ending may still run after the engine moved on. */
    private var onScreenKey: String? = null

    private val handler = Handler(Looper.getMainLooper())

    /** The run asked for by [EXTRA_RUN] (shown while it is active and nothing owns the screen). */
    private var requested by mutableStateOf<String?>(null)

    /** True while the night lock is on screen: dim, and the display may sleep. */
    private var night = false

    // An unattended takeover lets the display sleep after a while instead of keeping it lit for as long as it lasts.
    private val letScreenSleep = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        keepScreenOn()
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        resizeForKeyboard()
        hideSystemBars()
        TakeoverLauncher.register(this)
        requested = intent.getStringExtra(EXTRA_RUN)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent { Root() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_RUN)?.let { requested = it }
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
        // The night lock only comes back on a phone in use; whatever brings this screen back next (a prayer at night)
        // may turn the screen on again.
        if (night) setTurnScreenOn(true)
        if (closing || isChangingConfigurations) return
        // Left on a locked or dark phone: step back, so the next screen-on shows the plain lock screen (and its
        // emergency button). An unfinished takeover comes back after unlock, as the launcher's rules say.
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val awake = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (locked || !awake) moveTaskToBack(true)
        // Left while the previous takeover's ending was still playing: the next one has never been on screen, so it
        // is still a new one (it may wake the screen), not one the user walked away from.
        val active = Vigil.activeRun()
        if (active != null && takeoverKey(active) != onScreenKey) TakeoverLauncher.ensureShowing(this) else TakeoverLauncher.onEscaped(this)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        keepScreenOn()
    }

    override fun onDestroy() {
        handler.removeCallbacks(letScreenSleep)
        super.onDestroy()
    }

    /** Keeps the display on for [KEEP_SCREEN_ON_MS] after the takeover appears or is touched (never for the night lock). */
    private fun keepScreenOn() {
        if (night) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(letScreenSleep)
        handler.postDelayed(letScreenSleep, KEEP_SCREEN_ON_MS)
    }

    /**
     * The night lock is on screen ([on]) or not: very dim, the display sleeps when it would, and the activity never
     * turns the screen on for it.
     */
    private fun nightMode(on: Boolean) {
        night = on
        setTurnScreenOn(!on)
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) NIGHT_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (on) {
            handler.removeCallbacks(letScreenSleep)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            keepScreenOn()
        }
    }

    /** What this screen shows now: the run owning the screen, else the run asked for while it is active. */
    private fun current(): Run? = Vigil.activeRun() ?: requested?.let { id ->
        Vigil.state.value.runs.firstOrNull { it.id == id && it.phase == RunPhase.ACTIVE && it.lock != LockType.SCREEN }
    }

    /**
     * No run owns the screen any more while the takeover is behind other apps (finished, lock over, kill switch). A run
     * without a screen lock opened from its notification (the gym between sets, a meal) stays behind the lock screen or
     * other apps while it runs: its notification brings it back, and its timers still sound.
     */
    fun finishIfHidden() {
        if (!TakeoverLauncher.visible && current() == null) close()
    }

    @Composable
    private fun Root() {
        // Every engine step recomposes: who owns the screen now, and what a bail-out would cost.
        val state by Vigil.state.collectAsState()
        var shown by remember { mutableStateOf(current()) }
        // Bumped when the same run takes the screen again after its takeover ended (a fresh screen for it).
        var generation by remember { mutableIntStateOf(0) }
        val ticket = shown
        SideEffect { onScreenKey = ticket?.let { takeoverKey(it) } }

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
            val ticketKey = takeoverKey(ticket)
            val owner = current()
            val penaltyToday = state.pool.penaltyMinutesToday
            val onFinished: () -> Unit = {
                val next = current()
                if (next == null) {
                    close()
                } else {
                    // The same run owning the screen again after its takeover ended gets a fresh one.
                    if (takeoverKey(next) == ticketKey) generation++
                    shown = next
                }
            }
            if (ticket.phase == RunPhase.NIGHT) {
                key(ticketKey, generation) {
                    DisposableEffect(Unit) {
                        nightMode(true)
                        onDispose { nightMode(false) }
                    }
                    val file = remember { Vigil.file() }
                    NightScreen(
                        initial = ticket,
                        live = owner?.takeIf { takeoverKey(it) == ticketKey },
                        protocol = remember { file.find(ticket.protocol) },
                        alarmAt = Vigil.wakeAlarmAt(),
                        firstTodo = Vigil.todos().firstOrNull { !it.done }?.text,
                        zone = Graph.zone,
                        bailOut = file.pool.bailOut,
                        penaltyToday = penaltyToday,
                        onCall = { openDialer() },
                        loadApps = { Vigil.whitelistApps() },
                        onOpenApp = { openApp(it) },
                        onKillSwitch = { GuardScreens.killSwitch(this@TakeoverActivity) },
                        onLeave = { Vigil.bailOut(it) },
                        onFinished = onFinished,
                    )
                }
                return@Box
            }
            if (ticket.kind == ProtocolKind.NUDGE) {
                key(ticketKey, generation) {
                    val file = remember { Vigil.file() }
                    val fx = remember {
                        val s = Graph.settings
                        TakeoverFx(this@TakeoverActivity, tierFor(Vigil.intensity(ticket)), Vigil.isSilent(ticket), s.soundEnabled, s.vibrationEnabled, s.glyphEnabled)
                    }
                    DisposableEffect(Unit) {
                        this@TakeoverActivity.fx = fx
                        fx.start()
                        onDispose {
                            if (this@TakeoverActivity.fx === fx) this@TakeoverActivity.fx = null
                            fx.release()
                        }
                    }
                    // One big line and its seconds: drink water, look far away, stand up.
                    NudgeScreen(
                        initial = ticket,
                        live = owner?.takeIf { takeoverKey(it) == ticketKey },
                        takeover = file.takeover,
                        fx = fx,
                        callbacks = callbacks(onIntroSeen = { rememberIntroSeen(ticketKey) }, onFinished = onFinished),
                    )
                }
                return@Box
            }
            if (ticket.kind == ProtocolKind.ATTENTION) {
                key(ticketKey, generation) {
                    val file = remember { Vigil.file() }
                    val silent = remember { Vigil.isSilent(ticket) }
                    val fx = remember {
                        val s = Graph.settings
                        TakeoverFx(this@TakeoverActivity, tierFor(Vigil.intensity(ticket)), silent, s.soundEnabled, s.vibrationEnabled, s.glyphEnabled)
                    }
                    DisposableEffect(Unit) {
                        this@TakeoverActivity.fx = fx
                        fx.start()
                        onDispose {
                            if (this@TakeoverActivity.fx === fx) this@TakeoverActivity.fx = null
                            fx.release()
                        }
                    }
                    // A very plain screen: LOOK AT ONE SPOT and the countdown, a chime at the end.
                    AttentionScreen(
                        initial = ticket,
                        live = owner?.takeIf { takeoverKey(it) == ticketKey },
                        protocol = remember { file.find(ticket.protocol) },
                        penaltyToday = penaltyToday,
                        bailOut = file.pool.bailOut,
                        fx = fx,
                        callbacks = callbacks(onIntroSeen = { rememberIntroSeen(ticketKey) }, onFinished = onFinished),
                    )
                }
                return@Box
            }
            key(ticketKey, generation) {
                val live = owner?.takeIf { takeoverKey(it) == ticketKey }
                val file = remember { Vigil.file() }
                val protocol = remember { file.find(ticket.protocol) }
                // The protocol's intensity; the Jummah lock is a subtle, silent screen.
                val intensity = remember { Vigil.intensity(live ?: ticket) }
                val silentAtStart = remember { Vigil.isSilent(live ?: ticket) }
                val shownSince = remember { shownSince(ticketKey) }
                val introSeen = remember { ticketKey in introsSeen }
                val fx = remember {
                    val s = Graph.settings
                    // Subtle: a quiet screen, no sound and no Glyph from the start (light haptics only).
                    val subtle = intensity == Intensity.SUBTLE
                    TakeoverFx(
                        this@TakeoverActivity, tierFor(intensity), silentAtStart,
                        soundEnabled = s.soundEnabled && !subtle,
                        vibrationEnabled = s.vibrationEnabled,
                        glyphEnabled = s.glyphEnabled && !subtle,
                    )
                }
                DisposableEffect(Unit) {
                    this@TakeoverActivity.fx = fx
                    fx.start()
                    onDispose {
                        if (this@TakeoverActivity.fx === fx) this@TakeoverActivity.fx = null
                        fx.release()
                    }
                }
                // QUIET switched on while this takeover shows: silent from now on.
                val silentNow = live?.let { Vigil.isSilent(it) } ?: silentAtStart
                LaunchedEffect(silentNow) {
                    if (silentNow && !silentAtStart) fx.mute()
                }
                // The screen keeps its text clear of cutouts itself, so its flashes and effects cover the whole display.
                TakeoverScreen(
                    initial = ticket,
                    live = live,
                    protocol = protocol,
                    intensity = intensity,
                    steps = live?.let { Vigil.steps(it) } ?: emptyList(),
                    // The morning shows today's first open to-do (written in last night's wind-down).
                    todo = if (protocol?.kind == ProtocolKind.MORNING) todoLine(Vigil.todos().filter { !it.done }.map { it.text }) else null,
                    penaltyToday = penaltyToday,
                    bailOut = file.pool.bailOut,
                    takeover = file.takeover,
                    shownSinceMs = shownSince,
                    introSeen = introSeen,
                    fx = fx,
                    callbacks = callbacks(
                        onIntroSeen = { rememberIntroSeen(ticketKey) },
                        onFinished = onFinished,
                    ),
                )
            }
        }
    }

    private fun callbacks(onIntroSeen: () -> Unit, onFinished: () -> Unit) = object : TakeoverCallbacks {
        override fun stepView(runId: String): StepView? = Vigil.stepView(runId)
        override fun run(runId: String): Run? = Vigil.state.value.runs.firstOrNull { it.id == runId }
        override fun ending(runId: String): Entry? =
            Vigil.recentEntries(ENDING_LOOKBACK).lastOrNull { it.run == runId && it.type in RUN_ENDINGS }
        override fun stepDone(runId: String, index: Int, text: String?) = Vigil.stepDone(runId, index, text)
        override fun confirm(runId: String) = Vigil.confirm(runId)
        override fun notOkay(runId: String) = Vigil.notOkay(runId)
        override fun bailOut(runId: String) = Vigil.bailOut(runId)
        override fun dismiss(runId: String) {
            // A call card opened from its notification is closed too (NOT NOW), not shown again at once.
            if (requested == runId) requested = null
            Vigil.dismiss(runId)
        }
        override fun snooze(runId: String) = Vigil.snooze(runId)
        override fun snoozeMinutes(runId: String): Int? = run(runId)?.let { Vigil.snoozeMinutes(it) }
        override fun goingToSleep(ishaPrayed: Boolean) = Vigil.goingToSleep(ishaPrayed)
        override fun tick() = Vigil.onTick()
        override fun call() {
            fx?.pause()
            openDialer()
        }
        override fun message() {
            fx?.pause()
            openMessages()
        }
        override fun apps(): List<WhitelistApp> = Vigil.whitelistApps()
        override fun openApp(app: WhitelistApp) = this@TakeoverActivity.openApp(app)
        override fun killSwitch() {
            GuardScreens.killSwitch(this@TakeoverActivity)
        }
        override fun typing() = keepScreenOn()
        override fun introSeen() = onIntroSeen()
        override fun logSet(runId: String, exercise: String, reps: Int, weight: Double, restS: Int?) =
            Vigil.logSet(runId, exercise, reps, weight, restS)
        override fun finishWorkout(runId: String) = Vigil.finishWorkout(runId)
        override fun chime(loud: Boolean) {
            if (!Vigil.state.value.toggles.quiet) Chime.play(this@TakeoverActivity, loud)
        }
        override fun finished() = onFinished()
    }

    private fun close() {
        if (closing) return
        closing = true
        if (Vigil.activeRun() == null) TakeoverLauncher.onClosed(this)
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

    /**
     * APPS: leaves the takeover for a whitelisted app, which stays usable during every lock (the launcher does not
     * bring the takeover back over it). On a locked phone the user unlocks first; cancelling stays here.
     */
    private fun openApp(app: WhitelistApp) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = launch(app)
            })
        } else {
            launch(app)
        }
    }

    /**
     * MESSAGE on a call card's short version: WhatsApp (or the messaging app) opens; on a locked phone after unlocking.
     * The card is an asking takeover, so it does not come back over it.
     */
    private fun openMessages() {
        val intent = Device.messageIntent(this)
        val keyguard = getSystemService(KeyguardManager::class.java)
        val open = { runCatching { startActivity(intent) }.onFailure { Log.w(TAG, "Could not open the messages: ${it.message}") } }
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    open()
                }
            })
        } else {
            open()
        }
    }

    private fun launch(app: WhitelistApp) {
        try {
            startActivity(app.launch)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not open ${app.packageName}: ${e.message}")
        }
    }

    /**
     * A text step's keyboard: the screen gets the keyboard's insets and makes room itself (the eye gives way)
     * instead of the window being panned. With edge-to-edge this is how the insets reach the screen.
     */
    @Suppress("DEPRECATION")
    private fun resizeForKeyboard() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val TAG = "VigilTakeover"

        /** The phone app's emergency dialer, the same screen the lock screen's "Emergency call" opens. */
        private const val EMERGENCY_DIAL_ACTION = "com.android.phone.EmergencyDialer.DIAL"

        /** Which run to show when no run owns the screen (a run without a screen lock: the morning). */
        private const val EXTRA_RUN = "run"

        /** The night lock's screen brightness (0..1): as dim as it gets while still readable. */
        private const val NIGHT_BRIGHTNESS = 0.02f

        /** How long the display stays on after the takeover appears or is last touched. */
        private const val KEEP_SCREEN_ON_MS = 10 * 60_000L

        /** Takeovers remembered for [shownSince] and [introsSeen]. */
        private const val SHOWN_MAX = 32

        /** Journal entries searched for how a run ended (it ended a moment ago, so it is among the last few). */
        private const val ENDING_LOOKBACK = 40

        /** When each takeover ([takeoverKey]) first appeared (elapsed realtime), for its intro and intensity. */
        private val shownAt = LinkedHashMap<String, Long>()

        /** Takeovers ([takeoverKey]) whose intro played to the open eye in this process: they come back without it. */
        private val introsSeen = LinkedHashSet<String>()

        private fun rememberIntroSeen(key: String) {
            introsSeen += key
            while (introsSeen.size > SHOWN_MAX) introsSeen.remove(introsSeen.first())
        }

        /** When the takeover [key] first appeared in this process (now, the first time it is asked for). */
        private fun shownSince(key: String): Long {
            shownAt[key]?.let { return it }
            val now = SystemClock.elapsedRealtime()
            shownAt[key] = now
            while (shownAt.size > SHOWN_MAX) shownAt.remove(shownAt.keys.first())
            return now
        }

        fun intent(context: Context): Intent =
            Intent(context, TakeoverActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

        /** Opens the takeover of run [runId], a run without a screen lock (the morning), unless another owns the screen. */
        fun intent(context: Context, runId: String): Intent = intent(context).putExtra(EXTRA_RUN, runId)

        /** One line for the morning: the first open to-do, and how many more there are. */
        private fun todoLine(open: List<String>): String? = open.firstOrNull()?.let { first ->
            if (open.size > 1) "TODAY: $first (+${open.size - 1} MORE)" else "TODAY: $first"
        }
    }
}
