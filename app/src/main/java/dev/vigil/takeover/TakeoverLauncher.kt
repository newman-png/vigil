package dev.vigil.takeover

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.vigil.Graph
import dev.vigil.core.machine.Machine
import dev.vigil.core.run.Run
import dev.vigil.core.run.RunPhase
import dev.vigil.engine.Device
import dev.vigil.engine.Notifications
import dev.vigil.engine.Vigil
import dev.vigil.service.VigilService
import java.lang.ref.WeakReference

/**
 * Puts the takeover on screen for the run that owns it ([Vigil.activeRun]), and brings it back if the user leaves it
 * while its lock holds. Safety rules, in order:
 *  - never during a call (phone or VoIP, ringing included), and never over the phone, dialer or emergency apps;
 *    during a call nothing about it pops up at all;
 *  - after the takeover's CALL button it stays out of the way for [CALL_STEP_ASIDE_MS], even across a restart;
 *  - coming back is only allowed on an unlocked, awake phone whose front app is known and is not a call app, a
 *    whitelisted app (the APPS button's apps stay usable in every lock), VIGIL itself, the file picker or the share
 *    sheet; so it never covers the lock screen's emergency button;
 *  - only a stage-1 run comes back ([Machine.reasserts]); a stage-2 run shows once, then its notification leads back;
 *  - only a run that has just started (the engine's ShowTakeover) may turn the screen on, through the notification's
 *    full-screen intent; a takeover that was already on screen never wakes or lights up the phone;
 *  - the night lock never wakes or lights up the phone at all: it shows when the phone is in use (after GOING TO SLEEP,
 *    or after an unlock), silently.
 *
 * How it gets on screen: on a locked phone or a dark screen, the notification's full-screen intent opens it (the
 * system acts on that intent only when the notification is newly added, so it is posted again when the phone has
 * been locked since). On an unlocked phone in use, that intent only shows a banner, so the activity is started
 * directly, which Android allows from the background only with "display over other apps" (or while VIGIL itself
 * is in front). The system refuses a start silently, so a check a few seconds later falls back to the notification
 * and tries again. A periodic retry only updates a notification that is showing: one the user swiped away comes
 * back with the next screen-on or unlock, never every few seconds.
 *
 * Everything here runs on the main thread.
 */
object TakeoverLauncher {
    private const val TAG = "VigilTakeover"
    private const val REASSERT_DELAY_MS = 2_500L
    private const val RETRY_MS = 5_000L

    /** How long after a start to check that the takeover really appeared. */
    private const val VERIFY_MS = 3_000L

    /** Checks (and new tries) for a takeover not yet on screen while nothing blocks it. */
    private const val MAX_ATTEMPTS = 3

    /** The CPU stays up this long for a new takeover, until its activity starts (it covers every check above). */
    private const val WAKE_MS = 15_000L

    /** Posting is asynchronous: a notification posted this recently counts as showing before the system lists it. */
    private const val POST_SETTLE_MS = 2_000L
    private const val FRONT_LOOKBACK_MS = 2 * 60 * 60 * 1000L
    private const val CALL_STEP_ASIDE_MS = 10 * 60_000L

    /** Apps a waiting takeover never covers: the file picker and the share sheet (export). */
    private val PASS_THROUGH = setOf("com.android.documentsui", "com.google.android.documentsui", "com.android.intentresolver")

    /** True while [TakeoverActivity] is visible. */
    @Volatile
    var visible = false

    private var activity = WeakReference<TakeoverActivity>(null)

    /** The takeover notification as last posted, or null when none is known to be showing. */
    private var posted: Posted? = null

    /** When [posted] was posted (elapsed realtime). */
    private var postedAt = 0L

    /** The run that has just been given the screen and has not been on screen yet: only it may wake the screen. */
    private var unseen: String? = null

    /** Checks done for [unseen] (see [MAX_ATTEMPTS]). */
    private var attempts = 0

    /** The run last started directly and not yet seen on screen: the next try uses the notification. */
    private var startTried: String? = null

    /** When [startTried] was started (elapsed realtime). */
    private var startedAt = 0L

    private val handler = Handler(Looper.getMainLooper())

    /** Whether the scheduled check may add a notification that is not showing (a periodic retry never does). */
    private var retryMayAdd = false
    private val retry = Runnable {
        // Bring the engine up to date first (a lock may have ended since the last step); that closes or shows takeovers
        // through the usual effects.
        try {
            Vigil.onTick()
        } catch (e: Exception) {
            Log.e(TAG, "Engine tick failed", e)
        }
        show(Graph.app, mayAdd = retryMayAdd)
    }

    /**
     * What the takeover notification carries. [overLock]: a full-screen post made while the phone was locked or
     * dark, where the system opens the takeover; one made on an unlocked phone was only a banner.
     */
    private data class Posted(val runId: String, val fullScreen: Boolean, val overLock: Boolean)

