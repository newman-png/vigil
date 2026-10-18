package dev.vigil.guard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.vigil.core.machine.Machine
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.engine.Device
import dev.vigil.engine.Vigil
import dev.vigil.takeover.TakeoverLauncher
import java.lang.ref.WeakReference

/**
 * The Guard: VIGIL's accessibility service, the user's own enforcer for the pool (the distracting apps they chose,
 * design sections 1 and 3). It only learns which app's window comes to the front: it cannot read what is on the
 * screen (canRetrieveWindowContent is off). Every change of the app in front goes to the engine
 * ([Vigil.onForeground]); when a pool app comes to the front the Guard asks the engine ([Vigil.poolDecision]) and
 * does what it says: nothing, the wait screen, or home plus the blocked screen ([BlockActivity]). The engine's Kick (a
 * session that ended: heavy use, a window that closed, a lock that began) closes the pool app the same way. The Guard
 * decides nothing itself.
 *
 * Safety rules, kept from v1.1:
 *  - calls are never blocked: nothing happens during a call (ringing included); it looks again once the call is over;
 *  - never over the phone, dialer or a call app (pool apps never are);
 *  - nothing on a locked or dark phone: it never wakes the screen and never covers the lock screen's emergency button;
 *  - VIGIL's own screens (home, the takeover) are never closed, and they never count as an app.
 *
 * Usage access, when granted, is a backstop for window changes the accessibility events missed (a dialog closing over
 * a pool app, a phone unlocked into one).
 *
 * Everything here runs on the main thread.
 */
