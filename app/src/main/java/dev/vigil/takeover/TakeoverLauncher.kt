package dev.vigil.takeover

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import dev.vigil.Graph
import dev.vigil.core.engine.Engine
import dev.vigil.engine.Device
import dev.vigil.engine.Notifications
import dev.vigil.engine.Vigil
import dev.vigil.service.VigilService
import java.lang.ref.WeakReference

/**
 * Puts the takeover on screen whenever the engine has one active, and brings it back if the user leaves
 * it unfinished. Safety rules, in order:
 *  - never during a call (phone or VoIP, ringing included), and never over the phone, dialer or emergency apps;
 *  - after the takeover's CALL button it stays out of the way for [CALL_STEP_ASIDE_MS], even across a restart;
 *  - coming back is only allowed on an unlocked, awake phone whose front app is known and is not a call app,
 *    VIGIL itself or the file picker (so it never covers the lock screen's emergency button, needs usage
 *    access to check the front app, and lets the user import and export);
 *  - only a newly fired directive may turn the screen on;
 *  - a takeover closes itself after [Engine.MAX_TAKEOVER_MINUTES] (Engine.refresh, also run here).
 */
object TakeoverLauncher {
    private const val TAG = "VigilTakeover"
    private const val REASSERT_DELAY_MS = 2_500L
    private const val RETRY_MS = 5_000L
    private const val CALL_STEP_ASIDE_MS = 10 * 60_000L
    private val FILE_PICKERS = setOf("com.android.documentsui", "com.google.android.documentsui")

    /** True while [TakeoverActivity] is visible. */
    @Volatile
    var visible = false

    private var activity = WeakReference<TakeoverActivity>(null)

    /** The takeover notification currently posted: takeover id and whether it carries the full-screen intent. */
    private var posted: Pair<String, Boolean>? = null

    private val handler = Handler(Looper.getMainLooper())
    private val retryNew = Runnable { show(Graph.app, newDirective = true) }
    private val reassert = Runnable { show(Graph.app, newDirective = false) }

    /** A directive just fired (or the engine promoted the next one): show it now, turning the screen on. */
    fun ensureShowing(context: Context) = show(context.applicationContext, newDirective = true)

    /** The screen came on or the phone was unlocked: show a waiting takeover again, if the safety rules allow. */
    fun onScreenOn(context: Context) = show(context.applicationContext, newDirective = false)

    /** The user left the takeover (Home, recents, another app) before finishing it. */
    fun onEscaped(context: Context) {
        handler.removeCallbacks(reassert)
        handler.postDelayed(reassert, REASSERT_DELAY_MS)
    }

    fun register(takeover: TakeoverActivity) {
        activity = WeakReference(takeover)
    }

    /** The takeover is on screen: the app is in the foreground, so make sure the service runs. */
    fun onVisible(context: Context) {
        VigilService.start(context)
    }

    /** The user tapped CALL: let the dialer and the call through. */
    fun stepAsideForCall() {
        Graph.settings.stepAsideUntilMs = System.currentTimeMillis() + CALL_STEP_ASIDE_MS
        handler.removeCallbacks(reassert)
        repost(Graph.app, fullScreen = false)
    }

    /** The hidden kill switch on the takeover (seven taps on the eye). */
    fun suspend(minutes: Long) = Vigil.suspend(minutes)

    /** Nothing is active any more: remove the notification and close a takeover left behind other apps. */
    fun onClosed(context: Context) {
        handler.removeCallbacks(retryNew)
        handler.removeCallbacks(reassert)
        posted = null
        context.getSystemService(NotificationManager::class.java)?.cancel(Notifications.ID_TAKEOVER)
        activity.get()?.finishIfHidden()
    }

    private fun show(context: Context, newDirective: Boolean) {
        val now = System.currentTimeMillis()
        // Housekeeping first, so a takeover past its hard maximum is closed rather than shown again.
        val env = Graph.env()
        Graph.state.update { Engine.refresh(it, env) }
        val ticket = Graph.state.current.active
        if (ticket == null || Graph.settings.isSuspended(now)) {
            onClosed(context)
            return
        }
        val inCall = Device.inCall(context)
        val steppingAside = now < Graph.settings.stepAsideUntilMs
        // The full-screen intent wakes the phone, so only a new directive outside a call gets one.
        post(context, ticket.takeoverId, fullScreen = newDirective && !inCall && !steppingAside)

        if (visible) return
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val awake = Device.isInteractive(context)
        // On an unlocked, awake phone the front app matters: never cover the phone or dialer, even for a new
        // directive. A waiting takeover also leaves VIGIL's own screens and the file picker alone (for importing
        // and exporting), and needs to know the front app at all before it comes back.
        val front = if (awake && !locked) Device.foregroundPackage(context, lookbackMs = 2 * 60 * 60 * 1000L) else null
        val blocked = inCall || steppingAside || Device.isCallApp(context, front) ||
            (!newDirective && (locked || !awake || front == null || front == context.packageName || front in FILE_PICKERS))
        if (blocked) {
            // Try again shortly; the screen-on and unlock broadcasts also bring it back.
            val again = if (newDirective) retryNew else reassert
            handler.removeCallbacks(again)
            if (newDirective || (Device.isInteractive(context) && !locked)) handler.postDelayed(again, RETRY_MS)
            return
        }
        // Starting an activity from the background needs "display over other apps"; without it the
        // full-screen notification above still opens a new takeover when the phone is locked.
        if (Settings.canDrawOverlays(context) || newDirective) {
            try {
                context.startActivity(TakeoverActivity.intent(context))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not start the takeover directly: ${e.message}")
            }
        }
    }

    /**
     * Posts the takeover notification only when something about it changes, so it never re-pops as a banner.
     * A different takeover is posted fresh (cancel first), because the system only acts on the full-screen
     * intent of a newly added notification.
     */
    private fun post(context: Context, takeoverId: String, fullScreen: Boolean) {
        val key = takeoverId to fullScreen
        if (posted == key) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val title = Engine.directiveFor(Graph.env(), Graph.state.current.active ?: return)?.title ?: "Directive"
        if (posted?.first != takeoverId) nm.cancel(Notifications.ID_TAKEOVER)
        runCatching { nm.notify(Notifications.ID_TAKEOVER, Notifications.takeover(context, title, fullScreen)) }
        posted = key
    }

    private fun repost(context: Context, fullScreen: Boolean) {
        val id = Graph.state.current.active?.takeoverId ?: return
        post(context, id, fullScreen)
    }
}
