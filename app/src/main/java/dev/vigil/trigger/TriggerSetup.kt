package dev.vigil.trigger

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.vigil.ui.setup.SetupItem

/** Permissions the triggers need, shown on the setup screen. */
object TriggerSetup {
    fun items(context: Context): List<SetupItem> {
        val listener = ComponentName(context, AdhanListener::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        return listOf(
            SetupItem(
                "NOTIFICATION ACCESS",
                "For adhan directives: VIGIL reads your adhan app's notification (\"It's time for Isha\"). Nothing leaves the phone.",
                nm?.isNotificationListenerAccessGranted(listener) ?: false,
                "OPEN",
            ) {
                open(
                    context,
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString()),
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                )
            },
        )
    }

    internal fun open(context: Context, intent: Intent, fallback: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            runCatching { context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
