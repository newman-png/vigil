package dev.vigil.trigger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import dev.vigil.engine.Device
import dev.vigil.engine.Vigil

/**
 * Call pause (design: "Emergency dialer: it pauses the protocol for the length of the call"): tells the engine when a
 * call connects and ends. Three sources, each noticed at once:
 *  - the phone's call state (TelephonyCallback, needs READ_PHONE_STATE: off-hook = in a call or dialling);
 *  - the audio mode (in call / in communication, which VoIP apps set while a call is connected);
 *  - other apps' ongoing call notifications (seen by the notification listener, see AdhanListener).
 * The service also checks every few seconds, so a missed callback is corrected quickly. Main thread only.
 */
object CallWatcher {
    private const val TAG = "VigilCalls"

    private var telephony: TelephonyCallback? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null

    /** Starts listening (from the service); safe to call again, e.g. after READ_PHONE_STATE was granted. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (telephony == null && hasPhoneState(app)) {
            try {
                val tm = app.getSystemService(TelephonyManager::class.java)
                if (tm != null) {
                    val callback = CallStateCallback(app)
                    tm.registerTelephonyCallback(app.mainExecutor, callback)
                    telephony = callback
                }
            } catch (e: RuntimeException) {
                // SecurityException without the permission (revoked meanwhile); the audio mode still works.
                Log.w(TAG, "Could not watch the call state: ${e.message}")
            }
        }
        if (modeListener == null) {
            try {
                val am = app.getSystemService(AudioManager::class.java)
                if (am != null) {
                    val listener = AudioManager.OnModeChangedListener { check(app) }
                    am.addOnModeChangedListener(app.mainExecutor, listener)
                    modeListener = listener
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not watch the audio mode: ${e.message}")
            }
        }
        check(app)
    }

    /** Stops listening (the service is going away). */
    fun stop(context: Context) {
        val app = context.applicationContext
        telephony?.let { cb -> runCatching { app.getSystemService(TelephonyManager::class.java)?.unregisterTelephonyCallback(cb) } }
        telephony = null
        Device.telephonyState = TelephonyManager.CALL_STATE_IDLE
        modeListener?.let { l -> runCatching { app.getSystemService(AudioManager::class.java)?.removeOnModeChangedListener(l) } }
        modeListener = null
    }

    /** Compares the phone with the engine and tells the engine when a call connected or ended. */
    fun check(context: Context) {
        try {
            val connected = Device.callConnected(context)
            val paused = Vigil.state.value.callSince != null
            if (connected != paused) Vigil.onCall(connected)
        } catch (e: Exception) {
            Log.e(TAG, "Checking the call state failed", e)
        }
    }

    fun hasPhoneState(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private class CallStateCallback(private val app: Context) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            Device.telephonyState = state
            CallWatcher.check(app)
        }
    }
}
