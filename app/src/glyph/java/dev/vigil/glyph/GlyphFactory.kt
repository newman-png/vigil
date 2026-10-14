package dev.vigil.glyph

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphManager

/** Built with Nothing's Glyph SDK. On other phones [create] returns a no-op. */
object GlyphFactory {
    const val AVAILABLE = true

    fun create(context: Context): GlyphLights {
        val device = deviceCode() ?: return GlyphLights.None
        return NothingGlyphLights(context.applicationContext, device)
    }

    /** The SDK's code for this phone, or null when it is not a Nothing phone with Glyph lights the SDK drives. */
    private fun deviceCode(): String? = try {
        when {
            Common.is20111() -> Glyph.DEVICE_20111
            Common.is22111() -> Glyph.DEVICE_22111
            Common.is23111() -> Glyph.DEVICE_23111
            Common.is23113() -> Glyph.DEVICE_23113
            Common.is24111() -> Glyph.DEVICE_24111 // Phone (3a) and (3a) Pro
            Common.is25111() -> Glyph.DEVICE_25111
            Common.is25131() -> Glyph.DEVICE_25131
            else -> null
        }
    } catch (t: Throwable) {
        null
    }
}

private class NothingGlyphLights(private val context: Context, private val device: String) : GlyphLights {
    private var manager: GlyphManager? = null
    private var ready = false

    private val callback = object : GlyphManager.Callback {
        override fun onServiceConnected(componentName: ComponentName) {
            val m = manager ?: return
            safely {
                m.register(device)
                m.openSession()
                ready = true
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            ready = false
        }
    }

    override fun start() {
        if (manager != null) return
        safely {
            manager = GlyphManager.getInstance(context).also { it.init(callback) }
        }
    }

    override fun heartbeat(tier: Int) = withManager { m ->
        val builder = m.glyphFrameBuilder
        val frame = (if (tier >= 3) builder.buildChannelA().buildChannelB().buildChannelC() else builder.buildChannelC())
            .buildPeriod(300)
            .buildCycles(1)
            .buildInterval(0)
            .build()
        m.animate(frame)
    }

    // One cycle per call: the SDK cannot cancel a running animation, so callers repeat this instead of asking for many.
    override fun breathe() = withManager { m ->
        val frame = m.glyphFrameBuilder.buildChannelC().buildPeriod(3000).buildCycles(1).buildInterval(0).build()
        m.animate(frame)
    }

    override fun off() = withManager { it.turnOff() }

    override fun stop() {
        val m = manager ?: return
        safely {
            if (ready) {
                m.turnOff()
                m.closeSession()
            }
        }
        safely { m.unInit() }
        ready = false
        manager = null
    }

    private fun withManager(block: (GlyphManager) -> Unit) {
        val m = manager ?: return
        if (!ready) return
        safely { block(m) }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: GlyphException) {
            Log.w("VigilGlyph", "Glyph call failed: ${e.message}")
        } catch (t: RuntimeException) {
            Log.w("VigilGlyph", "Glyph unavailable: ${t.message}")
        }
    }
}
