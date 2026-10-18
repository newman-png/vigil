package dev.vigil.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import dev.vigil.R
import dev.vigil.core.machine.Effect
import dev.vigil.core.machine.NotifyKind
import dev.vigil.takeover.TakeoverActivity
import dev.vigil.ui.MainActivity

/**
 * Every notification VIGIL posts: the always-on one, the one behind a takeover, and the engine's notices
 * ([Effect.Notify]: heads-ups, class cards, CONFIRM prompts, stage-3 reminders, runs without a screen lock, window
 * warnings). Only a takeover that has just started and a stage-3 reminder or warning that is not silent may alert;
 * everything else is posted on low-importance channels, which never sound or light up a dark screen.
 */
object Notifications {
    private const val TAG = "VigilNotify"

    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_TAKEOVER = "takeover"
    const val CHANNEL_HEADS_UP = "heads_up"
    const val CHANNEL_CARDS = "cards"
    const val CHANNEL_REMINDERS = "reminders"

    /** v1 channels that no longer exist. */
    private val OLD_CHANNELS = listOf("plan")

    const val ID_SERVICE = 1
    const val ID_TAKEOVER = 2

    /** Every engine notice is posted with this id and the core's notice id as its tag. */
    const val ID_NOTICE = 10

    /** A heads-up goes away by itself after this long, in case its adhan never comes. */
    const val HEADS_UP_TIMEOUT_MS = 30 * 60_000L

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "VIGIL is watching", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The always-on notification that keeps VIGIL running."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TAKEOVER, "Protocols", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Opens the takeover when a protocol starts. The takeover makes its own sound."
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HEADS_UP, "Prayer heads-up", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The quiet notice a few minutes before a prayer."
                setSound(null, null)
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CARDS, "Cards", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Silent cards: a prayer during class, prayers waiting for CONFIRM, protocols without a takeover."
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Stage-3 reminders and a prayer window about to close. Silent while QUIET is on."
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        OLD_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }
    }

    /** The always-on notification, with the QUIET switch. */
    fun service(context: Context, text: String, quiet: Boolean): Notification {
        val toggle = PendingIntent.getBroadcast(
            context, 2, Intent(context, QuietToggleReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_stat_eye),
            if (quiet) "END QUIET" else "QUIET",
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
     * the phone is locked or the screen is off (only for a run that has just started). During a call it is posted
     * without that, so the takeover never covers the call screen.
     */
    fun takeover(context: Context, title: String, fullScreen: Boolean): Notification {
        val open = PendingIntent.getActivity(
            context, 1, TakeoverActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_TAKEOVER)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("VIGIL · ${title.uppercase()}")
            .setContentText("A protocol is running. Tap to open it.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply { if (fullScreen) setFullScreenIntent(open, true) }
            .build()
    }

    /** Shows (or replaces) one engine notice. */
    fun show(context: Context, notice: Effect.Notify) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val alerting = !notice.silent && (notice.kind == NotifyKind.REMINDER || notice.kind == NotifyKind.WARNING)
        val channel = when {
            notice.kind == NotifyKind.HEADS_UP -> CHANNEL_HEADS_UP
            alerting -> CHANNEL_REMINDERS
            else -> CHANNEL_CARDS
        }
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(Notification.BigTextStyle().bigText(notice.text))
            .setContentIntent(openApp(context))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        // A notice that must not alert is on a low-importance channel (heads-up, cards): no sound, no vibration.
        // (Notification.Builder.setSilent is hidden API, not part of the SDK.)
        when (notice.kind) {
            NotifyKind.HEADS_UP -> builder.setTimeoutAfter(HEADS_UP_TIMEOUT_MS).setAutoCancel(true)
            NotifyKind.CLASS_CARD, NotifyKind.CONFIRM, NotifyKind.WARNING -> builder.setCategory(Notification.CATEGORY_REMINDER)
            NotifyKind.REMINDER -> builder.setCategory(Notification.CATEGORY_REMINDER)
            NotifyKind.RUN -> builder.setCategory(Notification.CATEGORY_STATUS)
        }
        val runId = notice.runId
        if (runId != null && notice.kind != NotifyKind.HEADS_UP && notice.kind != NotifyKind.RUN) {
            // A prayer is confirmed; a stage-3 reminder is marked done. Both are Vigil.confirm.
            val prayer = Vigil.state.value.runs.firstOrNull { it.id == runId }?.prayer != null
            val label = if (prayer || notice.kind != NotifyKind.REMINDER) "CONFIRM" else "DONE"
            builder.addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_eye), label, confirmIntent(context, runId)).build(),
            )
        }
        try {
            nm.notify(notice.id, ID_NOTICE, builder.build())
        } catch (e: RuntimeException) {
            // No notification permission, or the system refused: the engine state is already saved.
            Log.w(TAG, "Could not post ${notice.id}: ${e.message}")
        }
    }

    /** Removes one engine notice. */
    fun cancel(context: Context, id: String) {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(id, ID_NOTICE)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not remove $id: ${e.message}")
        }
    }

    private fun confirmIntent(context: Context, runId: String): PendingIntent = PendingIntent.getBroadcast(
        context, runId.hashCode(),
        Intent(context, NoticeActionReceiver::class.java)
            .setAction(NoticeActionReceiver.ACTION_CONFIRM)
            .putExtra(NoticeActionReceiver.EXTRA_RUN, runId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** CONFIRM (or DONE) on an engine notice: [Vigil.confirm] for its run. The engine removes the notice when it applies. */
class NoticeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getStringExtra(EXTRA_RUN) ?: return
        // A failure here must not crash the process in the background; the button can simply be tapped again.
        try {
            if (intent.action == ACTION_CONFIRM) Vigil.confirm(runId)
        } catch (e: Exception) {
            Log.e(TAG, "Confirming $runId failed", e)
        }
    }

    companion object {
        const val ACTION_CONFIRM = "dev.vigil.action.CONFIRM"
        const val EXTRA_RUN = "run"
        private const val TAG = "VigilNotice"
    }
}
