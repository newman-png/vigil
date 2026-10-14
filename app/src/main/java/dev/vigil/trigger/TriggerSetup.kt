package dev.vigil.trigger

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.vigil.engine.Device
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
            SetupItem(
                "USAGE ACCESS",
                "For app-usage directives (e.g. TikTok opened too often). Also needed for an unfinished takeover to come back: " +
                    "VIGIL must be able to see that the phone app is not in front.",
                Device.hasUsageAccess(context),
                "OPEN",
            ) {
                open(
                    context,
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")),
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                )
            },
            SetupItem(
                "ACCESSIBILITY (INSTANT APP DETECTION)",
                "Optional. Notices app switches instantly instead of within ~5 s. It only sees which app is in front, never screen content.",
                accessibilityEnabled(context),
                "OPEN",
                optional = true,
            ) {
                open(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            },
        )
    }

    fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(context, UsageAccessibilityService::class.java)
        return enabled.split(':').any { it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true) }
    }

    internal fun open(context: Context, intent: Intent, fallback: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            runCatching { context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
