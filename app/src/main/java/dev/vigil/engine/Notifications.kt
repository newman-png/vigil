package dev.vigil.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import dev.vigil.R
import dev.vigil.takeover.TakeoverActivity
import dev.vigil.ui.MainActivity

object Notifications {
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_TAKEOVER = "takeover"
    const val CHANNEL_PLAN = "plan"
    const val ID_SERVICE = 1
    const val ID_TAKEOVER = 2
    const val ID_PLAN_COMPLETE = 3

    /** Intent extra MainActivity reads to open a screen directly; [OPEN_PLAN_COMPLETE] opens PLAN COMPLETE. */
    const val EXTRA_OPEN = "open"
    const val OPEN_PLAN_COMPLETE = "plan_complete"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "VIGIL is watching", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The always-on notification that keeps VIGIL running."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TAKEOVER, "Directives", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Opens the takeover when a directive fires. The takeover makes its own sound."
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            // Low importance: it never pulses or lights a dark screen (only a new directive may wake the phone).
            NotificationChannel(CHANNEL_PLAN, "Plan complete", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Says when the week's plan has ended and its bundle is ready to export."
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    /** The bundle was exported or a new plan imported: the export prompt goes away. */
    fun cancelPlanComplete(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_PLAN_COMPLETE)
    }

    /** The plan's week is over: export the bundle. Silent, like everything VIGIL posts outside a takeover. */
    fun planComplete(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN, OPEN_PLAN_COMPLETE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_PLAN)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("VIGIL · PLAN COMPLETE")
            .setContentText("Export the week's bundle for your next plan. The fallback plan runs until then.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ID_PLAN_COMPLETE, notification) }
    }

    fun service(context: Context, text: String, quiet: Boolean): Notification {
        val toggle = PendingIntent.getBroadcast(
            context, 2, Intent(context, QuietToggleReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_stat_eye),
            if (quiet) "END QUIET MODE" else "QUIET MODE",
            toggle,
        ).build()
        return Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle(if (quiet) "VIGIL · QUIET" else "VIGIL")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp(context))
            .addAction(action)
            .build()
    }

    /**
     * The notification behind every takeover. With [fullScreen] the system opens the takeover by itself when
     * the phone is locked or the screen is off. During a call it is posted without that, so the takeover
     * never covers the call screen.
     */
    fun takeover(context: Context, title: String, fullScreen: Boolean): Notification {
        val open = PendingIntent.getActivity(
            context, 1, TakeoverActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_TAKEOVER)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("VIGIL · ${title.uppercase()}")
            .setContentText("A directive is waiting. Tap to open it.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply { if (fullScreen) setFullScreenIntent(open, true) }
            .build()
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
