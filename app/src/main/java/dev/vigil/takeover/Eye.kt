package dev.vigil.takeover

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.vigil.R
import dev.vigil.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the eye is doing. */
enum class EyeMode {
    /** Nothing visible yet (the black beat). */
    HIDDEN,

    /** Just the red slit (frame 0), pulsing with the heartbeat. */
    SLIT,

    /** Playing the opening frames. */
    OPENING,

    /** Open: idle frames, an occasional blink, pips orbiting. */
    OPEN,

    /** Playing the opening frames backwards. */
    CLOSING,
}

/**
 * The eye, drawn on a 72x72 virtual pixel grid with hard integer scaling: the 32x32 sprite in the middle
 * and the tier marks around it. Tier 1-2 show 1-2 red pips orbiting the iris; at tier 3 three pips fuse
 * into a rotating triangle.
 *
 * The sprite sheet (res/drawable-nodpi/eye_sheet.png) is a horizontal strip whose layout is described by
 * res/values/eye_sheet.xml, so it can be replaced without touching code.
 */
@Composable
fun Eye(mode: EyeMode, tier: Int, modeStartedAt: Long, modifier: Modifier = Modifier) {
    val sheet = ImageBitmap.imageResource(R.drawable.eye_sheet)
    val frameSize = integerResource(R.integer.eye_frame_size)
    val openFrames = integerResource(R.integer.eye_open_frames)
    val idleFrames = integerResource(R.integer.eye_idle_frames)
    val clock = remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) withFrameMillis { clock.longValue = it }
    }

    Canvas(modifier) {
        val now = clock.longValue
        if (now == 0L || mode == EyeMode.HIDDEN) return@Canvas
        val elapsed = (System.currentTimeMillis() - modeStartedAt).coerceAtLeast(0)

        val grid = GRID
        val scale = max(1f, floor(minOf(size.width, size.height) / grid))
        val originX = floor((size.width - grid * scale) / 2)
        val originY = floor((size.height - grid * scale) / 2)
        val eyeOrigin = (grid - frameSize) / 2

        val frame = when (mode) {
            EyeMode.HIDDEN, EyeMode.SLIT -> 0
            EyeMode.OPENING -> (elapsed / FRAME_MS).toInt().coerceAtMost(openFrames - 1)
            EyeMode.CLOSING -> (openFrames - 1 - (elapsed / FRAME_MS).toInt()).coerceAtLeast(0)
            EyeMode.OPEN -> idleFrame(now, openFrames, idleFrames)
        }
        val slitPulse = mode == EyeMode.SLIT && (elapsed / 400) % 2 == 1L
        if (!slitPulse) {
            drawImage(
                image = sheet,
                srcOffset = IntOffset(frame * frameSize, 0),
                srcSize = IntSize(frameSize, frameSize),
                dstOffset = IntOffset((originX + eyeOrigin * scale).toInt(), (originY + eyeOrigin * scale).toInt()),
                dstSize = IntSize((frameSize * scale).toInt(), (frameSize * scale).toInt()),
                filterQuality = FilterQuality.None,
            )
        }

        if (mode == EyeMode.OPEN) {
            drawTierMarks(tier, now, elapsed, originX, originY, scale, grid)
        }
    }
}

/** Idle frames loop slowly; every few seconds the eye blinks (opening frames backwards then forwards). */
private fun idleFrame(now: Long, openFrames: Int, idleFrames: Int): Int {
    val blinkPeriod = 7000L
    val t = now % blinkPeriod
    val blinkFrames = 6
    val blinkLen = blinkFrames * 2 * BLINK_FRAME_MS
    if (t < blinkLen) {
        val i = (t / BLINK_FRAME_MS).toInt()
        val depth = if (i < blinkFrames) i else 2 * blinkFrames - 1 - i
        return (openFrames - 1 - depth).coerceAtLeast(0)
    }
    if (idleFrames <= 0) return openFrames - 1
    return openFrames + ((now / 450) % idleFrames).toInt()
}

private fun DrawScope.drawTierMarks(tier: Int, now: Long, elapsed: Long, ox: Float, oy: Float, scale: Float, grid: Int) {
    val center = grid / 2f
    fun cell(x: Int, y: Int, size: Int = 1) {
        if (x < 0 || y < 0 || x >= grid || y >= grid) return
        drawRect(Palette.Red, topLeft = Offset(ox + x * scale, oy + y * scale), size = Size(size * scale, size * scale))
    }
    fun point(angle: Double, radius: Float): Pair<Int, Int> =
        (center + radius * cos(angle)).roundToInt() to (center + radius * sin(angle)).roundToInt()

    val pips = tier.coerceIn(1, 3)
    if (pips < 3) {
        val angle = (now % 6000L) / 6000.0 * 2 * PI
        for (i in 0 until pips) {
            val (x, y) = point(angle + i * PI, PIP_RADIUS)
            cell(x - 1, y - 1, 2)
        }
        return
    }

    // Tier 3: the three pips orbit, then lines grow between them until they fuse into a turning triangle.
    val angle = (now % 10_000L) / 10_000.0 * 2 * PI
    // The triangle's inner circle (radius / 2) stays clear of the 32 px wide eye.
    val corners = (0 until 3).map { point(angle + it * 2 * PI / 3, TRIANGLE_RADIUS) }
    val fuse = ((elapsed - 600L).coerceAtLeast(0) / 900f).coerceAtMost(1f)
    for ((x, y) in corners) cell(x - 1, y - 1, 2)
    if (fuse <= 0f) return
    for (i in 0 until 3) {
        val (x0, y0) = corners[i]
        val (x1, y1) = corners[(i + 1) % 3]
        val points = line(x0, y0, x1, y1)
        val visible = (points.size * fuse).toInt()
        for (p in points.take(visible)) cell(p.first, p.second)
    }
}

/** Bresenham's line on the pixel grid. */
private fun line(x0: Int, y0: Int, x1: Int, y1: Int): List<Pair<Int, Int>> {
    val out = mutableListOf<Pair<Int, Int>>()
    var x = x0
    var y = y0
    val dx = abs(x1 - x0)
    val dy = -abs(y1 - y0)
    val sx = if (x0 < x1) 1 else -1
    val sy = if (y0 < y1) 1 else -1
    var err = dx + dy
    while (true) {
        out += x to y
        if (x == x1 && y == y1) break
        val e2 = 2 * err
        if (e2 >= dy) {
            err += dy
            x += sx
        }
        if (e2 <= dx) {
            err += dx
            y += sy
        }
    }
    return out
}

const val FRAME_MS = 70L
private const val GRID = 72
private const val PIP_RADIUS = 22f
private const val TRIANGLE_RADIUS = 34f
private const val BLINK_FRAME_MS = 45L
