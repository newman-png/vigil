package dev.vigil.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type
import kotlinx.coroutines.delay

/** Faint CRT scanlines drawn over the content. */
fun Modifier.scanlines(alpha: Float = 0.22f): Modifier = drawWithContent {
    drawContent()
    // Whole pixels, so the lines are crisp and even like the eye.
    val step = 3.dp.roundToPx().toFloat()
    val line = 1.dp.roundToPx().coerceAtLeast(1).toFloat()
    var y = 0f
    val color = Color.Black.copy(alpha = alpha)
    while (y < size.height) {
        drawRect(color, topLeft = Offset(0f, y), size = Size(size.width, line))
        y += step
    }
}

@Composable
fun PixelText(text: String, style: TextStyle = Type.Body, modifier: Modifier = Modifier, color: Color? = null) {
    BasicText(text = text, style = if (color != null) style.copy(color = color) else style, modifier = modifier)
}

/** A square bordered button. Disabled buttons are dimmed and ignore taps. */
@Composable
fun PixelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Palette.White,
) {
    val c = if (enabled) color else Palette.Faint
    Box(
        modifier = modifier
            .border(2.dp, c, RectangleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        PixelText(text, Type.Body, color = c)
    }
}

/**
 * A button that must be held for [holdMillis] to fire. Releasing early resets it. Used for "abandon"
 * and "I'm not okay today", so neither can happen by accident.
 */
@Composable
fun HoldButton(
    text: String,
    holdMillis: Long,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.White,
) {
    var holding by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val complete by rememberUpdatedState(onComplete)

    LaunchedEffect(holding) {
        if (!holding) {
            progress = 0f
            return@LaunchedEffect
        }
        val start = System.currentTimeMillis()
        while (holding) {
            val p = ((System.currentTimeMillis() - start).toFloat() / holdMillis).coerceAtMost(1f)
            progress = p
            if (p >= 1f) {
                holding = false
                complete()
                break
            }
            delay(16)
        }
    }

    // The fill grows in hard 5% steps, like a pixel progress bar. It is drawn behind the label rather than laid
    // out, so it never changes the button's size.
    val stepped = (progress * 20).toInt() / 20f
    val fill = color.copy(alpha = 0.35f)
    Box(
        modifier = modifier
            .border(2.dp, color, RectangleShape)
            .drawBehind { drawRect(fill, size = Size(size.width * stepped, size.height)) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    holding = true
                    waitForUpOrCancellation()
                    holding = false
                }
            },
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val secondsLeft = ((1f - progress) * holdMillis / 1000f).toInt() + 1
            PixelText(if (holding) "$text  ${secondsLeft}" else text, Type.Body, color = color)
        }
    }
}

/** 1, 2 or 3 red squares showing a directive's tier. */
@Composable
fun TierPips(tier: Int, modifier: Modifier = Modifier, pip: Dp = 8.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            if (i > 0) Spacer(Modifier.width(pip / 2))
            Box(
                Modifier
                    .size(pip)
                    .then(if (i < tier) Modifier.background(Palette.Red) else Modifier.border(1.dp, Palette.Faint, RectangleShape)),
            )
        }
    }
}

/** A 2dp horizontal rule. */
@Composable
fun Rule(modifier: Modifier = Modifier, color: Color = Palette.Faint) {
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(color))
    }
}
