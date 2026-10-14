package dev.vigil.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The adb escape hatch. Only the shell (adb) and the system can send it, because the manifest protects it
 * with android.permission.DUMP:
 *
 *   adb shell am broadcast -a dev.vigil.action.SUSPEND -n dev.vigil/.engine.KillSwitchReceiver --ei minutes 60
 *   adb shell am broadcast -a dev.vigil.action.RESUME -n dev.vigil/.engine.KillSwitchReceiver
 */
class KillSwitchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SUSPEND -> Vigil.suspend(intent.getIntExtra("minutes", 60).coerceIn(1, 7 * 24 * 60).toLong())
            ACTION_RESUME -> Vigil.resume()
        }
    }

    companion object {
        const val ACTION_SUSPEND = "dev.vigil.action.SUSPEND"
        const val ACTION_RESUME = "dev.vigil.action.RESUME"
    }
}
