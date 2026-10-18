package dev.vigil.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.timeline.Timeline
import dev.vigil.engine.Vigil
import dev.vigil.service.VigilService
import java.time.Instant

private const val TAG = "VigilTestAlarm"

/**
 * TEST > FIRE IN 60 S: starts a TEST preview of a prayer protocol through a real exact alarm, so the whole path (an
 * alarm in deep sleep, the wake lock, the full-screen takeover over the lock screen) can be tried with the screen off.
 * A preview runs at stage 1 and is journaled as a test: no stages, penalties or prayer memory. It has its own alarm
 * and never replaces the engine's.
 */
object TestAlarm {
    /** Its own request code (the engine's alarm uses 0), so the two never replace each other. */
    private const val REQUEST_CODE = 1
    private const val MAX_SECONDS = 24 * 60 * 60L
    internal const val EXTRA_PROTOCOL = "protocol"

    /**
     * Starts the preview of [protocolId] [seconds] from now (at least 1 s); without an id, the protocol of the prayer
     * whose adhan is expected next (or the first prayer protocol in the file).
     */
    fun schedule(context: Context, seconds: Long, protocolId: String? = null) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val atMs = System.currentTimeMillis() + seconds.coerceIn(1L, MAX_SECONDS) * 1000
        // The same kind of alarm as the engine's, so the test runs the real path.
        AlarmScheduler.set(am, atMs, pendingIntent(app, protocolId))
    }

    /** Cancels a test alarm that has not gone off yet. */
    fun cancel(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(app, null))
    }

    /** The protocol a test alarm without an id previews: the next prayer's, else the first prayer protocol, else the first. */
    fun defaultProtocol(): String? {
        val file = Vigil.file()
        val now = Instant.now()
        val last = Vigil.state.value.prayers.lastAdhan
        val next = Prayer.entries
            .filter { file.prayerProtocol(it) != null && last[it] != null }
            .minByOrNull { Timeline.estimate(last.getValue(it), now) }
        return next?.let { file.prayerProtocol(it)?.id }
            ?: file.protocols.firstOrNull { it.kind == ProtocolKind.PRAYER }?.id
            ?: file.protocols.firstOrNull()?.id
    }

    private fun pendingIntent(context: Context, protocolId: String?): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(context, TestAlarmReceiver::class.java).apply { if (protocolId != null) putExtra(EXTRA_PROTOCOL, protocolId) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The test alarm went off: start the preview like a protocol that has just fired. */
class TestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VigilService.start(context)
        try {
            val id = intent.getStringExtra(TestAlarm.EXTRA_PROTOCOL) ?: TestAlarm.defaultProtocol() ?: return
            Vigil.startManual(id, test = true)
        } catch (e: Exception) {
            Log.e(TAG, "Firing the test alarm failed", e)
        }
    }
}