class GuardService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    /** The app in front as the Guard last saw it: a package, [OWN] for VIGIL's own screens, or null (not known yet). */
    private var current: String? = null

    /** A blocked screen waiting for the home screen to come to the front (after going home). */
    private var pending: Pending? = null

    /** A pool app left to the takeover to cover (a run owns the screen); checked once the takeover had time to appear. */
    private var leftToTakeover: String? = null

    /** A pool app the wait screen was just opened for; checked once the screen had time to appear. */
    private var gated: String? = null

    private var receiverRegistered = false

    /** Window classes looked up: true = an app's screen (an activity), false = a dialog, popup or panel over one. */
    private val windowKinds = HashMap<String, Boolean>()

    private var keyboard: String? = null
    private var keyboardReadAt = 0L

    private var homeApp: String? = null
    private var homeReadAt = 0L

    /** Recent home presses (elapsed realtime), for the loop breaker. */
    private val homePresses = ArrayDeque<Long>()

    /** Closing pool apps is paused until then (elapsed realtime): something kept bringing one back. */
    private var pausedUntil = 0L

    private data class Pending(val app: String, val heavy: Boolean)

    /** The engine's Kick, registered with [Vigil.onKick] while the Guard runs (Vigil calls it on the main thread). */
    private val kick: (String?, BlockReason) -> Unit = { app, reason -> safely("Kick") { onKick(app, reason) } }

    private val recheck = Runnable { safely("Recheck") { current?.takeIf { it != OWN }?.let { enforce(it) } } }
    private val showPending = Runnable { safely("Blocked screen") { flushPending() } }
    private val takeoverCheck = Runnable { safely("Takeover check") { checkTakeover() } }
    private val gateCheck = Runnable { safely("Wait screen check") { checkGate() } }
    private val verify = object : Runnable {
        override fun run() {
            safely("Front check") { verifyFront() }
            handler.postDelayed(this, VERIFY_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            safely("Screen event ${intent.action}") {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_USER_PRESENT -> onPresent()
                    // Without a lock screen, or with one that is already gone, the phone is usable at once.
                    Intent.ACTION_SCREEN_ON -> if (Device.isPresent(context)) onPresent()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        connected = true
        current = null
        if (!receiverRegistered) {
            try {
                // Protected broadcasts only the system sends; USER_PRESENT comes from System UI, which a receiver
                // registered as not exported does not get on every phone (as in VigilService).
                registerReceiver(
                    screenReceiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(Intent.ACTION_USER_PRESENT)
                    },
                    Context.RECEIVER_EXPORTED,
                )
                receiverRegistered = true
            } catch (e: RuntimeException) {
                // The front check below still notices a pool app after an unlock, only later.
                Log.e(TAG, "Could not watch the screen", e)
            }
        }
        Vigil.onKick = kick
        handler.removeCallbacks(verify)
        handler.post(verify)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString()
        // A failure here must not crash the process (and with it the service, the takeover and the alarms).
        safely("Window change") { onWindow(pkg, cls) }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        stop()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun stop() {
        connected = false
        if (instance.get() === this) instance = WeakReference(null)
        if (Vigil.onKick === kick) Vigil.onKick = null
        handler.removeCallbacksAndMessages(null)
        pending = null
        leftToTakeover = null
        gated = null
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not stop watching the screen: ${e.message}")
            }
            receiverRegistered = false
        }
    }

    // ------------------------------------------------------------------ the app in front

    /** A window of [pkg] (class [cls]) came to the front. */
    private fun onWindow(pkg: String, cls: String?) {
        if (pkg == packageName) {
            // A Guard screen stands in front of the pool app it is about: for the pool, that app is still in front.
            if (cls == BlockActivity::class.java.name || BlockActivity.inFront) return
            // VIGIL's own screens (home, the takeover): none of the user's apps is in front.
            if (current != OWN) {
                current = OWN
                if (Device.isInteractive(this)) report(null)
            }
            return
        }
        if (ignored(pkg, cls) || pkg == current) return
        val pool = isPool(pkg)
        // A dialog, popup or panel of another app over the one in front is not an app switch; a pool app always is.
        if (!pool && !isAppScreen(pkg, cls)) return
        switchTo(pkg, pool)
    }

    /** [pkg] is the app in front now: tell the engine, then act on a pool app. */
    private fun switchTo(pkg: String, pool: Boolean) {
        current = pkg
        // While the screen is off the app in front is remembered for the next unlock (onPresent).
        if (!Device.isInteractive(this)) return
        report(pkg)
        if (pool) enforce(pkg) else flushPending()
    }

    /** Tells the engine which app is in front (null = none of the user's apps), once per change. */
    private fun report(pkg: String?) {
        if (Vigil.state.value.foreground == pkg) return
        Vigil.onForeground(pkg)
    }

    /** A pool app (the engine's list and every browser), but never the home screen app, which must always work. */
    private fun isPool(pkg: String): Boolean = Vigil.isPoolApp(pkg) && pkg != homePackage()

    /** The default home screen app, read again every few minutes. */
    private fun homePackage(): String? {
        val now = SystemClock.elapsedRealtime()
        if (homeReadAt == 0L || now - homeReadAt > HOME_TTL_MS) {
            homeApp = try {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                packageManager
                    .resolveActivity(home, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
                    ?.activityInfo?.packageName
                    ?.takeIf { it != "android" }
            } catch (e: RuntimeException) {
                null
            }
            homeReadAt = now
        }
        return homeApp
    }

    /** System UI (the shade, the lock screen, the volume panel) and keyboards are never "the app in front". */
    private fun ignored(pkg: String, cls: String?): Boolean =
        pkg == SYSTEM_UI || cls == KEYBOARD_WINDOW || pkg == keyboardPackage()

    /** The current keyboard's package, read again every minute. */
    private fun keyboardPackage(): String? {
        val now = SystemClock.elapsedRealtime()
        if (keyboardReadAt == 0L || now - keyboardReadAt > KEYBOARD_TTL_MS) {
            keyboard = try {
                Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
            } catch (e: RuntimeException) {
                null
            }
            keyboardReadAt = now
        }
        return keyboard
    }

    /**
     * True when window class [cls] of [pkg] is one of its activities (a screen of its own), false for a dialog, popup
     * or panel. A package VIGIL cannot look into, or a window without a class, counts as a screen.
     */
    private fun isAppScreen(pkg: String, cls: String?): Boolean {
        if (cls.isNullOrEmpty()) return true
        val key = "$pkg/$cls"
        windowKinds[key]?.let { return it }
        val pm = packageManager
        val screen = try {
            pm.getActivityInfo(
                ComponentName(pkg, cls),
                PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
            )
            true
        } catch (e: PackageManager.NameNotFoundException) {
            // Not one of its activities, or a package hidden from VIGIL: only the first is a window over another app.
            !canSee(pm, pkg)
        } catch (e: RuntimeException) {
            true
        }
        if (windowKinds.size >= WINDOW_KINDS_MAX) windowKinds.clear()
        windowKinds[key] = screen
        return screen
    }

    private fun canSee(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: RuntimeException) {
        false
    }

    /**
     * Backstop (with usage access): the app most recently brought to the front, when the window events missed it.
     * VIGIL's own screens are left to the events (a Guard screen stands in for the app behind it).
     */
    private fun verifyFront() {
        if (!Device.isPresent(this) || !Device.hasUsageAccess(this)) return
        val last = Device.foregroundPackage(this, lookbackMs = FRONT_LOOKBACK_MS) ?: return
        if (last == packageName || last == current || ignored(last, null)) return
        switchTo(last, isPool(last))
    }

    // ------------------------------------------------------------------ screen

    private fun onScreenOff() {
        handler.removeCallbacks(recheck)
        handler.removeCallbacks(showPending)
        handler.removeCallbacks(takeoverCheck)
        handler.removeCallbacks(gateCheck)
        pending = null
        leftToTakeover = null
        gated = null
        report(null)
    }

    /** The phone is usable again: the app that was in front is in front again. */
    private fun onPresent() {
        val app = current?.takeIf { it != OWN }
        report(app)
        if (app != null) enforce(app)
    }

    // ------------------------------------------------------------------ enforcing

    /**
     * Pool app [pkg] is in front: does what the engine's decision says. [kicked] = the engine's Kick reason, when this
     * comes from one (heavy use shows "ENOUGH FOR NOW").
     */
    private fun enforce(pkg: String, kicked: BlockReason? = null) {
        if (!Device.isPresent(this) || !isPool(pkg) || Device.isCallApp(this, pkg)) return
        if (Device.inCall(this)) {
            // Calls are never blocked: look again once the call is over.
            recheckIn(CALL_RECHECK_MS)
            return
        }
        when (Vigil.poolDecision(pkg)) {
            PoolDecision.Allowed -> Unit
            is PoolDecision.Wait -> {
                GuardScreens.showWait(this, pkg)
                gated = pkg
                handler.removeCallbacks(gateCheck)
                handler.postDelayed(gateCheck, GATE_CHECK_MS)
            }
            is PoolDecision.Blocked -> close(pkg, heavy = kicked == BlockReason.HEAVY_USE, fromKick = kicked != null)
        }
    }

    /** The wait screen should be up by now; if the system did not show it, the pool app does not stay open either. */
    private fun checkGate() {
        val pkg = gated ?: return
        gated = null
        if (BlockActivity.inFront || pkg != current || !Device.isPresent(this) || Device.inCall(this)) return
        if (Vigil.poolDecision(pkg) is PoolDecision.Allowed) return
        Log.w(TAG, "The wait screen for $pkg did not appear; going home")
        goHome()
    }

    /**
     * Closes blocked pool app [pkg]: home, then the blocked screen over it. [fromKick]: the engine ended the session, in
     * a step that may also have given the screen to a new run.
     */
    private fun close(pkg: String, heavy: Boolean, fromKick: Boolean) {
        val run = Vigil.activeRun()
        if (!heavy && run != null && (fromKick || Machine.reasserts(run))) {
            // A run owns the screen: its takeover is coming up (a new run) or comes back over the pool app by itself
            // (stage 1, TakeoverLauncher), and a home press now would only hide it again. Make sure it did.
            leftToTakeover = pkg
            handler.removeCallbacks(takeoverCheck)
            handler.postDelayed(takeoverCheck, TAKEOVER_CHECK_MS)
            return
        }
        goHomeThenShow(pkg, heavy)
    }

    /**
     * Sends [pkg] home and shows the blocked screen once the home screen is in front (a screen opened before the home
     * press is handled would be sent home with the app).
     */
    private fun goHomeThenShow(pkg: String, heavy: Boolean) {
        if (!goHome()) return
        pending = Pending(pkg, heavy)
        handler.removeCallbacks(showPending)
        handler.postDelayed(showPending, HOME_SETTLE_MS)
    }

    private fun flushPending() {
        val p = pending ?: return
        pending = null
        handler.removeCallbacks(showPending)
        // Not over a call, and not over VIGIL's own screens (the takeover explains itself).
        if (!Device.isPresent(this) || Device.inCall(this) || current == OWN || Device.isCallApp(this, current)) return
        GuardScreens.showBlocked(this, p.app, p.heavy)
    }

    /** The takeover should have covered [leftToTakeover] by now; if it did not (refused, stepping aside), close it. */
    private fun checkTakeover() {
        val pkg = leftToTakeover ?: return
        leftToTakeover = null
        // Once the takeover is up, its window made VIGIL the app in front.
        if (pkg != current || TakeoverLauncher.visible || !Device.isPresent(this) || Device.inCall(this)) return
        if (Vigil.poolDecision(pkg) !is PoolDecision.Blocked) return
        goHomeThenShow(pkg, heavy = false)
    }

    /** The engine ended the pool session ([reason]): close the pool app in front, if one is. */
    private fun onKick(app: String?, reason: BlockReason) {
        if (!connected) return
        val front = frontApp() ?: return
        if (front == OWN) return
        if (front != app) Log.i(TAG, "Kick for $app while $front is in front ($reason)")
        enforce(front, kicked = reason)
    }

    /** The app in front now: from usage access when granted (it is exact after the fact), else the last window seen. */
    private fun frontApp(): String? {
        val known = current
        if (!Device.hasUsageAccess(this)) return known
        val last = Device.foregroundPackage(this, lookbackMs = KICK_LOOKBACK_MS) ?: return known
        return when {
            last == packageName -> OWN
            ignored(last, null) -> known
            else -> last
        }
    }

    /** Presses home; false when the loop breaker holds it back. */
    private fun goHome(): Boolean {
        if (loopBroken()) return false
        if (performGlobalAction(GLOBAL_ACTION_HOME)) return true
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not go to the home screen: ${e.message}")
        }
        return true
    }

    /**
     * The loop breaker: when pool apps had to be sent home more than [LOOP_MAX] times within [LOOP_SPAN_MS] (an app
     * that keeps coming back, a home screen mistaken for a pool app), the Guard stops pressing home for
     * [LOOP_PAUSE_MS] and then looks again, so the phone never gets stuck in a loop.
     */
    private fun loopBroken(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < pausedUntil) return true
        while (homePresses.isNotEmpty() && now - homePresses.first() > LOOP_SPAN_MS) homePresses.removeFirst()
        homePresses.addLast(now)
        if (homePresses.size <= LOOP_MAX) return false
        Log.w(TAG, "Sent pool apps home ${homePresses.size} times in ${LOOP_SPAN_MS / 1000} s; pausing for ${LOOP_PAUSE_MS / 1000} s")
        homePresses.clear()
        pausedUntil = now + LOOP_PAUSE_MS
        recheckIn(LOOP_PAUSE_MS + RECHECK_MS)
        return true
    }

    private fun recheckIn(ms: Long) {
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, ms)
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "$what failed", e)
        }
    }

    companion object {
        private const val TAG = "VigilGuard"

        /** [current] while one of VIGIL's own screens is in front (never a package name: those have no '<'). */
        private const val OWN = "<vigil>"

        private const val SYSTEM_UI = "com.android.systemui"
        private const val KEYBOARD_WINDOW = "android.inputmethodservice.SoftInputWindow"

        /** The usage-access backstop runs this often while the phone is in use. */
        private const val VERIFY_MS = 15_000L
        private const val FRONT_LOOKBACK_MS = 10 * 60_000L
        private const val KICK_LOOKBACK_MS = 2 * 60 * 60_000L

        /** During a call the Guard looks again this often, so it acts once the call is over. */
        private const val CALL_RECHECK_MS = 5_000L

        /** After a Guard screen closes: time for the next window to come to the front before looking again. */
        private const val RECHECK_MS = 700L

        /** The blocked screen opens at the latest this long after the home press. */
        private const val HOME_SETTLE_MS = 700L

        /** How long the takeover gets to come back over a pool app before the Guard closes the app itself. */
        private const val TAKEOVER_CHECK_MS = 2_500L

        /** How long the wait screen gets to appear over a pool app before the Guard sends the app home. */
        private const val GATE_CHECK_MS = 2_000L

        private const val KEYBOARD_TTL_MS = 60_000L
        private const val HOME_TTL_MS = 5 * 60_000L

        /** The loop breaker: more than this many home presses within the span pauses closing for a while. */
        private const val LOOP_MAX = 5
        private const val LOOP_SPAN_MS = 15_000L
        private const val LOOP_PAUSE_MS = 30_000L
        private const val WINDOW_KINDS_MAX = 256

        /** True while the Guard runs (the accessibility service is connected). */
        @Volatile
        var connected = false
            private set

        private var instance = WeakReference<GuardService>(null)

        /**
         * A Guard screen was left or closed (main thread): the app now in front may be a pool app again (the user went
         * back to it), so the Guard looks again shortly.
         */
        internal fun recheckSoon() {
            instance.get()?.recheckIn(RECHECK_MS)
        }
    }
}
