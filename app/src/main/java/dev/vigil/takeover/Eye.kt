package dev.vigil.takeover

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the eye is doing. */
enum class EyeMode {
    /** Nothing visible (black). */
    HIDDEN,

    /** Just the closed red slit (frame 0). */
    SLIT,

    /** Playing the opening frames, [EYE_OPEN_FRAME_MS] each; the first few flash in negative. */
    OPENING,

    /** Open and still: the first idle frame, an occasional glint flicker, the tier pips orbiting. */
    OPEN,

    /** Open, the pupil narrowing in hard steps with the hold progress (the contract frames). */
    CONTRACTING,

    /** Playing the opening frames backwards, slowly ([EYE_CLOSE_FRAME_MS] each), down to the slit. */
    CLOSING,
}

/**
 * The eye, drawn from the sprite sheet with hard integer pixel scaling (no smoothing) so the eye itself spans
 * about 90% of the width, with the tier marks behind it: 1-2 red pips orbit the eye; at tier 3 three pips fuse
 * into a turning triangle.
 *
 * The sheet (res/drawable-nodpi/eye_sheet.png) is a horizontal strip whose layout is described by
 * res/values/eye_sheet.xml, so it can be replaced without touching code.
 *
 * [modeStartedAt] is when [mode] began, on the [clock]'s time base ([android.os.SystemClock.uptimeMillis]).
 * [contract] is the hold progress (0..1) shown while [mode] is [EyeMode.CONTRACTING]. Both lambdas and the clock
 * are read while drawing only.
 */
@Composable
fun Eye(
    mode: EyeMode,
    modeStartedAt: Long,
    tier: Int,
    clock: LongState,
    modifier: Modifier = Modifier,
    contract: () -> Float = { 0f },
) {
    val sheet = ImageBitmap.imageResource(R.drawable.eye_sheet)
    val frameSize = integerResource(R.integer.eye_frame_size)
    val eyeWidth = integerResource(R.integer.eye_width)
    val openFrames = integerResource(R.integer.eye_open_frames)
    val idleFrames = integerResource(R.integer.eye_idle_frames)
    val contractFrames = integerResource(R.integer.eye_contract_frames)
    // When the eye first stood open: the tier-3 pips fuse into the triangle once, from then on.
    val openedAt = remember { longArrayOf(0L) }
    if (openedAt[0] == 0L && (mode == EyeMode.OPEN || mode == EyeMode.CONTRACTING)) openedAt[0] = modeStartedAt

    Canvas(modifier) {
        val now = clock.longValue
        val elapsed = (now - modeStartedAt).coerceAtLeast(0L)
        val frame = when (mode) {
            EyeMode.HIDDEN -> return@Canvas
            EyeMode.SLIT -> 0
            EyeMode.OPENING -> (elapsed / EYE_OPEN_FRAME_MS).toInt().coerceAtMost(openFrames - 1)
            EyeMode.OPEN -> idleFrame(now, openFrames, idleFrames)
            EyeMode.CONTRACTING -> contractFrame(contract(), openFrames, idleFrames, contractFrames)
            EyeMode.CLOSING -> (openFrames - 1 - (elapsed / EYE_CLOSE_FRAME_MS).toInt()).coerceAtLeast(0)
        }

        // Whole-pixel scale: as large as fits ~90% of the width, and the eye's height in the box.
        val byWidth = floor(size.width * EYE_SPAN / eyeWidth).toInt()
        val byHeight = floor(size.height / (frameSize * EYE_HEIGHT_SHARE)).toInt()
        val scale = max(1, min(byWidth, byHeight))
        val originX = floor((size.width - frameSize * scale) / 2f).toInt()
        val originY = floor((size.height - frameSize * scale) / 2f).toInt()

        if (mode == EyeMode.OPEN || mode == EyeMode.CONTRACTING) {
            drawTierMarks(tier, now, now - openedAt[0], originX, originY, scale, frameSize, eyeWidth)
        }
        drawImage(
            image = sheet,
            srcOffset = IntOffset(frame * frameSize, 0),
            srcSize = IntSize(frameSize, frameSize),
            dstOffset = IntOffset(originX, originY),
            dstSize = IntSize(frameSize * scale, frameSize * scale),
            colorFilter = if (eyeNegative(mode, elapsed)) NEGATIVE else null,
            filterQuality = FilterQuality.None,
        )
    }
}

/**
 * True while the opening flashes in negative (black and white swapped, red unchanged): the first two opening
 * frames and the fourth, about 250 ms in all. The takeover screen turns its background white for the same frames.
 */
internal fun eyeNegative(mode: EyeMode, elapsedMs: Long): Boolean =
    mode == EyeMode.OPENING && (elapsedMs.coerceAtLeast(0L) / EYE_OPEN_FRAME_MS).toInt() in NEGATIVE_FRAMES

