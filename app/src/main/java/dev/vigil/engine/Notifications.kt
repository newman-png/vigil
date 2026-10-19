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
import dev.vigil.alarm.WakeAlarmActivity
import dev.vigil.alarm.WhiteNoiseStopReceiver
import dev.vigil.core.machine.Effect
import dev.vigil.core.machine.NotifyKind
import dev.vigil.core.run.JummahPart
import dev.vigil.core.run.RunPhase
import dev.vigil.takeover.TakeoverActivity
import dev.vigil.ui.MainActivity

/**
 * Every notification VIGIL posts: the always-on one (QUIET, LATER), the one behind a takeover, and the engine's notices
 * ([Effect.Notify]: heads-ups, class cards, CONFIRM and I'M AT UNI prompts, stage-3 reminders, runs without a screen
 * lock, window warnings, the reset offer, promises). Only a takeover that has just started, a stage-3 reminder or warning
 * that is not silent, and the reset offer may alert; everything else is posted on low-importance channels, which never
 * sound or light up a dark screen.
 */
object Notifications {
    private const val TAG = "VigilNotify"

    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_TAKEOVER = "takeover"
    const val CHANNEL_HEADS_UP = "heads_up"
    const val CHANNEL_CARDS = "cards"
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_ALARM = "wake_alarm"
    const val CHANNEL_NOISE = "white_noise"
    const val CHANNEL_OFFER = "offers"

    /** v1 channels that no longer exist. */
    private val OLD_CHANNELS = listOf("plan")