    /** A run was just given the screen (the engine's ShowTakeover): show it now, turning the screen on. */
    fun ensureShowing(context: Context) {
        val app = context.applicationContext
        if (!onMain { ensureShowing(app) }) return
        val run = Vigil.activeRun()
        val id = run?.id
        if (id != null && id != unseen) {
            unseen = id
            attempts = 0
            startTried = null
            // After an alarm or a notification the phone may fall back asleep before the takeover is up (not for the
            // night lock, which waits for the phone to be used).
            if (!visible && run.phase != RunPhase.NIGHT) Device.keepAwake(app, WAKE_MS)
        }
        show(app, mayAdd = true)
    }

    /**
     * The screen came on, the phone was unlocked, or another app came to the front: show a waiting takeover again,
     * if the safety rules allow.
     */
    fun onScreenOn(context: Context) {
        val app = context.applicationContext
        if (!onMain { onScreenOn(app) }) return
        show(app, mayAdd = true)
    }

    /** The user left the takeover (Home, recents, another app, the power button) before its run was over. */
    fun onEscaped(context: Context) {
        // It was on screen, so from now on it is a waiting takeover, which never wakes the screen.
        unseen = null
        startTried = null
        retryIn(REASSERT_DELAY_MS, mayAdd = true)
    }

    fun register(takeover: TakeoverActivity) {
        activity = WeakReference(takeover)
    }

    /** The takeover is on screen: it has been seen, and the app is in the foreground, so make sure the service runs. */
    fun onVisible(context: Context) {
        unseen = null
        startTried = null
        handler.removeCallbacks(retry)
        Device.releaseAwake()
        VigilService.start(context)
    }

    /** The user tapped CALL: let the dialer and the call through. */
    fun stepAsideForCall() {
        Graph.settings.stepAsideUntilMs = System.currentTimeMillis() + CALL_STEP_ASIDE_MS
        handler.removeCallbacks(retry)
        // Keep the notification (it leads back), but without its full-screen intent.
        Vigil.activeRun()?.let { post(Graph.app, it, fullScreen = false, mayAlert = false) }
    }

    /** Suspends VIGIL for [minutes] (the kill switch, once its wait is over). */
    fun suspend(minutes: Long) {
        Vigil.suspend(minutes)
    }

    /** Nothing owns the screen any more: remove the notification and close a takeover left behind other apps. */
    fun onClosed(context: Context) {
        val app = context.applicationContext
        if (!onMain { onClosed(app) }) return
        handler.removeCallbacks(retry)
        posted = null
        unseen = null
        attempts = 0
        startTried = null
        Device.releaseAwake()
        try {
            app.getSystemService(NotificationManager::class.java)?.cancel(Notifications.ID_TAKEOVER)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not remove the takeover notification: ${e.message}")
        }
        activity.get()?.finishIfHidden()
    }

    /**
     * One attempt to get the active run's takeover on screen. [mayAdd]: a notification that is not showing may be
     * added (it can show a banner); false for the periodic retries.
     */
    private fun show(context: Context, mayAdd: Boolean) {
        // Also runs from the retry handler, where an uncaught failure would crash the process in the background.
        try {
            tryShow(context, mayAdd)
        } catch (e: Exception) {
            Log.e(TAG, "Could not show the takeover", e)
        }
    }

