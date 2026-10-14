package dev.vigil.takeover

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.vigil.R
import dev.vigil.glyph.GlyphFactory
import dev.vigil.glyph.GlyphLights

/**
 * Sound, vibration and Glyph lights for one takeover. Silent takeovers (quiet mode) and the app's
 * sound/vibration/glyph settings switch the matching outputs off; nothing else is decided here.
 *
 * Tier 3 uses the alarm audio stream and alarm vibrations, so a Fajr or sleep directive is heard even
 * when the ringer is on vibrate; tiers 1-2 follow the ringer like a notification.
 *
 * Everything goes quiet while the takeover is not visible ([pause]): leaving it, a call, or the screen
 * turning off never leaves a drone, a vibration or a light running behind other apps.
 */
class TakeoverFx(
    context: Context,
    private val tier: Int,
    silent: Boolean,
    soundEnabled: Boolean,
    vibrationEnabled: Boolean,
    glyphEnabled: Boolean,
) {
    private val sound = soundEnabled && !silent
    private val vibrate = vibrationEnabled && !silent
    private val glyph: GlyphLights = if (glyphEnabled && !silent) GlyphFactory.create(context) else GlyphLights.None

    private val usage = if (tier >= 3) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT
    private val pool: SoundPool? = if (sound) {
        SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
    } else {
        null
    }
    private val drone = pool?.load(context, R.raw.drone, 1) ?: 0
    private val thump = pool?.load(context, R.raw.thump, 1) ?: 0
    private val sting = pool?.load(context, R.raw.sting, 1) ?: 0
    private val blip = pool?.load(context, R.raw.blip, 1) ?: 0
    private val dismiss = pool?.load(context, R.raw.dismiss, 1) ?: 0
    private var droneStream = 0

    /** The drone should be playing whenever the takeover is visible (tier 3, between the eye opening and the end). */
    private var droneWanted = false

    /** The checklist is waiting: the Glyph breathes. */
    private var breathingWanted = false

    /** True while the takeover is not visible. */
    private var paused = false

    /** Quiet mode was switched on while this takeover was showing: silent from now on. */
    private var muted = false
    private var released = false

    private val vibrator: Vibrator? =
        if (vibrate) context.getSystemService(VibratorManager::class.java)?.defaultVibrator else null
    private val vibrationAttributes = VibrationAttributes.createForUsage(
        if (tier >= 3) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_NOTIFICATION,
    )

    // The SDK's long animations cannot be cancelled, so the breathing light is one short cycle repeated from here.
    private val handler = Handler(Looper.getMainLooper())
    private val breath = object : Runnable {
        override fun run() {
            if (!breathingWanted || paused || released) return
            glyph.breathe()
            handler.postDelayed(this, BREATH_MS)
        }
    }

    fun start() = glyph.start()

    /** One "lub-dub": thump sound, matching vibration and a Glyph pulse. */
    fun heartbeat() {
        if (paused || muted) return
        play(thump, 0.9f)
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 110, 90), intArrayOf(0, 255, 0, 170), -1))
        glyph.heartbeat(tier)
    }

    /** A light tick for tier-1 nudges, which skip the heartbeat. */
    fun tick() {
        if (paused || muted) return
        vibrate(VibrationEffect.createOneShot(40, 120))
        glyph.heartbeat(1)
    }

    fun startDrone() {
        if (tier < 3) return
        droneWanted = true
        playDrone()
    }

    fun blip() {
        if (!paused) play(blip, 0.12f)
    }

    fun waiting() {
        if (muted) return
        breathingWanted = true
        handler.removeCallbacks(breath)
        handler.post(breath)
    }

    fun completed() {
        endAmbience()
        play(sting, 0.8f)
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 30), intArrayOf(0, 140, 0, 200), -1))
    }

    fun dismissed() {
        endAmbience()
        play(dismiss, 0.6f)
    }

    /** Quiet mode came on: go silent (no sound, vibration or lights) for the rest of this takeover. */
    fun mute() {
        muted = true
        endAmbience()
        vibrator?.cancel()
    }

    /** Stops everything for good (the takeover closed in another way). */
    fun stop() {
        endAmbience()
        vibrator?.cancel()
    }

    /** The takeover left the screen: silence everything until [resume]. */
    fun pause() {
        paused = true
        handler.removeCallbacks(breath)
        stopDroneStream()
        vibrator?.cancel()
        glyph.off()
    }

    /** The takeover is visible again: bring back the drone and the breathing light if they were on. */
    fun resume() {
        if (!paused || released) return
        paused = false
        if (droneWanted) playDrone()
        if (breathingWanted) handler.post(breath)
    }

    fun release() {
        stop()
        released = true
        glyph.stop()
        pool?.release()
    }

    private fun endAmbience() {
        droneWanted = false
        breathingWanted = false
        handler.removeCallbacks(breath)
        stopDroneStream()
        glyph.off()
    }

    private fun playDrone() {
        if (paused || muted || released || droneStream != 0) return
        droneStream = pool?.play(drone, 0.22f, 0.22f, 0, -1, 1f) ?: 0
    }

    private fun stopDroneStream() {
        if (droneStream != 0) pool?.stop(droneStream)
        droneStream = 0
    }

    private fun play(id: Int, volume: Float) {
        if (id != 0 && !released && !muted) pool?.play(id, volume, volume, 1, 0, 1f)
    }

    private fun vibrate(effect: VibrationEffect) {
        if (!paused && !muted) vibrator?.vibrate(effect, vibrationAttributes)
    }

    private companion object {
        const val BREATH_MS = 3_600L
    }
}
