package dev.vigil.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.vigil.core.engine.Engine
import dev.vigil.engine.Vigil
import dev.vigil.service.VigilService

private const val TAG = "VigilTestAlarm"

/**
 * TEST > FIRE IN 60 S: fires the tier-2 preview through a real exact alarm, so the whole path (an alarm in deep
 * sleep, the wake lock, the full-screen takeover over the lock screen) can be tried with the screen off.
 * It has its own alarm and never replaces the one for the plan.
 */
object TestAlarm {
    /** Its own request code (the plan's alarm uses 0), so the two never replace each other. */
    private const val REQUEST_CODE = 1
    private const val MAX_SECONDS = 24 * 60 * 60L

    /** Fires the tier-2 preview [seconds] from now (at least 1 s). */
    fun schedule(context: Context, seconds: Long) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val atMs = System.currentTimeMillis() + seconds.coerceIn(1L, MAX_SECONDS) * 1000
        // The same kind of alarm as a directive's time, so the test runs the real path.
        AlarmScheduler.set(am, atMs, pendingIntent(app))
    }

    /** Cancels a test alarm that has not gone off yet. */
    fun cancel(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(app))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, TestAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The test alarm went off: show the tier-2 preview like a directive that has just fired. */
class TestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VigilService.start(context)
        try {
            Vigil.fireNow(Engine.DEMO_2_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Firing the test alarm failed", e)
        }
    }
}
