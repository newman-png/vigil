package dev.vigil.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
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

val PixelFont = FontFamily(Font(R.font.vt323_regular))

object Type {
    val Huge = TextStyle(fontFamily = PixelFont, fontSize = 64.sp, lineHeight = 60.sp, color = Palette.White)
    val Title = TextStyle(fontFamily = PixelFont, fontSize = 40.sp, lineHeight = 40.sp, color = Palette.White)
    val Heading = TextStyle(fontFamily = PixelFont, fontSize = 28.sp, lineHeight = 30.sp, color = Palette.White)
    val Body = TextStyle(fontFamily = PixelFont, fontSize = 22.sp, lineHeight = 24.sp, color = Palette.White)
    val Small = TextStyle(fontFamily = PixelFont, fontSize = 18.sp, lineHeight = 20.sp, color = Palette.Dim)
}