    const val ID_SERVICE = 1
    const val ID_TAKEOVER = 2
    const val ID_ALARM = 3
    const val ID_NOISE = 4

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
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_OFFER, "Reset offer", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "The reset offered after missed protocols in a row (START or NOT NOW). Silent while QUIET is on."
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Wake alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Opens VIGIL's wake alarm. The alarm makes its own sound on the alarm stream."
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_NOISE, "White noise", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while white noise plays, with STOP."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        OLD_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }
    }

    /** The always-on notification, with the QUIET switch and LATER (a 10-minute promise, from home's sheet). */
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
        val later = PendingIntent.getActivity(
            context, 5, MainActivity.laterIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle(if (quiet) "VIGIL · QUIET" else "VIGIL")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp(context))
            .addAction(action)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_eye), "LATER", later).build())
            .build()
    }

    /**
     * The notification behind every takeover. With [fullScreen] the system opens the takeover by itself when
     * the phone is locked or the screen is off (only for a run that has just started). During a call it is posted
     * without that, so the takeover never covers the call screen.
     */
    fun takeover(context: Context, title: String, fullScreen: Boolean, night: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            context, 1, TakeoverActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_TAKEOVER)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle(if (night) "VIGIL · NIGHT LOCK" else "VIGIL · ${title.uppercase()}")
            .setContentText(if (night) "Until your wake alarm. Tap to open it." else "A protocol is running. Tap to open it.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply { if (fullScreen) setFullScreenIntent(open, true) }
            .build()
    }

    /** The wake alarm's notification (it keeps its service running); [fullScreen] opens the alarm over the lock screen. */
    fun wakeAlarm(context: Context, test: Boolean, fullScreen: Boolean): Notification {
        val open = PendingIntent.getActivity(
            context, 3, WakeAlarmActivity.intent(context, test),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle(if (test) "VIGIL · WAKE ALARM (TEST)" else "VIGIL · WAKE UP")
            .setContentText("Get up. Hold I'M UP on the alarm screen to stop it.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(open)
            .apply { if (fullScreen) setFullScreenIntent(open, true) }
            .build()
    }

    /** White noise is playing: STOP ends it. */
    fun whiteNoise(context: Context): Notification {
        val stop = PendingIntent.getBroadcast(
            context, 4, Intent(context, WhiteNoiseStopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_NOISE)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("VIGIL · WHITE NOISE")
            .setContentText("Playing. It stops at the wake alarm, after 8 hours at the latest.")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp(context))
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_eye), "STOP", stop).build())
            .build()
    }

    /** Shows (or replaces) one engine notice. */
    fun show(context: Context, notice: Effect.Notify) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val alerting = !notice.silent && (notice.kind == NotifyKind.REMINDER || notice.kind == NotifyKind.WARNING)
        val channel = when {
            notice.kind == NotifyKind.HEADS_UP -> CHANNEL_HEADS_UP
            // The reset offer is a full-intensity card: it alerts (heads-up), unless QUIET is on.
            notice.kind == NotifyKind.OFFER && !notice.silent -> CHANNEL_OFFER
            alerting -> CHANNEL_REMINDERS
            else -> CHANNEL_CARDS
        }
        // A run without a screen lock that is still going (the morning): its takeover opens from the notice.
        val openable = notice.runId?.takeIf { id ->
            notice.kind == NotifyKind.RUN && Vigil.state.value.runs.any { it.id == id && it.phase == RunPhase.ACTIVE }
        }
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(Notification.BigTextStyle().bigText(notice.text))
            .setContentIntent(openable?.let { openRun(context, it) } ?: openApp(context))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        // A notice that must not alert is on a low-importance channel (heads-up, cards): no sound, no vibration.
        // (Notification.Builder.setSilent is hidden API, not part of the SDK.)
        when (notice.kind) {
            NotifyKind.HEADS_UP -> builder.setTimeoutAfter(HEADS_UP_TIMEOUT_MS).setAutoCancel(true)
            NotifyKind.CLASS_CARD, NotifyKind.CONFIRM, NotifyKind.WARNING -> builder.setCategory(Notification.CATEGORY_REMINDER)
            NotifyKind.REMINDER -> builder.setCategory(Notification.CATEGORY_REMINDER)
            NotifyKind.RUN -> {
                builder.setCategory(Notification.CATEGORY_STATUS)
                // While the run goes on it stays (the way back to a meal or the gym's workout); its end removes it.
                if (openable != null) builder.setOngoing(true)
                openable?.let { id ->
                    builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_eye), "OPEN", openRun(context, id)).build())
                }
            }
            // Jummah's "class ends in 5 minutes": nothing to answer.
            NotifyKind.CARD -> builder.setCategory(Notification.CATEGORY_REMINDER)
            NotifyKind.OFFER -> {
                val title = Vigil.state.value.offer?.let { Vigil.file().find(it.protocol)?.title } ?: "RESET"
                builder.setCategory(Notification.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_eye), "START ${title.uppercase()}", offerIntent(context, true)).build())
                    .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_eye), "NOT NOW", offerIntent(context, false)).build())
            }
            // A promise stays while it runs (its pool lock holds until DONE).
            NotifyKind.PROMISE -> builder.setCategory(Notification.CATEGORY_REMINDER).setOngoing(true)
        }
        val runId = notice.runId
        val answerable = notice.kind != NotifyKind.HEADS_UP && notice.kind != NotifyKind.RUN && notice.kind != NotifyKind.CARD
        if (runId != null && answerable) {
            // Vigil.confirm for every one: a prayer or Jummah is confirmed, I'M AT UNI on the way to Jummah, a stage-3
            // reminder or a promise is done.
            val run = Vigil.state.value.runs.firstOrNull { it.id == runId }
            val part = run?.jummah?.part
            val label = when {
                part == JummahPart.LEAVE || part == JummahPart.TRAVEL -> "I'M AT UNI"
                notice.kind == NotifyKind.PROMISE -> "DONE"
                run?.prayer != null || run?.jummah != null || notice.kind != NotifyKind.REMINDER -> "CONFIRM"
                else -> "DONE"
            }
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

    /** START ([start]) or NOT NOW on the reset offer. */
    private fun offerIntent(context: Context, start: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, if (start) 6 else 7,
        Intent(context, NoticeActionReceiver::class.java)
            .setAction(if (start) NoticeActionReceiver.ACTION_OFFER_START else NoticeActionReceiver.ACTION_OFFER_NOT_NOW),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Opens the takeover of run [runId] (one without a screen lock is shown only when asked for). */
    private fun openRun(context: Context, runId: String): PendingIntent = PendingIntent.getActivity(
        context, runId.hashCode(), TakeoverActivity.intent(context, runId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * The buttons on engine notices: CONFIRM, DONE or I'M AT UNI ([Vigil.confirm] for its run), and START / NOT NOW on the
 * reset offer. The engine removes the notice when it applies.
 */
class NoticeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A failure here must not crash the process in the background; the button can simply be tapped again.
        try {
            when (intent.action) {
                ACTION_CONFIRM -> intent.getStringExtra(EXTRA_RUN)?.let { Vigil.confirm(it) }
                ACTION_OFFER_START -> Vigil.answerOffer(start = true)
                ACTION_OFFER_NOT_NOW -> Vigil.answerOffer(start = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handling ${intent.action} failed", e)
        }
    }

    companion object {
        const val ACTION_CONFIRM = "dev.vigil.action.CONFIRM"
        const val ACTION_OFFER_START = "dev.vigil.action.OFFER_START"
        const val ACTION_OFFER_NOT_NOW = "dev.vigil.action.OFFER_NOT_NOW"
        const val EXTRA_RUN = "run"
        private const val TAG = "VigilNotice"
    }
}
