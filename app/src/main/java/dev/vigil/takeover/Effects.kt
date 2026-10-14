package dev.vigil.takeover

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import dev.vigil.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The takeover's clock: [SystemClock.uptimeMillis], refreshed about 30 times a second while the screen is
 * visible (it waits for frames, so it stops while the activity is stopped). Read it only while drawing, so a
 * tick redraws without recomposing.
 */
@Composable
internal fun rememberTakeoverClock(): LongState {
    val clock = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            clock.longValue = SystemClock.uptimeMillis()
            delay(TICK_MS)
        }
    }
    return clock
}

/** Faint CRT scanlines drawn over the content, in whole pixels so they stay crisp like the eye. */
internal fun Modifier.crtScanlines(alpha: Float = 0.22f): Modifier = drawWithContent {
    drawContent()
    val step = 3.dp.roundToPx().toFloat()
    val line = 1.dp.roundToPx().coerceAtLeast(1).toFloat()
    val color = Color.Black.copy(alpha = alpha)
    var y = 0f
    while (y < size.height) {
        drawRect(color, topLeft = Offset(0f, y), size = Size(size.width, line))
        y += step
    }
}

/** One scanline tear: a band of the screen ([top] and [height] as fractions of its height) shifted sideways by [shift] (a fraction of its width). */
internal class Tear(val top: Float, val height: Float, val shift: Float)

/**
 * While [running], a tear now and then: every 9 to 24 s (at random) one band of the screen jumps sideways for
 * about 120 ms. Null between tears.
 */
@Composable
internal fun rememberTear(running: Boolean): State<Tear?> {
    val tear = remember { mutableStateOf<Tear?>(null) }
    LaunchedEffect(running) {
        tear.value = null
        if (!running) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(TEAR_MIN_GAP_MS, TEAR_MAX_GAP_MS + 1))
            val side = if (Random.nextBoolean()) 1f else -1f
            tear.value = Tear(
                top = 0.08f + Random.nextFloat() * 0.8f,
                height = 0.015f + Random.nextFloat() * 0.05f,
                shift = side * (0.02f + Random.nextFloat() * 0.06f),
            )
            delay(TEAR_MS)
            tear.value = null
        }
    }
    return tear
}

/**
 * Alpha of the red vignette [elapsedMs] after the effects started: a slow rise and fall over
 * [VIGNETTE_PERIOD_MS], in hard steps rather than a smooth fade. Starts at 0.
 */
internal fun vignetteAlpha(elapsedMs: Long): Float {
    val t = (elapsedMs.coerceAtLeast(0L) % VIGNETTE_PERIOD_MS).toFloat() / VIGNETTE_PERIOD_MS
    val wave = 1f - abs(2f * t - 1f)
    return floor(wave * VIGNETTE_STEPS) / VIGNETTE_STEPS * VIGNETTE_MAX_ALPHA
}

/**
 * The takeover's after-effects, drawn over the content: a scanline [tear] (one band drawn again, shifted
 * sideways) and the red vignette at [vignette] alpha. Both are read while drawing.
 */
internal fun Modifier.genjutsu(tear: () -> Tear?, vignette: () -> Float): Modifier = drawWithContent {
    drawContent()
    val t = tear()
    if (t != null) {
        val top = (size.height * t.top).roundToInt().toFloat()
        val bottom = (top + (size.height * t.height).roundToInt().coerceAtLeast(1)).coerceAtMost(size.height)
        val shift = (size.width * t.shift).roundToInt().toFloat()
        clipRect(top = top, bottom = bottom) {
            drawRect(Palette.Black, topLeft = Offset(0f, top), size = Size(size.width, bottom - top))
            translate(left = shift) {
                this@drawWithContent.drawContent()
            }
        }
    }
    val alpha = vignette()
    if (alpha > 0f) drawVignette(alpha)
}

/** Red creeping in from the edges: a round gradient stretched to the screen, so all four edges darken alike. */
private fun DrawScope.drawVignette(alpha: Float) {
    val side = size.width
    if (side <= 0f || size.height <= 0f) return
    val brush = Brush.radialGradient(
        0f to Color.Transparent,
        VIGNETTE_CLEAR to Color.Transparent,
        1f to Palette.Red.copy(alpha = alpha),
        center = center,
        radius = side * 0.71f,
    )
    scale(scaleX = 1f, scaleY = size.height / side, pivot = center) {
        drawRect(brush, topLeft = Offset(0f, center.y - side / 2f), size = Size(side, side))
    }
}

private const val TICK_MS = 30L
private const val TEAR_MIN_GAP_MS = 9_000L
private const val TEAR_MAX_GAP_MS = 24_000L
private const val TEAR_MS = 120L
private const val VIGNETTE_PERIOD_MS = 7_000L
private const val VIGNETTE_STEPS = 5
private const val VIGNETTE_MAX_ALPHA = 0.2f

/** How far out (as a fraction of the gradient's radius) the vignette stays clear. */
private const val VIGNETTE_CLEAR = 0.45f
