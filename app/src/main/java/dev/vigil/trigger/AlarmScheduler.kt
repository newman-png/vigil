package dev.vigil.trigger

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.vigil.engine.Vigil
import dev.vigil.service.VigilService
import java.time.Instant

private const val TAG = "VigilAlarm"

/**
 * One alarm at a time: the next moment the engine must wake ([dev.vigil.core.machine.Machine.nextWake]: a planned
 * start, a lock or timer end, a prayer heads-up or window, the day's end ...), as an exact alarm that is allowed while
 * idle, so it fires on the minute in Doze too. Without exact alarms it falls back to an inexact one. The planned
 * moment travels with the alarm, so a late alarm is journaled (alarm_late).
 *
 * Not an alarm clock (setAlarmClock): the phone would show VIGIL as its next alarm, and a bedtime Do Not
 * Disturb schedule set to end at the next alarm would switch off at the next protocol every night.
 */
object AlarmScheduler {
    private const val REQUEST_ALARM = 0

    /** The planned moment (epoch ms) in the alarm's intent. */
    const val EXTRA_AT = "at"

    /** After a failure while handling the clock, try again this much later. */
    private const val RETRY_AFTER_FAILURE_MS = 5 * 60_000L

    /** When the alarm is set for, as far as this process knows (null after a restart: set it again). */
    @Volatile
    var scheduledAt: Instant? = null
        private set

    /** Sets the alarm to [at], or cancels it when null. */
    fun schedule(context: Context, at: Instant?) {
        scheduledAt = at
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(app, AlarmReceiver::class.java)
        if (at != null) intent.putExtra(EXTRA_AT, at.toEpochMilli())
        val pi = PendingIntent.getBroadcast(app, REQUEST_ALARM, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (at == null) {
            am.cancel(pi)
            return
        }
        set(am, at.toEpochMilli(), pi)
    }

    /** Handling the clock failed, so no next alarm was set: wake again in a few minutes and retry. */
    fun retrySoon(context: Context) = schedule(context, Instant.now().plusMillis(RETRY_AFTER_FAILURE_MS))

    /** Sets [pi] to go off at [atMs]: exact and allowed while idle, or inexact when exact alarms are refused. Also used by [TestAlarm]. */
    // USE_EXACT_ALARM (declared in the manifest) grants exact alarms; lint only knows SCHEDULE_EXACT_ALARM.
    @SuppressLint("MissingPermission")
    internal fun set(am: AlarmManager, atMs: Long, pi: PendingIntent) {
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused, using an inexact one: ${e.message}")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }
}

/** The scheduled alarm went off: the engine applies everything due (and journals the alarm if it came late). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // An exact alarm lets VIGIL start its service from the background; the work itself is short and runs here.
        VigilService.start(context)
        val at = intent.getLongExtra(AlarmScheduler.EXTRA_AT, 0L).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
        try {
            Vigil.onAlarm(at)
        } catch (e: Exception) {
            // A crash here would kill the process in the background, and no next alarm would be set.
            Log.e(TAG, "Handling the alarm failed", e)
            AlarmScheduler.retrySoon(context)
        }
    }
}

/**
 * Reboot, app update, a clock or time zone change, or exact alarms allowed again: the engine catches up and the alarm
 * is set again. Other actions are ignored (the receiver is exported so the system can reach it).
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> Unit
            else -> return
        }
        VigilService.start(context)
        try {
            Vigil.onSystemEvent()
        } catch (e: Exception) {
            Log.e(TAG, "Handling ${intent.action} failed", e)
            AlarmScheduler.retrySoon(context)
        }
    }
}
