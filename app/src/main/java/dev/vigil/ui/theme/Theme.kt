package dev.vigil.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.vigil.R

/**
 * Strictly black, white and red. Red is rationed: the eye, tier pips and urgent states only.
 * Secondary text is white at reduced alpha, never a new colour.
 */
object Palette {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val Red = Color(0xFFFF2B2B)
    val Dim = White.copy(alpha = 0.55f)
    val Faint = White.copy(alpha = 0.25f)
}

/** The VT323 pixel font. Used only for the "VIGIL" title on the home screen ([Type.Logo]). */
val PixelFont = FontFamily(Font(R.font.vt323_regular))

/**
 * Uppercase monospace with wide tracking everywhere, sized for a phone held in one hand.
 * [Small] is the one style meant for sentence-case help text, so its tracking is tighter.
 */
object Type {
    /** The home screen's "VIGIL" and nothing else. */
    val Logo = TextStyle(fontFamily = PixelFont, fontSize = 96.sp, lineHeight = 88.sp, letterSpacing = 0.04.em, color = Palette.White)

    /** One big statement: APPLIES FROM TOMORROW. */
    val Huge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = 0.12.em, color = Palette.White)

    /** Screen titles. */
    val Title = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = 0.2.em, color = Palette.White)

    /** The rows of the home screen. */
    val Item = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = 0.18.em, color = Palette.White)

    /** Section headers. */
    val Heading = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.2.em, color = Palette.White)

    val Body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.12.em, color = Palette.White)

    /** Status lines, hints and labels: small, uppercase, widely spaced. */
    val Label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 17.sp, letterSpacing = 0.16.em, color = Palette.Dim)

    /** Help text, sentence case. */
    val Small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.06.em, color = Palette.Dim)
}
