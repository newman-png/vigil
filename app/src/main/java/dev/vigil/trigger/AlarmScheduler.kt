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

/** One exact alarm at a time: the next moment the engine said it must wake. */
object AlarmScheduler {
    private const val TAG = "VigilAlarm"

    // USE_EXACT_ALARM (declared in the manifest) grants exact alarms; lint only knows SCHEDULE_EXACT_ALARM.
    @SuppressLint("MissingPermission")
    fun schedule(context: Context, at: Instant?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context)
        if (at == null) {
            am.cancel(pi)
            return
        }
        val ms = at.toEpochMilli()
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused, using an inexact one: ${e.message}")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The scheduled alarm went off: process time triggers (the engine decides what is due). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VigilService.start(context)
        Vigil.onClock(catchUp = true)
    }
}

/** Reboot, app update, or a clock/timezone change: reschedule (and after a reboot, catch up). */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VigilService.start(context)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Vigil.onClock(catchUp = true)
            // A manual clock change would otherwise replay (or skip) hours of directives.
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Vigil.onClock(catchUp = false)
        }
    }
}
