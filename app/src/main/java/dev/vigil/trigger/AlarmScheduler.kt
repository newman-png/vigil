package dev.vigil.trigger

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.vigil.Graph
import dev.vigil.core.engine.TimeSchedule
import dev.vigil.engine.Vigil
import dev.vigil.service.VigilService
import dev.vigil.ui.MainActivity
import java.time.Instant

private const val TAG = "VigilAlarm"

/**
 * One alarm at a time: the next moment the engine said it must wake. A directive's own time is set as an alarm
 * clock, which Doze never delays (the phone leaves deep sleep a moment before it); other wake-ups (the plan's
 * expiry, a takeover's hard maximum) use an exact alarm that is allowed while idle. Without exact alarms both
 * fall back to an inexact one.
 */
object AlarmScheduler {
    private const val REQUEST_ALARM = 0
    private const val REQUEST_SHOW = 10

    /** After a failure while handling the clock, try again this much later. */
    private const val RETRY_AFTER_FAILURE_MS = 5 * 60_000L

    /** Sets the alarm to [at], or cancels it when null. */
    fun schedule(context: Context, at: Instant?) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            app, REQUEST_ALARM, Intent(app, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) {
            am.cancel(pi)
            return
        }
        set(app, am, at.toEpochMilli(), pi, alarmClock = isDirectiveTime(at))
    }

    /** Handling the clock failed, so no next alarm was set: wake again in a few minutes and retry. */
    fun retrySoon(context: Context) = schedule(context, Instant.now().plusMillis(RETRY_AFTER_FAILURE_MS))

    /** True when [at] is the next moment a time-triggered directive of the active plan fires. */
    private fun isDirectiveTime(at: Instant): Boolean = try {
        val now = Instant.now()
        TimeSchedule.next(Graph.activeSet(now).directives, now, Graph.zone)?.at == at
    } catch (e: RuntimeException) {
        false
    }

    /**
     * Sets [pi] to go off at [atMs]: as an alarm clock or an exact alarm allowed while idle, or an inexact one
     * when exact alarms are refused. Also used by [TestAlarm].
     */
    // USE_EXACT_ALARM (declared in the manifest) grants exact alarms; lint only knows SCHEDULE_EXACT_ALARM.
    @SuppressLint("MissingPermission")
    internal fun set(context: Context, am: AlarmManager, atMs: Long, pi: PendingIntent, alarmClock: Boolean) {
        try {
            when {
                !am.canScheduleExactAlarms() -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                alarmClock -> am.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, showIntent(context)), pi)
                else -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused, using an inexact one: ${e.message}")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    /** What tapping the alarm in the status bar or the clock app opens: VIGIL. */
    private fun showIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, REQUEST_SHOW,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The scheduled alarm went off: process time triggers (the engine decides what is due). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // An exact alarm lets VIGIL start its service from the background; the work itself is short and runs here.
        VigilService.start(context)
        try {
            Vigil.onClock(catchUp = true)
        } catch (e: Exception) {
            // A crash here would kill the process in the background, and no next alarm would be set.
            Log.e(TAG, "Handling the alarm failed", e)
            AlarmScheduler.retrySoon(context)
        }
    }
}

/**
 * Reboot, app update, a clock/timezone change, or exact alarms allowed again: reschedule (and after a reboot,
 * catch up). Other actions are ignored (the receiver is exported so the system can reach it).
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val catchUp = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> true
            // A manual clock change would otherwise replay (or skip) hours of directives.
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> false
            else -> return
        }
        VigilService.start(context)
        try {
            Vigil.onClock(catchUp)
        } catch (e: Exception) {
            Log.e(TAG, "Handling ${intent.action} failed", e)
            AlarmScheduler.retrySoon(context)
        }
    }
}