    private fun tryShow(context: Context, mayAdd: Boolean) {
        val now = System.currentTimeMillis()
        val run = Vigil.activeRun()
        if (run == null) {
            onClosed(context)
            return
        }
        val id = run.id
        val fresh = id == unseen
        val night = run.phase == RunPhase.NIGHT

        if (visible) {
            // Already on screen: the activity moves on to whatever owns the screen next by itself. Keep the
            // notification's title in step without alerting (and without its full-screen intent), and check once that
            // the activity was not just closing.
            post(context, run, fullScreen = false, mayAlert = false)
            if (fresh && attempts == 0) {
                attempts++
                retryIn(VERIFY_MS, mayAdd = true)
            }
            return
        }

        val awake = Device.isInteractive(context)
        val present = awake && !Device.isLocked(context)

        // Never during a call or right after CALL: no banner, no sound, nothing over the call screen.
        val inCall = Device.inCall(context)
        val stepAsideUntil = Graph.settings.stepAsideUntilMs
        if (inCall || now < stepAsideUntil) {
            post(context, run, fullScreen = false, mayAlert = false)
            // Try again shortly, or when the step aside ends; the screen-on and unlock broadcasts also bring it back.
            if (fresh || present) {
                retryIn(if (inCall) RETRY_MS else (stepAsideUntil - now).coerceIn(RETRY_MS, CALL_STEP_ASIDE_MS))
            }
            return
        }

        // On an unlocked, awake phone the front app matters: never cover the phone or dialer, even for a new run.
        val front = if (present) frontApp(context) else null
        if (Device.isCallApp(context, front)) {
            post(context, run, fullScreen = false, mayAlert = false)
            retryIn(RETRY_MS)
            return
        }
        val vigilInFront = front == context.packageName

        if (!fresh) {
            // A run that was already on screen. A stage-2 run is not re-asserted: its notification leads back.
            if (!Machine.reasserts(run)) {
                post(context, run, fullScreen = false, mayAlert = awake && mayAdd)
                return
            }
            // It comes back only where nothing allowed can be covered: an unlocked phone whose front app is known and
            // is not VIGIL itself, the file picker or share sheet, or a whitelisted app (usable in every lock).
            val allowedInFront = front == null || vigilInFront || front in PASS_THROUGH || Vigil.isWhitelisted(front)
            if (!present || allowedInFront) {
                post(context, run, fullScreen = false, mayAlert = awake && mayAdd)
                if (present) retryIn(RETRY_MS)
                return
            }
        }

        // Started directly a moment ago: give the activity time to appear before treating the start as refused
        // (an unlock and a run fired by it both land here within milliseconds).
        val sinceStart = SystemClock.elapsedRealtime() - startedAt
        if (startTried == id && sinceStart in 0L until VERIFY_MS) {
            retryIn(VERIFY_MS - sinceStart, mayAdd = true)
            return
        }

        val canStart = Settings.canDrawOverlays(context) || vigilInFront
        // Only when the front app is known: otherwise the call-app check above proved nothing.
        if (present && front != null && canStart && startTried != id) {
            // Unlocked and in use: the full-screen intent would only show a banner (over the takeover, once it is
            // up), so start the activity directly and use the notification only if it has not appeared shortly after.
            startTried = id
            startedAt = SystemClock.elapsedRealtime()
            start(context)
            retryIn(VERIFY_MS, mayAdd = true)
            return
        }

        // Locked, screen off, no permission to start it directly, or a direct start the system refused: the
        // notification. For a run not yet on screen it carries the full-screen intent, which opens the takeover by
        // itself on a locked phone or a dark screen; on an unlocked phone it is a banner to tap.
        post(context, run, fullScreen = fresh, mayAlert = awake && mayAdd, overLock = !present)
        // Also start it directly, but only where nothing can be covered: on a dark screen (with "display over other
        // apps" that works under the lock screen too, should the full-screen intent not be allowed), or in use with
        // a known front app. On an awake, locked phone the user may be typing an emergency number over the lock
        // screen, so there only the full-screen intent is used: the system shows it as a banner over that dialer.
        // A waiting takeover never gets here on a dark or locked phone (see above).
        if ((!awake && !night) || (present && front != null && canStart)) start(context)
        if (fresh && attempts < MAX_ATTEMPTS) {
            attempts++
            retryIn(VERIFY_MS, mayAdd = true)
        }
    }

    /**
     * The app in front: from usage stats when VIGIL may read them (they include VIGIL's own screens), else the last
     * app the Guard reported. Null when unknown.
     */
    private fun frontApp(context: Context): String? {
        Device.foregroundPackage(context, lookbackMs = FRONT_LOOKBACK_MS)?.let { return it }
        val s = Vigil.state.value
        return s.foreground.takeIf { s.screenOn }
    }

    private fun start(context: Context) {
        try {
            context.startActivity(TakeoverActivity.intent(context))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start the takeover directly: ${e.message}")
        }
    }

    private fun retryIn(ms: Long, mayAdd: Boolean = false) {
        handler.removeCallbacks(retry)
        retryMayAdd = mayAdd
        handler.postDelayed(retry, ms)
    }

    /** True on the main thread. Elsewhere it hands [block] to the main thread and returns false. */
    private fun onMain(block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return true
        handler.post { block() }
        return false
    }

    /**
     * Posts the takeover notification only when something about it changes, so it never re-pops as a banner by
     * accident. A full-screen post is always a newly added notification (cancel first), because the system acts
     * on that intent only then; it is posted again when the phone has been locked since the last one ([overLock]).
     * Other changes update the posted notification in place (it alerts only once). A notification that is gone
     * (Android 14+ lets the user swipe it away; the system may drop it) is added again only for a full-screen post
     * or with [mayAlert], since a new one can show a banner or light up the screen.
     */
    private fun post(context: Context, run: Run, fullScreen: Boolean, mayAlert: Boolean, overLock: Boolean = false) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // The night lock never opens itself over the lock screen and never alerts.
        val night = run.phase == RunPhase.NIGHT
        val full = fullScreen && !night
        val alert = mayAlert && !night
        val key = Posted(run.id, full, full && overLock)
        val settling = posted != null && SystemClock.elapsedRealtime() - postedAt < POST_SETTLE_MS
        val shown = settling || try {
            nm.activeNotifications?.any { it.id == Notifications.ID_TAKEOVER } == true
        } catch (e: RuntimeException) {
            posted != null
        }
        if (!shown) posted = null
        if (shown && posted == key) return
        if (!shown && !full && !alert) return
        try {
            if (full && shown) nm.cancel(Notifications.ID_TAKEOVER)
            val title = Vigil.file().find(run.protocol)?.title ?: run.protocol
            nm.notify(Notifications.ID_TAKEOVER, Notifications.takeover(context, title, full, night))
            posted = key
            postedAt = SystemClock.elapsedRealtime()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not post the takeover notification: ${e.message}")
        }
    }
}
