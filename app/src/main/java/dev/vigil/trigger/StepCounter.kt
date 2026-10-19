package dev.vigil.trigger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.vigil.engine.Vigil

/**
 * The phone's step counter (design M8): the engine ends a walk after 10 minutes without steps, and the movement nudge
 * comes after 90 still minutes. The counter's total goes to the engine at most once a minute ([BATCH_MS]). While a walk
 * runs the sensor reports at once; otherwise the hardware batches its reports for minutes ([SLOW_LATENCY_US]), so the
 * phone sleeps. Without a step counter or the PHYSICAL ACTIVITY permission the engine is told so, and walks end by
 * their time rules instead. Main thread only.
 */
object StepCounter : SensorEventListener {
    private const val TAG = "VigilSteps"

    /** The engine gets the total at most this often. */
    private const val BATCH_MS = 60_000L

    /** Outside a walk the hardware may hold the counter's reports this long (microseconds). */
    private const val SLOW_LATENCY_US = 5 * 60 * 1_000_000

    private val handler = Handler(Looper.getMainLooper())

    /** Registered for a walk (true), outside one (false), or not registered (null). */
    private var mode: Boolean? = null

    private var latest: Long? = null
    private var sent: Long? = null
    private var sentAt = 0L
    private val flush = Runnable { send() }

    /** True when VIGIL may read the step counter (PHYSICAL ACTIVITY allowed). */
    fun permitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** True when the phone has a step counter. */
    fun present(context: Context): Boolean =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /**
     * Listens in the right mode for now (a walk or not), or tells the engine there is no step counter. Called by the
     * service at its start and every minute, and when walking switches on or off.
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        val sm = app.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sm == null || sensor == null || !permitted(app)) {
            if (mode != null) sm?.unregisterListener(this)
            mode = null
            val steps = Vigil.state.value.steps
            if (steps.available || steps.total != null) Vigil.onSteps(null)
            return
        }
        val walking = Vigil.state.value.toggles.walking
        if (mode == walking) return
        sm.unregisterListener(this)
        val ok = try {
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, if (walking) 0 else SLOW_LATENCY_US)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not listen to the step counter: ${e.message}")
            false
        }
        mode = if (ok) walking else null
        if (!ok && Vigil.state.value.steps.available) Vigil.onSteps(null)
    }

    /** Stops listening (the service is going away). */
    fun stop(context: Context) {
        handler.removeCallbacks(flush)
        if (mode != null) context.applicationContext.getSystemService(SensorManager::class.java)?.unregisterListener(this)
        mode = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val total = event.values.firstOrNull()?.toLong() ?: return
        latest = total
        val since = SystemClock.elapsedRealtime() - sentAt
        if (sent == null || since >= BATCH_MS) {
            send()
        } else {
            handler.removeCallbacks(flush)
            handler.postDelayed(flush, BATCH_MS - since)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun send() {
        handler.removeCallbacks(flush)
        val total = latest ?: return
        if (total == sent) return
        sent = total
        sentAt = SystemClock.elapsedRealtime()
        try {
            Vigil.onSteps(total)
        } catch (e: Exception) {
            Log.e(TAG, "Could not report the steps", e)
        }
    }
}
