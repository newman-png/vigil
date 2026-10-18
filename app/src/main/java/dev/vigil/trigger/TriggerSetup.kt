package dev.vigil.trigger

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.vigil.engine.Device
import dev.vigil.ui.setup.SetupItem

/** Permissions the triggers need (adhan and call notifications, the front app, the call state), for the SETUP screen. */
object TriggerSetup {
    fun items(context: Context): List<SetupItem> = listOf(
        SetupItem(
            "NOTIFICATION ACCESS",
            "For the prayers: VIGIL reads your adhan app's notification (\"It's time for Isha\"). It also sees other apps' " +
                "call notifications, so a takeover never covers a WhatsApp call. Nothing leaves the phone.",
            notificationAccess(context),
            "OPEN",
        ) { open(context, notificationAccessIntent(context), Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        SetupItem(
            "USAGE ACCESS",
            "Lets a running protocol's takeover come back when you leave it: VIGIL must see that the phone app or a " +
                "whitelisted app is not in front.",
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
            "PHONE (CALL PAUSE)",
            "A call pauses the running protocol and its lock for the length of the call. Allow \"Phone\" under Permissions.",
            CallWatcher.hasPhoneState(context),
            "OPEN",
            optional = true,
        ) { open(context, appInfoIntent(context), Intent(Settings.ACTION_SETTINGS)) },
    )

    /** True when the adhan listener may read notifications. */
    fun notificationAccess(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.isNotificationListenerAccessGranted(ComponentName(context, AdhanListener::class.java))
    }

    /** The system page that grants the adhan listener notification access. */
    fun notificationAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, AdhanListener::class.java).flattenToString())

    /** True when exact alarms are allowed (USE_EXACT_ALARM grants them; a user may still switch them off). */
    fun exactAlarms(context: Context): Boolean = context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false

    /** VIGIL's App info page (permissions, battery). */
    fun appInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** Opens [intent] from any context, or [fallback] when the phone has no such page. */
    fun open(context: Context, intent: Intent, fallback: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            runCatching { context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
