package dev.vigil.engine

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import dev.vigil.Graph

/**
 * Do Not Disturb for the Jummah lock ([dev.vigil.core.machine.Effect.SetDnd]). On = priority only, so alarms and the
 * calls the user lets through in their own Do Not Disturb settings still come; off = back to whatever the user had
 * before (remembered in [dev.vigil.data.Settings]). It needs Do Not Disturb access (SETUP); without it nothing changes
 * and the engine journals that the lock showed without silencing the phone.
 */
object Dnd {
    private const val TAG = "VigilDnd"

    /** True when VIGIL may switch Do Not Disturb. */
    fun hasAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted ?: false

    /** The system page that grants Do Not Disturb access. */
    fun accessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    /**
     * Turns VIGIL's Do Not Disturb on or off. Off only undoes what VIGIL did (a Do Not Disturb the user set before, or
     * changed during the lock, is left alone). Returns false when Android did not allow it (no access): nothing changed.
     */
    fun set(context: Context, on: Boolean): Boolean {
        val settings = Graph.settings
        if (!on && !settings.dndByVigil) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.isNotificationPolicyAccessGranted) return false
        return try {
            if (on) {
                if (!settings.dndByVigil) settings.dndPrevious = nm.currentInterruptionFilter
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                settings.dndByVigil = true
            } else {
                val previous = settings.dndPrevious
                // Changed by the user meanwhile (Do Not Disturb off, or total silence): theirs, left as it is.
                if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                    nm.setInterruptionFilter(
                        if (previous == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) NotificationManager.INTERRUPTION_FILTER_ALL else previous,
                    )
                }
                settings.dndByVigil = false
            }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Do Not Disturb refused: ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Do Not Disturb refused: ${e.message}")
            false
        }
    }

    /**
     * On app start: Do Not Disturb as the engine wants it ([wanted]: a Jummah lock holds). One VIGIL turned on and no
     * lock needs any more (the process died before the lock's end was carried out) goes off.
     */
    fun sync(context: Context, wanted: Boolean) {
        val mine = Graph.settings.dndByVigil
        if (wanted != mine) set(context, wanted)
    }
}
