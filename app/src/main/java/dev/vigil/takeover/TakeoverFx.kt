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
 * The takeover is serious, not a game: heartbeats, a reversed swell under a thin high tone, total silence
 * the moment the eye opens, then a slow sub-bass drone, and a dry thud for each confirmed step. There are
 * no chimes, bleeps or UI clicks. The sounds are res/raw/drone, heartbeat, swell, tone and thud; a file
 * replaced under the same name swaps the sound.
 *
 * Tier 3 uses the alarm audio stream and alarm vibrations, so a Fajr or sleep directive is heard even
 * when the ringer is on vibrate; tiers 1-2 follow the ringer like a notification.
 *
 * Everything goes quiet while the takeover is not visible ([pause]): leaving it, a call, or the screen
 * turning off never leaves a drone, a vibration or a light running behind other apps. After [completed],
 * [dismissed] or [stop] nothing plays any more; a new takeover gets a new TakeoverFx.
 */
class TakeoverFx(
    context: Context,
    private val tier: Int,
    silent: Boolean,
    soundEnabled: Boolean,
    vibrationEnabled: Boolean,
    glyphEnabled: Boolean,
) {
    private val glyph: GlyphLights = if (glyphEnabled && !silent) GlyphFactory.create(context) else GlyphLights.None

    private val usage = if (tier >= 3) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT
    private val pool: SoundPool? = if (soundEnabled && !silent) {
        SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
            // A loop asked for before its file has loaded starts as soon as the file is ready.
            .also { it.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) onLoaded(sampleId) } }
    } else {
        null
    }
    private val droneSound = pool?.load(context, R.raw.drone, 1) ?: 0
    private val heartbeatSound = pool?.load(context, R.raw.heartbeat, 1) ?: 0
    private val swellSound = pool?.load(context, R.raw.swell, 1) ?: 0
    private val toneSound = pool?.load(context, R.raw.tone, 1) ?: 0
    private val thudSound = pool?.load(context, R.raw.thud, 1) ?: 0

    // SoundPool stream ids of what may be playing; 0 = nothing.
    private var droneStream = 0
    private var toneStream = 0
    private var swellStream = 0
    private var heartbeatStream = 0
    private var thudStream = 0

    /** Full drone volume for this tier: tier 3 full, tier 2 about half, tier 1 none. */
    private val droneVolume = when {
        tier >= 3 -> DRONE_VOLUME
        tier == 2 -> DRONE_VOLUME / 2
        else -> 0f
    }

    /** The drone has been asked for and plays whenever the takeover is visible, until the end. */
    private var droneWanted = false

    /** How far the drone's fade-in has come, 0..1. Kept while paused, so [resume] brings back the same level. */
    private var droneLevel = 0f

    /** The thin high tone loops from [swellStart] until [eyeOpen]. */
    private var toneWanted = false

    /** A step is waiting: the Glyph breathes. */
    private var breathingWanted = false

    /** True while the takeover is not visible. */
    private var paused = false

    /** Quiet mode was switched on while this takeover was showing: silent from now on. */
    private var muted = false

    /** The takeover is over (completed, dismissed or stopped): nothing new plays. */
    private var ended = false
    private var released = false

    private val vibrator: Vibrator? =
        if (vibrationEnabled && !silent) context.getSystemService(VibratorManager::class.java)?.defaultVibrator else null
    private val vibrationAttributes = VibrationAttributes.createForUsage(
        if (tier >= 3) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_NOTIFICATION,
    )

    private val handler = Handler(Looper.getMainLooper())

    // The SDK's long animations cannot be cancelled, so the breathing light is one short cycle repeated from here.
    private val breath = object : Runnable {
        override fun run() {
            if (!breathingWanted || paused || released) return
            glyph.breathe()
            handler.postDelayed(this, BREATH_MS)
        }
    }

    // SoundPool has no fades, so the drone's volume is raised in small steps from here.
    private val droneFade = object : Runnable {
        override fun run() {
            if (!droneWanted || paused || released) return
            droneLevel = (droneLevel + FADE_STEP_MS.toFloat() / DRONE_FADE_MS).coerceAtMost(1f)
            applyDroneVolume()
            if (droneLevel < 1f) handler.postDelayed(this, FADE_STEP_MS)
        }
    }

    /** Connects the Glyph lights. Call when the takeover becomes visible. */
    fun start() = glyph.start()

    /** One heartbeat: the low "lub-dub" sound, a matching vibration and a Glyph pulse. */
    fun heartbeat() {
        if (quietNow()) return
        heartbeatStream = play(heartbeatSound, HEARTBEAT_VOLUME)
        // The second pulse lands with the sound's "dub", 240 ms after the "lub".
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 180, 70), intArrayOf(0, 255, 0, 160), -1))
        glyph.heartbeat(tier)
    }

    /** Tier 1's single beat: one soft vibration and a Glyph pulse, no sound. */
    fun tick() {
        if (quietNow()) return
        vibrate(VibrationEffect.createOneShot(40, 110))
        glyph.heartbeat(1)
    }

    /**
     * The reversed swell rises under a thin high tone. The tone loops until [eyeOpen]; the swell ends
     * abruptly by itself, and [eyeOpen] cuts it if it is still rising.
     */
    fun swellStart() {
        if (muted || ended || released) return
        swellStream = stopStream(swellStream)
        swellStream = play(swellSound, SWELL_VOLUME)
        // Wanted even while paused, so [resume] brings the tone back until the eye opens.
        toneWanted = true
        playTone()
    }

    /** The eye's first frame: the swell and the tone stop at once, leaving total silence. */
    fun eyeOpen() {
        toneWanted = false
        toneStream = stopStream(toneStream)
        swellStream = stopStream(swellStream)
        heartbeatStream = stopStream(heartbeatStream)
        vibrator?.cancel()
    }

    /**
     * After the open eye has held in silence: the looping sub-bass drone fades in over about 6 s, to full
     * volume on tier 3 and about half on tier 2. Tier 1 has no drone. Calling it again does nothing.
     */
    fun droneIn() {
        if (tier < 2 || muted || ended || released || droneWanted) return
        droneWanted = true
        playDrone()
        handler.removeCallbacks(droneFade)
        handler.postDelayed(droneFade, FADE_STEP_MS)
    }

    /** A step's lock has ended and it can be confirmed: one soft haptic tick, no sound. */
    fun stepReady() {
        if (quietNow()) return
        vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }

    /** A step was confirmed (held to the end): a dry low thud and a short low vibration. */
    fun confirmStep() {
        if (quietNow()) return
        thudStream = play(thudSound, THUD_VOLUME)
        vibrate(VibrationEffect.createOneShot(50, 200))
    }

    /** A step is waiting for the user: the Glyph breathes slowly until the takeover ends. */
    fun waiting() {
        if (muted || ended || released) return
        breathingWanted = true
        handler.removeCallbacks(breath)
        handler.post(breath)
    }

    /**
     * The directive is done: instant silence. The drone and everything else stop and nothing new plays.
     * Only the last step's confirmation (the thud, which has no tail, and its 50 ms vibration) is left to
     * finish, so the last step feels like every other one.
     */
    fun completed() {
        ended = true
        silence(keepConfirmation = true)
    }

    /** The directive was abandoned: instant silence, no sound. */
    fun dismissed() {
        ended = true
        silence()
    }

    /** Quiet mode came on: go silent (no sound, vibration or lights) for the rest of this takeover. */
    fun mute() {
        muted = true
        silence()
    }

    /** Stops everything for good (the takeover closed in another way). */
    fun stop() {
        ended = true
        silence()
    }

    /** The takeover left the screen: silence everything until [resume]. */
    fun pause() {
        if (released) return
        paused = true
        handler.removeCallbacks(breath)
        handler.removeCallbacks(droneFade)
        stopSounds()
        vibrator?.cancel()
        glyph.off()
    }

    /** The takeover is visible again: bring back the tone, the drone (at the level it had) and the breathing light. */
    fun resume() {
        if (!paused || released) return
        paused = false
        playTone()
        if (droneWanted) {
            playDrone()
            if (droneLevel < 1f) {
                handler.removeCallbacks(droneFade)
                handler.postDelayed(droneFade, FADE_STEP_MS)
            }
        }
        if (breathingWanted) {
            handler.removeCallbacks(breath)
            handler.post(breath)
        }
    }

    /** Stops everything and frees the sounds and the Glyph service. The object is unusable afterwards. */
    fun release() {
        if (released) return
        stop()
        released = true
        handler.removeCallbacksAndMessages(null)
        glyph.stop()
        pool?.release()
    }

    /** True when nothing may play right now: not visible, muted, over or released. */
    private fun quietNow() = paused || muted || ended || released

    /** Ends everything this takeover set going. [keepConfirmation] lets a just-played thud and its vibration finish. */
    private fun silence(keepConfirmation: Boolean = false) {
        droneWanted = false
        toneWanted = false
        breathingWanted = false
        handler.removeCallbacks(breath)
        handler.removeCallbacks(droneFade)
        stopSounds(keepThud = keepConfirmation)
        if (!keepConfirmation) vibrator?.cancel()
        glyph.off()
    }

    private fun stopSounds(keepThud: Boolean = false) {
        droneStream = stopStream(droneStream)
        toneStream = stopStream(toneStream)
        swellStream = stopStream(swellStream)
        heartbeatStream = stopStream(heartbeatStream)
        if (!keepThud) thudStream = stopStream(thudStream)
    }

    /** Stops [stream] if there is one; returns 0 ("no stream") to store in its place. */
    private fun stopStream(stream: Int): Int {
        if (stream != 0) pool?.stop(stream)
        return 0
    }

    private fun playDrone() {
        if (!droneWanted || quietNow() || droneStream != 0) return
        val volume = currentDroneVolume()
        droneStream = pool?.play(droneSound, volume, volume, LOOP_PRIORITY, -1, 1f) ?: 0
    }

    private fun applyDroneVolume() {
        if (droneStream == 0) return
        val volume = currentDroneVolume()
        pool?.setVolume(droneStream, volume, volume)
    }

    // Squared, so the fade-in sounds even to the ear instead of jumping up and then flattening out.
    private fun currentDroneVolume() = droneVolume * droneLevel * droneLevel

    private fun playTone() {
        if (!toneWanted || quietNow() || toneStream != 0) return
        toneStream = pool?.play(toneSound, TONE_VOLUME, TONE_VOLUME, LOOP_PRIORITY, -1, 1f) ?: 0
    }

    private fun onLoaded(sampleId: Int) {
        if (sampleId == droneSound) playDrone()
        if (sampleId == toneSound) playTone()
    }

    /** Plays a sound once; returns its stream id, or 0 when nothing plays. */
    private fun play(sound: Int, volume: Float): Int {
        if (sound == 0 || quietNow()) return 0
        return pool?.play(sound, volume, volume, SHOT_PRIORITY, 0, 1f) ?: 0
    }

    private fun vibrate(effect: VibrationEffect) {
        if (!quietNow()) vibrator?.vibrate(effect, vibrationAttributes)
    }

    private companion object {
        const val BREATH_MS = 3_600L
        const val DRONE_FADE_MS = 6_000L
        const val FADE_STEP_MS = 100L

        // Playback volumes, 0..1, on top of the files' own level (the generated ones all peak at -3 dBFS).
        const val DRONE_VOLUME = 0.45f // tier 3; tier 2 plays it at half
        const val HEARTBEAT_VOLUME = 0.9f
        const val SWELL_VOLUME = 0.8f
        const val TONE_VOLUME = 0.06f
        const val THUD_VOLUME = 0.7f

        // When all streams are busy, SoundPool stops the lowest priority: a one-shot never cuts a loop.
        const val LOOP_PRIORITY = 2
        const val SHOT_PRIORITY = 1
    }
}
