package dev.vigil.takeover

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.vigil.ui.theme.Palette

/**
 * The takeover's type: uppercase monospace with wide tracking, centred. No pixel font in the takeover.
 * Kept here rather than in the app theme so the takeover looks the same whatever the other screens do.
 */
internal object TakeoverType {
    /** Secondary text: white at reduced alpha, never a new colour. */
    val Dim = Palette.White.copy(alpha = 0.45f)
    val Faint = Palette.White.copy(alpha = 0.3f)

    /** The directive's title and the check-in's lines. */
    val Title = style(fontSize = 26, lineHeight = 36, tracking = 0.18)

    /** The current step. */
    val Step = style(fontSize = 22, lineHeight = 32, tracking = 0.14)

    /** The directive's why, under its title. */
    val Small = style(fontSize = 13, lineHeight = 21, tracking = 0.2, color = Dim)

    /** One-tap choices: EASY / OK / HARD, BETTER / SAME / WORSE. */
    val Choice = style(fontSize = 18, lineHeight = 24, tracking = 0.22)

    /** The safety strip and the small notes above it. */
    val Strip = style(fontSize = 12, lineHeight = 18, tracking = 0.25, color = Faint)

    private fun style(fontSize: Int, lineHeight: Int, tracking: Double, color: Color = Palette.White) = TextStyle(
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = tracking.em,
        textAlign = TextAlign.Center,
    )
}

/** Takeover text: always uppercase. */
@Composable
internal fun TakeoverText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = style.color) {
    BasicText(text = text.uppercase(), modifier = modifier, style = style.copy(color = color))
}
