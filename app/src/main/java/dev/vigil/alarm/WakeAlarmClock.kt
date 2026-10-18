package dev.vigil.alarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.vigil.trigger.AlarmReceiver
import dev.vigil.trigger.AlarmScheduler
import dev.vigil.ui.MainActivity
import java.time.Instant

/**
 * The wake alarm as a real alarm clock ([AlarmManager.setAlarmClock]): the most reliable kind (it fires on the minute
 * in Doze and survives the app being killed) and the phone shows it as the next alarm. It goes to the engine's
 * [AlarmReceiver] like every other alarm; at that moment the engine rings it ([dev.vigil.core.machine.Effect.RingWakeAlarm]).
 * The time is the engine's ([dev.vigil.core.machine.Machine.wakeAlarmAt]): MY TIMES wake, or a ring again.
 */
object WakeAlarmClock {
    /** Its own request code (the engine's alarm uses 0, the TEST alarm 1), so they never replace each other. */
    private const val REQUEST_CODE = 2
    private const val TAG = "VigilWakeAlarm"

    /** When the alarm clock is set for, as far as this process knows (null after a restart: set it again). */
    @Volatile
    private var scheduledAt: Instant? = null

    @Volatile
    private var known = false

    /** Sets the alarm clock to [at] (null cancels it), unless it is already set there. */
    fun sync(context: Context, at: Instant?) {
        if (known && at == scheduledAt) return
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(app, AlarmReceiver::class.java)
        if (at != null) intent.putExtra(AlarmScheduler.EXTRA_AT, at.toEpochMilli())
        val pi = PendingIntent.getBroadcast(app, REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (at == null) {
            am.cancel(pi)
        } else {
            set(app, am, at.toEpochMilli(), pi)
        }
        scheduledAt = at
        known = true
    }

    // USE_EXACT_ALARM (declared in the manifest) allows alarm clocks; lint only knows SCHEDULE_EXACT_ALARM.
    @SuppressLint("MissingPermission")
    private fun set(context: Context, am: AlarmManager, atMs: Long, pi: PendingIntent) {
        // Tapping the alarm icon in the status bar opens VIGIL.
        val show = PendingIntent.getActivity(
            context, REQUEST_CODE,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, show), pi)
        } catch (e: SecurityException) {
            Log.w(TAG, "Alarm clock refused, using an exact alarm: ${e.message}")
            AlarmScheduler.set(am, atMs, pi)
        }
    }
}
