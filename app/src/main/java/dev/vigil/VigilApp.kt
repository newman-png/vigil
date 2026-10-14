package dev.vigil

import android.app.Application
import android.util.Log
import dev.vigil.engine.Vigil
import dev.vigil.service.VigilService
import dev.vigil.trigger.AlarmScheduler

/** The process entry point: sets up the singletons, then the engine's clock, the alarm and the service. */
class VigilApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        try {
            Vigil.onAppStart(this)
        } catch (e: Exception) {
            // The process also starts for alarms, the notification listener and the service, so a crash here would
            // take all of them down, again on every start. Keep the service up and handle the clock again later.
            Log.e(TAG, "Start-up failed", e)
            runCatching { VigilService.start(this) }
            runCatching { AlarmScheduler.retrySoon(this) }
        }
    }

    private companion object {
        const val TAG = "Vigil"
    }
}