/** The eye holds still; every few seconds the glint flickers through the other idle frames. No blink. */
private fun idleFrame(now: Long, openFrames: Int, idleFrames: Int): Int {
    if (idleFrames <= 0) return openFrames - 1
    val t = now % GLINT_PERIOD_MS
    val flicker = (idleFrames - 1) * GLINT_FRAME_MS
    return if (t < flicker) openFrames + 1 + (t / GLINT_FRAME_MS).toInt() else openFrames
}

/**
 * The hold progress in hard steps: the resting eye first, then one contract frame per step, the pinpoint
 * pupil before the hold completes.
 */
private fun contractFrame(progress: Float, openFrames: Int, idleFrames: Int, contractFrames: Int): Int {
    val rest = if (idleFrames > 0) openFrames else openFrames - 1
    if (contractFrames <= 0) return rest
    val step = floor(progress.coerceIn(0f, 1f) * (contractFrames + 1)).toInt()
    if (step == 0) return rest
    return openFrames + idleFrames + (step - 1).coerceAtMost(contractFrames - 1)
}

/**
 * The tier marks on the sprite's pixel grid (so they line up with the eye), drawn before the eye so it covers
 * them. [sinceOpen] is how long the eye has stood open.
 */
private fun DrawScope.drawTierMarks(
    tier: Int,
    now: Long,
    sinceOpen: Long,
    originX: Int,
    originY: Int,
    scale: Int,
    frameSize: Int,
    eyeWidth: Int,
) {
    val centre = frameSize / 2.0
    // Room from the centre to the edges of the canvas, in sprite pixels, keeping a 2 x 2 pip inside.
    val roomX = size.width / 2.0 / scale - 1.5
    val roomY = size.height / 2.0 / scale - 1.5

    fun cell(x: Int, y: Int, cells: Int = 1) {
        drawRect(
            Palette.Red,
            topLeft = Offset((originX + x * scale).toFloat(), (originY + y * scale).toFloat()),
            size = Size((cells * scale).toFloat(), (cells * scale).toFloat()),
        )
    }

    fun point(angle: Double, rx: Double, ry: Double): Pair<Int, Int> =
        (centre + rx * cos(angle)).roundToInt() to (centre + ry * sin(angle)).roundToInt()

    val pips = tier.coerceIn(1, 3)
    if (pips < 3) {
        // An ellipse just clear of the eye's corners.
        val rx = min(eyeWidth / 2.0 + 3, roomX)
        val ry = min(rx * 0.75, roomY)
        val angle = (now % PIP_ORBIT_MS) / PIP_ORBIT_MS.toDouble() * 2 * PI
        for (i in 0 until pips) {
            val (x, y) = point(angle + i * PI, rx, ry)
            cell(x - 1, y - 1, 2)
        }
        return
    }

    // Tier 3: the three pips orbit, then lines grow between them until they fuse into a turning triangle.
    val radius = minOf(eyeWidth / 2.0 + 4, roomX, roomY)
    val angle = (now % TRIANGLE_TURN_MS) / TRIANGLE_TURN_MS.toDouble() * 2 * PI
    val corners = (0 until 3).map { point(angle + it * 2 * PI / 3, radius, radius) }
    for ((x, y) in corners) cell(x - 1, y - 1, 2)
    val fuse = ((sinceOpen - FUSE_DELAY_MS).coerceAtLeast(0L) / FUSE_MS.toFloat()).coerceAtMost(1f)
    if (fuse <= 0f) return
    for (i in 0 until 3) {
        val (x0, y0) = corners[i]
        val (x1, y1) = corners[(i + 1) % 3]
        val points = line(x0, y0, x1, y1)
        for (p in points.take((points.size * fuse).toInt())) cell(p.first, p.second)
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

/** How long each opening frame shows. */
const val EYE_OPEN_FRAME_MS = 60L

/** How long each frame shows while the eye closes: slowly. */
const val EYE_CLOSE_FRAME_MS = 180L

/** The share of the canvas width the open eye spans, at most (the integer scale rounds it down). */
private const val EYE_SPAN = 0.9f

/** The share of a frame's height the eye needs to fit inside the canvas. */
private const val EYE_HEIGHT_SHARE = 0.6f

private val NEGATIVE_FRAMES = setOf(0, 1, 3)
private const val GLINT_PERIOD_MS = 5_200L
private const val GLINT_FRAME_MS = 160L
private const val PIP_ORBIT_MS = 6_000L
private const val TRIANGLE_TURN_MS = 12_000L
private const val FUSE_DELAY_MS = 600L
private const val FUSE_MS = 900L

/**
 * The negative: an affine colour map with black -> white, white -> black and the eye's red #FF2B2B (255, 43, 43)
 * left as it is. Each row sums to -1 with an offset of 255, which swaps black and white; the first column is then
 * solved so that red maps to itself (R: 43 / 212, G and B: -169 / 212). Alpha is untouched, so transparent stays
 * transparent.
 */
private val NEGATIVE = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.202830f, -0.601415f, -0.601415f, 0f, 255f,
            -0.797170f, -0.101415f, -0.101415f, 0f, 255f,
            -0.797170f, -0.101415f, -0.101415f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)
