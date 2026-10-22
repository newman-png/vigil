package dev.vigil.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * A hold's progress (0 to 1, read while drawing) behind the content: a bar along the bottom that grows from the left,
 * or with [fill] the whole area filling from the left. Nothing at 0, so it is gone the moment the hold is let go.
 */
fun Modifier.holdProgress(progress: () -> Float, color: Color = Palette.Red, fill: Boolean = false): Modifier = drawBehind {
    val p = progress().coerceIn(0f, 1f)
    if (p <= 0f) return@drawBehind
    if (fill) {
        drawRect(color, size = Size(size.width * p, size.height))
    } else {
        val bar = HOLD_BAR.toPx()
        drawRect(color, topLeft = Offset(0f, size.height - bar), size = Size(size.width * p, bar))
    }
}

private val HOLD_BAR = 3.dp

/** A held button's fill: its own colour, faint, so the countdown on it stays readable. */
private const val HOLD_FILL_ALPHA = 0.35f

/** How often a hold's countdown and progress move on. */
internal const val HOLD_TICK_MS = 30L

/** A tap target with no ripple or highlight: VIGIL answers a tap with a hard cut, never an animation. */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick)

/** Text in one of the [Type] styles; [color] replaces the style's colour. */
@Composable
fun PixelText(
    text: String,
    style: TextStyle = Type.Body,
    modifier: Modifier = Modifier,
    color: Color? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    var s = if (color != null) style.copy(color = color) else style
    if (textAlign != null) s = s.copy(textAlign = textAlign)
    BasicText(
        text = text,
        modifier = modifier,
        style = s,
        // A cut line ends in an ellipsis rather than mid-letter.
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        maxLines = maxLines,
    )
}

/** A thin-bordered button with an uppercase label. Disabled buttons are faint and ignore taps. */
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
            .border(1.dp, c, RectangleShape)
            .tap(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        PixelText(text.uppercase(), Type.Body, color = c, textAlign = TextAlign.Center)
    }
}

/**
 * A button that must be held for [holdMillis] to fire. While it is held the label gives way to the seconds
 * left, counting down, and the button fills from the left; releasing early resets both. For actions that must not
 * happen by accident.
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
    var secondsLeft by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    val complete by rememberUpdatedState(onComplete)

    LaunchedEffect(holding) {
        if (!holding) {
            progress = 0f
            return@LaunchedEffect
        }
        val start = System.currentTimeMillis()
        while (holding) {
            val held = System.currentTimeMillis() - start
            val left = holdMillis - held
            if (left <= 0) {
                holding = false
                progress = 0f
                complete()
                break
            }
            // 2.4 s left shows 3: the number is the seconds still to hold, rounded up.
            secondsLeft = ((left + 999) / 1000).toInt()
            progress = held.toFloat() / holdMillis
            delay(HOLD_TICK_MS)
        }
    }

    Box(
        modifier = modifier
            .border(1.dp, color, RectangleShape)
            .holdProgress({ progress }, color.copy(alpha = HOLD_FILL_ALPHA), fill = true)
            .pointerInput(holdMillis) {
                awaitEachGesture {
                    awaitFirstDown()
                    secondsLeft = ((holdMillis + 999) / 1000).toInt()
                    holding = true
                    waitForUpOrCancellation()
                    holding = false
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The label stays laid out (invisible while held), so the button never changes size.
        PixelText(text.uppercase(), Type.Body, color = if (holding) Color.Transparent else color, textAlign = TextAlign.Center)
        if (holding) PixelText("$secondsLeft", Type.Body, color = color)
    }
}

/** 1, 2 or 3 red squares: how forced a protocol is (3 at stage 1, 1 at stage 3; see [stagePips]). */
@Composable
fun TierPips(tier: Int, modifier: Modifier = Modifier, pip: Dp = 6.dp) {
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

/** The pips for a protocol at [stage]: stage 1 (forced) shows 3, stage 2 (guided) 2, stage 3 (reminder) 1. */
fun stagePips(stage: Int): Int = (4 - stage).coerceIn(1, 3)

/** A thin horizontal rule. */
@Composable
fun Rule(modifier: Modifier = Modifier, color: Color = Palette.Faint) {
    Box(modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(color))
    }
}

/** A screen's title and an optional line of help under it. */
@Composable
fun ScreenTitle(title: String, help: String? = null) {
    PixelText(title, Type.Title)
    if (help != null) {
        Spacer(Modifier.height(4.dp))
        PixelText(help, Type.Small)
    }
    Spacer(Modifier.height(16.dp))
}

/** A small uppercase label above a group of rows. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Palette.Dim) {
    PixelText(text.uppercase(), Type.Label, modifier.padding(top = 8.dp, bottom = 4.dp), color = color)
}

/** A row with a label on the left and its state on the right, e.g. "SOUND ...... ON". Tapping flips it. */
@Composable
fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .tap { onChange(!value) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelText(label.uppercase(), Type.Body, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        PixelText(if (value) "ON" else "OFF", Type.Body, color = if (value) Palette.White else Palette.Dim)
    }
}

/**
 * A collapsible section: a header row with its state on the right ([hint], e.g. "2 MISSING") and a + / -
 * marker. The open state survives rotation.
 */
@Composable
fun Section(
    title: String,
    hint: String? = null,
    hintColor: Color = Palette.Dim,
    initiallyOpen: Boolean = false,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Faint))
        Row(
            Modifier
                .fillMaxWidth()
                .tap { open = !open }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelText(title.uppercase(), Type.Heading, Modifier.weight(1f))
            if (hint != null) {
                Spacer(Modifier.width(12.dp))
                PixelText(hint.uppercase(), Type.Label, color = hintColor)
            }
            Spacer(Modifier.width(12.dp))
            PixelText(if (open) "-" else "+", Type.Heading, color = Palette.Dim)
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) { content() }
        }
    }
}

/** A single-line text field that saves on every change. It reloads [initial] only when [resetKey] changes. */
@Composable
fun Field(initial: String, resetKey: Any?, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    var value by remember(resetKey) { mutableStateOf(initial) }
    BasicTextField(
        value = value,
        onValueChange = {
            value = it
            onChange(it)
        },
        singleLine = true,
        textStyle = Type.Body,
        cursorBrush = SolidColor(Palette.White),
        modifier = modifier
            .padding(vertical = 4.dp)
            .border(1.dp, Palette.Faint, RectangleShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
