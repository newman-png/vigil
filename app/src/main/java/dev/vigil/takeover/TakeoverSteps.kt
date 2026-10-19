package dev.vigil.takeover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.vigil.core.machine.StepView
import dev.vigil.core.protocol.Step
import dev.vigil.core.protocol.StepType
import dev.vigil.ui.theme.Palette

/**
 * Stage 1: the current step alone, by its type. A check or done step is dim while it is locked (with when it
 * unlocks) and white once it can be held; a timer counts itself down and ends by itself; a text step asks for its
 * lines and SAVE. A step with `noise_toggle` (the wind-down's lights low) also has the WHITE NOISE switch. [nowSec]
 * reads the clock (epoch seconds) for the countdowns, so only this step redraws each second; [onTyping] is told about
 * every keystroke.
 */
@Composable
internal fun SingleStep(view: StepView, nowSec: () -> Long, onTyping: () -> Unit, onSave: (String) -> Unit) {
    val step = view.step
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when (step.type) {
            StepType.CHECK, StepType.DONE -> {
                TakeoverText(step.text, TakeoverType.Step, color = if (view.canTap) Palette.White else TakeoverType.Dim)
                val until = view.lockedUntil
                val hint = when {
                    view.canTap -> "HOLD TO CONFIRM"
                    until != null -> "UNLOCKS IN ${minutesUntil(until, nowSec())} MIN"
                    else -> null
                }
                if (hint != null) {
                    Spacer(Modifier.height(20.dp))
                    TakeoverText(hint, TakeoverType.Strip)
                }
            }
            StepType.TIMER -> {
                TakeoverText(step.text, TakeoverType.Step)
                val ends = view.timerEndsAt
                if (ends != null) {
                    Spacer(Modifier.height(24.dp))
                    TakeoverText(countdown(secondsUntil(ends, nowSec())), TakeoverType.Timer)
                }
                // Only where the engine lets a timer be tapped through (not in a forced run).
                if (view.canTap) {
                    Spacer(Modifier.height(16.dp))
                    TakeoverText("HOLD TO GO ON", TakeoverType.Strip)
                }
            }
            StepType.TEXT -> {
                TakeoverText(step.text, TakeoverType.Step)
                Spacer(Modifier.height(20.dp))
                TextLines(step.lines ?: 1, requiredLines(step), onTyping, onSave)
                if (step.optional) SkipWord { onSave("") }
            }
        }
        if (step.noiseToggle) {
            Spacer(Modifier.height(16.dp))
            NoiseSwitch()
        }
        // A reset's re-enter step says what re-entering does: what comes first, starts now, or comes next.
        view.reentry?.let {
            Spacer(Modifier.height(20.dp))
            TakeoverText(it, TakeoverType.Small, color = Palette.White)
        }
    }
}

/** How many of a text step's lines must be filled: all of them, or one for a to-dos step ("up to" its lines). */
internal fun requiredLines(step: Step): Int = if (step.todos) 1 else (step.lines ?: 1)

/** SKIP under an optional text step (the reset's "name it"): the step is done with nothing typed. */
@Composable
private fun SkipWord(onSkip: () -> Unit) {
    TakeoverText(
        "SKIP",
        TakeoverType.Choice,
        Modifier
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onSkip)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        color = TakeoverType.Dim,
    )
}

/**
 * Stage 2 (guided): every step as a plain checklist, without timers or step locks. Only the current step [current]
 * can be ticked, with a tap; a text step asks for its lines in place.
 */
@Composable
internal fun Checklist(
    steps: List<Step>,
    current: Int,
    onTap: (Int, StepType) -> Unit,
    onTyping: () -> Unit,
    onSave: (Int, String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        steps.forEachIndexed { i, step ->
            key(i, step.text) {
                val done = i < current
                val now = i == current
                val tappable = now && step.type != StepType.TEXT
                val color = when {
                    done -> TakeoverType.Faint
                    now -> Palette.White
                    else -> TakeoverType.Dim
                }
                val source = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(source, indication = null, enabled = tappable) { onTap(i, step.type) }
                        .padding(vertical = 10.dp),
                ) {
                    TakeoverText(if (done) "[X]" else "[ ]", TakeoverType.Item, color = color)
                    Spacer(Modifier.width(14.dp))
                    TakeoverText(step.text, TakeoverType.Item, Modifier.weight(1f), color = color)
                }
                if (now && step.type == StepType.TEXT) {
                    TextLines(step.lines ?: 1, requiredLines(step), onTyping) { onSave(i, it) }
                    if (step.optional) SkipWord { onSave(i, "") }
                    Spacer(Modifier.height(8.dp))
                }
                if (now && step.noiseToggle) NoiseSwitch()
            }
        }
    }
}

/**
 * A text step's [count] lines, one field each, then SAVE (only once at least [required] lines have something). The
 * filled lines are joined with line breaks, as the engine counts them. [onTyping] is told about every change: the
 * keyboard's keys never reach the activity, which keeps the display on only while it is touched.
 */
@Composable
internal fun TextLines(count: Int, required: Int, onTyping: () -> Unit, onSave: (String) -> Unit) {
    val lines = count.coerceAtLeast(1)
    val values = remember(lines) { mutableStateListOf<String>().apply { repeat(lines) { add("") } } }
    val focus = LocalFocusManager.current
    val ready = values.count { it.isNotBlank() } >= required.coerceIn(1, lines)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        for (i in 0 until lines) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TakeoverText("${i + 1}", TakeoverType.Strip)
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = values[i],
                    // One line per field: a pasted line break would count as an extra line.
                    onValueChange = {
                        values[i] = it.replace('\n', ' ')
                        onTyping()
                    },
                    singleLine = true,
                    textStyle = TakeoverType.Field,
                    cursorBrush = SolidColor(Palette.White),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = if (i < lines - 1) ImeAction.Next else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focus.moveFocus(FocusDirection.Down) },
                        onDone = { focus.clearFocus() },
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .drawBehind {
                            val line = 1.dp.toPx()
                            drawRect(TakeoverType.Faint, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
                        }
                        .padding(vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        TakeoverText(
            "SAVE",
            TakeoverType.Choice,
            Modifier
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = ready) {
                    focus.clearFocus()
                    onSave(values.filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
                }
                .padding(horizontal = 24.dp, vertical = 14.dp),
            color = if (ready) Palette.White else TakeoverType.Faint,
        )
    }
}
