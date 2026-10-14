package dev.vigil.glyph

/**
 * The Glyph lights on the back of Nothing phones. The real implementation (src/glyph) uses Nothing's Glyph SDK
 * and only works while VIGIL is in the foreground; builds without the SDK use src/noglyph, which does nothing.
 */
interface GlyphLights {
    /** Connect to the Glyph service. Call when the takeover becomes visible. */
    fun start()

    /** A short pulse for each heartbeat of the takeover intro. */
    fun heartbeat(tier: Int)

    /** One slow breath of light (about 3 s); repeated while the checklist is waiting. */
    fun breathe()

    fun off()

    /** Release the Glyph service. Call when the takeover is no longer visible. */
    fun stop()

    object None : GlyphLights {
        override fun start() = Unit
        override fun heartbeat(tier: Int) = Unit
        override fun breathe() = Unit
        override fun off() = Unit
        override fun stop() = Unit
    }
}
