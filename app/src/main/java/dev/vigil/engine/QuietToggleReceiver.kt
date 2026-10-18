package dev.vigil.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** The QUIET button on the always-on notification: switches QUIET mode (every takeover silent) on or off. */
class QuietToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A failure here must not crash the process in the background; the button can simply be tapped again.
        try {
            Vigil.setQuiet(!Vigil.state.value.toggles.quiet)
        } catch (e: Exception) {
            Log.e(TAG, "Toggling quiet mode failed", e)
        }
    }

    private companion object {
        const val TAG = "VigilQuiet"
    }
}
