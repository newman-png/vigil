package dev.vigil.glyph

import android.content.Context

/** Built without Nothing's Glyph SDK (run tools/fetch-glyph-sdk.sh to include it). */
object GlyphFactory {
    const val AVAILABLE = false

    fun create(context: Context): GlyphLights = GlyphLights.None
}
