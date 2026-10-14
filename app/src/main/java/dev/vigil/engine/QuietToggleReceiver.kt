package dev.vigil.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.vigil.Graph

/** The "quiet" button on the always-on notification. */
class QuietToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A failure here must not crash the process in the background; the button can simply be tapped again.
        try {
            Vigil.setQuiet(!Graph.state.current.quiet)
        } catch (e: Exception) {
            Log.e(TAG, "Toggling quiet mode failed", e)
        }
    }

    private companion object {
        const val TAG = "VigilQuiet"
    }
}
