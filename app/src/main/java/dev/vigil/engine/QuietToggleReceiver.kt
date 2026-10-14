package dev.vigil.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.vigil.Graph

/** The "quiet" button on the always-on notification. */
class QuietToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Vigil.setQuiet(!Graph.state.current.quiet)
    }
}
