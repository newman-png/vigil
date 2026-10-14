package dev.vigil.takeover

import android.content.Context
import java.lang.ref.WeakReference

/** Puts the takeover on screen when the engine says one is active. */
object TakeoverLauncher {
    /** True while [TakeoverActivity] is visible. */
    @Volatile
    var visible = false

    private var activity = WeakReference<TakeoverActivity>(null)

    fun ensureShowing(context: Context) {
        if (visible) return
        context.startActivity(TakeoverActivity.intent(context))
    }

    fun register(takeover: TakeoverActivity) {
        activity = WeakReference(takeover)
    }

    /** The takeover is on screen. */
    fun onVisible(context: Context) = Unit

    /** The user left the takeover (Home, recents, another app) before finishing it. */
    fun onEscaped(context: Context) = Unit

    /** The user tapped CALL: let the dialer through. */
    fun stepAsideForCall() = Unit

    /** The hidden kill switch arrives with the background service (next milestone). */
    fun suspend(minutes: Long) = Unit

    /** Nothing is active any more. */
    fun onClosed(context: Context) {
        activity.get()?.finishIfHidden()
    }
}
