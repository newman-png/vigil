package dev.vigil.guard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.vigil.Graph
import dev.vigil.core.machine.Machine
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.pool.PoolWait
import dev.vigil.engine.Device
import dev.vigil.engine.Vigil
import java.time.Instant

/** What a Guard screen is for. */
internal enum class GuardMode {
    /** The pool's wait: stay on it until it reaches zero, then the app opens. */
    WAIT,

    /** The pool is blocked: why, until when, and HOME. */
    BLOCKED,

    /** Heavy use closed the app (the engine's Kick): "ENOUGH FOR NOW" and the cooldown. */
    ENOUGH,

    /** VIGIL's own off switch: a 5-minute wait, then VIGIL is suspended for an hour. */
    KILL,
}

/** One Guard screen: [mode] for pool app [app] (null for the kill switch). */
internal data class GuardRequest(val mode: GuardMode, val app: String?)

/**
 * The Guard's screens ([GuardMode]): black, white and red, the pixel eye, hard cuts. It shows what the engine says
 * and reports back to it (the wait started, done or abandoned; the kill switch); it decides nothing.
 *
 * It never stays behind other apps: left in any way (Home, recents, another app, the power button, a call) it
 * finishes, a wait counting as abandoned (the same wait applies next time) and the kill switch cancelled; the Guard
 * then looks again at the app in front. It never covers a call (it closes when one starts), never wakes the screen,
 * and shows over the lock screen only for the kill switch opened from the takeover there.
 */
class BlockActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())

    /** What this screen shows now. */
    private var request by mutableStateOf<GuardRequest?>(null)

    /** The app's name, for the screen. */
    private var appLabel by mutableStateOf("")

    /** The wall clock, moved on by the ticker: the screens count down from it. */
    private var now by mutableStateOf(Instant.now())

    /** The engine's wait this screen started, while it counts. */
    private var wait by mutableStateOf<PoolWait?>(null)

    /** Pool opens so far today (this one is the next). */
    private var opensToday by mutableIntStateOf(0)

    /** The engine's answer for the blocked screens, refreshed now and then. */
    private var decision by mutableStateOf<PoolDecision?>(null)

    /** When the kill switch's wait ends (elapsed realtime); 0 = not counting. */
    private var killEndsAt = 0L

    /** What is left of the kill switch's wait. */
    private var killLeftMs by mutableLongStateOf(KILL_WAIT_MS)

    /** The kill switch went through: VIGIL is off until then. */
    private var suspendedUntil by mutableStateOf<Instant?>(null)

    private var started = false
    private var ticks = 0L
    private var buzzed = false

    private val ticker = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Exception) {
                Log.e(TAG, "Tick failed", e)
            }
            if (started) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val first = requestOf(intent)
        if (first == null) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Hard cuts, no sliding in or out.
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        show(first)
        setContent {
            val r = request
            if (r != null) {
                GuardScreen(
                    request = r,
                    appLabel = appLabel,
                    now = now,
                    wait = wait,
                    opensToday = opensToday,
                    decision = decision,
                    killLeftMs = killLeftMs,
                    suspendedUntil = suspendedUntil,
                    zone = Graph.zone,
                    heavyUse = Vigil.file().pool.heavyUse,
                    onHome = { home() },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val next = requestOf(intent) ?: return
        // The same screen asked for again (another window of the same app): it keeps counting.
        if (next == request) return
        show(next)
    }

    override fun onStart() {
        super.onStart()
        started = true
        // Calls are never blocked: a Guard screen never shows over one.
        if (Device.inCall(this)) {
            finish()
            return
        }
        begin()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        inFront = true
    }

    override fun onPause() {
        inFront = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        started = false
        handler.removeCallbacks(ticker)
        if (isChangingConfigurations) return
        // Left: a wait counts as abandoned, the kill switch is cancelled, and the screen never stays behind other apps.
        endWait()
        if (!isFinishing) finish()
        // The user may have gone back to the pool app: the Guard looks again.
        GuardService.recheckSoon()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Puts [r] on screen (a new screen, or this one turning into another, e.g. a wait into a block). */
    private fun show(r: GuardRequest) {
        endWait()
        request = r
        appLabel = r.app?.let { labelOf(it) } ?: ""
        decision = null
        killEndsAt = 0L
        killLeftMs = KILL_WAIT_MS
        suspendedUntil = null
        buzzed = false
        opensToday = Vigil.state.value.pool.opens
        // Only the kill switch, opened from the takeover on a locked phone, may show over the lock screen.
        setShowWhenLocked(r.mode == GuardMode.KILL)
        if (r.mode == GuardMode.BLOCKED || r.mode == GuardMode.ENOUGH) r.app?.let { decision = Vigil.poolDecision(it) }
        if (started) begin()
        keepScreenOn()
    }

    /** The screen is visible: start what it counts. */
    private fun begin() {
        val r = request ?: return
        when (r.mode) {
            GuardMode.WAIT -> if (wait == null) r.app?.let { startWait(it) }
            GuardMode.KILL -> if (killEndsAt == 0L && suspendedUntil == null) {
                killEndsAt = SystemClock.elapsedRealtime() + KILL_WAIT_MS
                killLeftMs = KILL_WAIT_MS
            }
            GuardMode.ENOUGH -> if (!buzzed) {
                buzzed = true
                buzz()
            }
            GuardMode.BLOCKED -> Unit
        }
        keepScreenOn()
    }

    private fun tick() {
        ticks++
        now = Instant.now()
        // Calls are never blocked: close as soon as one starts (ringing included).
        if (ticks % CALL_CHECK_TICKS == 0L && Device.inCall(this)) {
            finish()
            return
        }
        val r = request ?: return
        when (r.mode) {
            GuardMode.WAIT -> {
                val w = wait
                val app = r.app
                if (w != null && app != null && !now.isBefore(endOf(w))) waitOver(app)
            }
            GuardMode.KILL -> if (killEndsAt != 0L && suspendedUntil == null) {
                killLeftMs = (killEndsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                if (killLeftMs == 0L) killDone()
            }
            GuardMode.BLOCKED, GuardMode.ENOUGH -> if (ticks % REFRESH_TICKS == 0L) {
                // A plain read: the engine's own tick keeps its state current, so this writes nothing.
                decision = Machine.poolDecision(Vigil.state.value, Graph.env())
            }
        }
    }

    // ------------------------------------------------------------------ the wait

    /** Starts the engine's wait for [app]; when there is nothing to wait for, does what the engine says instead. */
    private fun startWait(app: String, again: Boolean = true) {
        Vigil.poolWaitStarted(app)
        val w = engineWait(app)
        if (w != null) {
            wait = w
            keepScreenOn()
            return
        }
        when (val d = Vigil.poolDecision(app)) {
            // A session is running (or VIGIL is suspended): back to the app underneath.
            PoolDecision.Allowed -> finish()
            is PoolDecision.Blocked -> show(blockedRequest(d, app))
            is PoolDecision.Wait -> if (again) {
                startWait(app, again = false)
            } else {
                Log.w(TAG, "The engine did not start a wait for $app")
                home()
            }
        }
    }

    /** The wait reached zero: the engine opens the session, and the app underneath is back. */
    private fun waitOver(app: String) {
        wait = null
        keepScreenOn()
        Vigil.poolWaitDone(app)
        when (val d = Vigil.poolDecision(app)) {
            PoolDecision.Allowed -> finish()
            // Something closed the pool meanwhile (a window ended, a lock began), or this open was heavy use.
            is PoolDecision.Blocked -> show(blockedRequest(d, app))
            is PoolDecision.Wait -> {
                // The engine's wait changed meanwhile: count to its end, or start again.
                val w = engineWait(app)
                if (w != null && endOf(w).isAfter(Instant.now())) {
                    wait = w
                    keepScreenOn()
                } else {
                    startWait(app)
                }
            }
        }
    }

    /** Abandons the wait this screen started, if it is still the engine's (a newer one, from the next screen, stays). */
    private fun endWait() {
        val w = wait ?: return
        wait = null
        if (Vigil.state.value.pool.wait == w) Vigil.poolWaitAbandoned(w.app)
    }

    private fun engineWait(app: String): PoolWait? = Vigil.state.value.pool.wait?.takeIf { it.app == app }

    /** The blocked screen for [d]: "ENOUGH FOR NOW" when heavy use is the reason (e.g. the third open in a row). */
    private fun blockedRequest(d: PoolDecision.Blocked, app: String): GuardRequest =
        GuardRequest(if (d.reason == BlockReason.HEAVY_USE) GuardMode.ENOUGH else GuardMode.BLOCKED, app)

    // ------------------------------------------------------------------ the kill switch

    private fun killDone() {
        killEndsAt = 0L
        Vigil.suspend(KILL_SUSPEND_MINUTES)
        suspendedUntil = Vigil.state.value.suspendedUntil ?: Instant.now().plusSeconds(KILL_SUSPEND_MINUTES * 60)
        keepScreenOn()
    }

    // ------------------------------------------------------------------ plumbing

    /** Back: the kill switch is cancelled; any other Guard screen goes home (never back into a pool app). */
    private fun leave() {
        if (request?.mode == GuardMode.KILL) finish() else home()
    }

    /** HOME: the home screen, and this screen closes. */
    private fun home() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not go to the home screen: ${e.message}")
        }
        finish()
    }

    /** The display stays on while a wait counts (it going dark would abandon the wait). */
    private fun keepScreenOn() {
        val r = request
        val counting = r != null && (
            (r.mode == GuardMode.WAIT && wait != null) || (r.mode == GuardMode.KILL && suspendedUntil == null)
            )
        if (counting) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** One short buzz for heavy use (full style), unless QUIET is on. */
    private fun buzz() {
        if (Vigil.state.value.toggles.quiet) return
        try {
            getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(BUZZ, -1))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not vibrate: ${e.message}")
        }
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
    } catch (e: Exception) {
        pkg
    }

    companion object {
        private const val TAG = "VigilGuard"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_APP = "app"

        private const val TICK_MS = 250L

        /** Calls are looked for once a second (every this many ticks). */
        private const val CALL_CHECK_TICKS = 4L

        /** The blocked screens re-read the engine every 5 seconds. */
        private const val REFRESH_TICKS = 20L

        /** The kill switch: 5 minutes on this screen, then an hour off. */
        private const val KILL_WAIT_MS = 5 * 60_000L
        private const val KILL_SUSPEND_MINUTES = 60L

        private val BUZZ = longArrayOf(0L, 180L, 120L, 180L)

        /** True while a Guard screen is in front (its windows stand in for the app behind it). */
        @Volatile
        internal var inFront = false

        private fun endOf(w: PoolWait): Instant = w.startedAt.plusSeconds(w.minutes * 60L)

        internal fun intent(context: Context, request: GuardRequest): Intent =
            Intent(context, BlockActivity::class.java)
                .putExtra(EXTRA_MODE, request.mode.name)
                .putExtra(EXTRA_APP, request.app)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        private fun requestOf(intent: Intent?): GuardRequest? {
            if (intent == null) return null
            val name = intent.getStringExtra(EXTRA_MODE) ?: return null
            val mode = GuardMode.entries.firstOrNull { it.name == name } ?: return null
            if (mode == GuardMode.KILL) return GuardRequest(mode, null)
            val app = intent.getStringExtra(EXTRA_APP)
            return if (app.isNullOrEmpty()) null else GuardRequest(mode, app)
        }
    }
}
