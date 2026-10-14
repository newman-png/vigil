package dev.vigil.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.vigil.engine.Notifications
import dev.vigil.engine.Vigil

/**
 * Keeps VIGIL's process alive so triggers are noticed and an unfinished takeover can come back.
 * It watches the screen turning on/off and runs a short periodic tick; all decisions stay in the engine.
 */
class VigilService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            Vigil.onServiceTick(this@VigilService)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> Vigil.onScreenOn(context)
                Intent.ACTION_SCREEN_OFF -> Vigil.onScreenOff(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val notification = Notifications.service(this, Vigil.statusLine())
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifications.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifications.ID_SERVICE, notification)
            }
        } catch (e: IllegalStateException) {
            // A sticky restart in the background without the battery exemption is refused; the next foreground
            // moment (opening the app or a takeover) starts the service again.
            Log.w(TAG, "Could not enter the foreground: ${e.message}")
            stopSelf()
            return
        }
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        handler.post(tick)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshNotification(this)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "VigilService"
        private const val TICK_MS = 5_000L

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, VigilService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // Happens when the battery exemption is missing; the setup screen asks for it.
                Log.w(TAG, "Foreground service not allowed right now: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not start the service: ${e.message}")
            }
        }

        fun refreshNotification(context: Context) {
            if (!running) return
            context.getSystemService(NotificationManager::class.java)
                ?.notify(Notifications.ID_SERVICE, Notifications.service(context, Vigil.statusLine()))
        }
    }
}
