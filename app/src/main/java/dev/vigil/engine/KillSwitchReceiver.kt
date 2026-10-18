package dev.vigil.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.vigil.takeover.TakeoverLauncher

/**
 * The adb escape hatch (kept from v1). Only the shell (adb) and the system can send it, because the manifest protects
 * it with android.permission.DUMP:
 *
 *   adb shell am broadcast -a dev.vigil.action.SUSPEND -n dev.vigil/.engine.KillSwitchReceiver --ei minutes 60
 *   adb shell am broadcast -a dev.vigil.action.RESUME -n dev.vigil/.engine.KillSwitchReceiver
 *
 * Suspended = no takeovers and the pool open (the engine's kill switch, without the 5-minute wait).
 */
class KillSwitchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                ACTION_SUSPEND -> Vigil.suspend(intent.getIntExtra("minutes", 60).coerceIn(1, MAX_MINUTES).toLong())
                ACTION_RESUME -> Vigil.resume()
            }
        } catch (e: Exception) {
            // The escape hatch must work even when the engine step fails: at least remove the takeover notification
            // and a takeover left behind other apps.
            Log.e(TAG, "Handling ${intent.action} failed", e)
            if (intent.action == ACTION_SUSPEND) runCatching { TakeoverLauncher.onClosed(context) }
        }
    }

    companion object {
        const val ACTION_SUSPEND = "dev.vigil.action.SUSPEND"
        const val ACTION_RESUME = "dev.vigil.action.RESUME"
        private const val TAG = "VigilKillSwitch"
        private const val MAX_MINUTES = 7 * 24 * 60
    }
}
