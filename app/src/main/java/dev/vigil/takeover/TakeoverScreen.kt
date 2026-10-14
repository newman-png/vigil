package dev.vigil.takeover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import dev.vigil.core.engine.Engine
import dev.vigil.core.engine.Ticket
import dev.vigil.core.plan.Directive
import dev.vigil.ui.Format
import dev.vigil.ui.components.HoldButton
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type
import kotlinx.coroutines.delay

/** How a step tap was received by the engine. */
enum class StepResult { NEXT, COMPLETED, IGNORED }

/** What the user did with "I'm not okay today" while this takeover was showing. */
enum class BadDayResult { BYPASSED, KEPT }

interface TakeoverCallbacks {
    fun stepShown(takeoverId: String)
    fun stepDone(takeoverId: String, index: Int): StepResult
    fun dismiss(takeoverId: String)
    fun notOkay(takeoverId: String): BadDayResult
    fun call()

    /** The hidden developer kill switch (seven taps on the eye). */
    fun suspend(minutes: Long)

    /** The takeover's closing screen has finished; show the next one or close. */
    fun finished()
}

private enum class Phase { BLACK, BEAT, OPENING, TITLE, CHECKLIST, CLOSING, DONE, ABANDONED, BYPASSED }

/**
 * One takeover, from the black beat to the closing screen.
 *
 * [initial] is the ticket as it was when the takeover appeared; [live] is the same ticket as the engine
 * currently has it (steps done, lock start), or null once the engine has closed it.
 */
@Composable
fun TakeoverScreen(
    initial: Ticket,
    live: Ticket?,
    directive: Directive,
    fx: TakeoverFx,
    callbacks: TakeoverCallbacks,
) {
    val id = initial.takeoverId
    val title = remember(directive) { directive.title.uppercase() }
    val resume = initial.stepsDone > 0 || initial.stepShownAtMs != null
    var phase by remember { mutableStateOf(if (resume) Phase.CHECKLIST else Phase.BLACK) }
    var eyeMode by remember { mutableStateOf(if (resume) EyeMode.OPEN else EyeMode.HIDDEN) }
    var eyeModeAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var titleShown by remember { mutableIntStateOf(if (resume) title.length else 0) }
    var closedAt by remember { mutableLongStateOf(0L) }
    var essentialNote by remember { mutableStateOf(false) }
    var eyeTaps by remember { mutableIntStateOf(0) }
    // The last state the engine reported, kept (outside Compose state) for the frame in which it closes the takeover.
    val lastLiveHolder = remember { arrayOf(initial) }
    if (live != null) lastLiveHolder[0] = live
    val lastLive = live ?: lastLiveHolder[0]
    val cb by rememberUpdatedState(callbacks)

    fun setEye(mode: EyeMode) {
        eyeMode = mode
        eyeModeAt = System.currentTimeMillis()
    }

    // The intro: black, heartbeat, the eye opens, the directive types out.
    LaunchedEffect(id) {
        if (resume) {
            fx.startDrone()
            fx.waiting()
            return@LaunchedEffect
        }
        delay(
            when (directive.tier) {
                1 -> 300L
                2 -> 800L
                else -> 1400L
            },
        )
        phase = Phase.BEAT
        setEye(EyeMode.SLIT)
        if (directive.tier == 1) {
            fx.tick()
            delay(400)
        } else {
            repeat(if (directive.tier >= 3) 2 else 1) {
                fx.heartbeat()
                delay(850)
            }
        }
        phase = Phase.OPENING
        setEye(EyeMode.OPENING)
        fx.startDrone()
        delay(OPEN_FRAMES * FRAME_MS + 200)
        setEye(EyeMode.OPEN)
        phase = Phase.TITLE
        for (i in 1..title.length) {
            titleShown = i
            if (i % 2 == 0) fx.blip()
            delay(55)
        }
        delay(350)
        phase = Phase.CHECKLIST
        fx.waiting()
    }

    // The engine closed this takeover for another reason (timed out, plan replaced, bad day from elsewhere).
    LaunchedEffect(live == null, phase) {
        if (live == null && phase <= Phase.CHECKLIST) {
            fx.stop()
            cb.finished()
        }
    }

    // Closing screens stay briefly, then hand over. Completion first lets the eye close.
    LaunchedEffect(phase) {
        val hold = when (phase) {
            Phase.CLOSING -> {
                delay(OPEN_FRAMES * FRAME_MS + 150)
                phase = Phase.DONE
                return@LaunchedEffect
            }
            Phase.DONE -> 3200L
            Phase.ABANDONED -> 2200L
            Phase.BYPASSED -> 4000L
            else -> return@LaunchedEffect
        }
        delay(hold)
        cb.finished()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        when (phase) {
            Phase.DONE -> {
                ClosingScreen(
                    headline = "DONE.",
                    lines = listOf(
                        "$title · ${Format.duration((closedAt - initial.firedAtMs).coerceAtLeast(0) / 1000)}",
                        directive.why,
                    ),
                    accent = Palette.White,
                    onTap = { cb.finished() },
                )
                return@Column
            }
            Phase.ABANDONED -> {
                ClosingScreen("ABANDONED.", listOf("Logged, not hidden.", "The next one is a fresh start."), Palette.White) { cb.finished() }
                return@Column
            }
            Phase.BYPASSED -> {
                ClosingScreen(
                    "OKAY.",
                    listOf(
                        "Today is lighter. Only the essentials will come back until the day ends.",
                        "This is not a failure. Rest, and come back tomorrow.",
                    ),
                    Palette.White,
                ) { cb.finished() }
                return@Column
            }
            else -> Unit
        }

        // Everything above the controls scrolls, so a long checklist never pushes CALL off the screen.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.2f)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { eyeTaps++ },
                contentAlignment = Alignment.Center,
            ) {
                Eye(eyeMode, directive.tier, eyeModeAt, Modifier.fillMaxSize())
            }
            if (eyeTaps >= 7) {
                // The developer kill switch, reachable even while a takeover holds the screen.
                PixelButton("KILL SWITCH: SUSPEND VIGIL FOR 1 HOUR", { cb.suspend(60) }, Modifier.fillMaxWidth(), color = Palette.Red)
                Spacer(Modifier.height(8.dp))
            }

            if (phase >= Phase.TITLE) {
                PixelText(title.take(titleShown), Type.Title)
                Spacer(Modifier.height(12.dp))
            }

            if (phase == Phase.CHECKLIST) {
                Checklist(
                    directive = directive,
                    ticket = lastLive,
                    onShown = { if (live != null) cb.stepShown(id) },
                    onTick = { index ->
                        when (cb.stepDone(id, index)) {
                            StepResult.COMPLETED -> {
                                closedAt = System.currentTimeMillis()
                                setEye(EyeMode.CLOSING)
                                fx.completed()
                                phase = Phase.CLOSING
                            }
                            StepResult.NEXT, StepResult.IGNORED -> Unit
                        }
                    },
                    onBlip = { fx.blip() },
                )
            }

            if (phase >= Phase.TITLE) {
                Spacer(Modifier.height(16.dp))
                PixelText(directive.why, Type.Small, color = Palette.White.copy(alpha = 0.8f))
                Spacer(Modifier.height(12.dp))
            }
        }

        if (phase < Phase.CHECKLIST) {
            // Calls are never blocked, not even during the intro.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PixelButton("CALL", { cb.call() }, color = Palette.Dim)
            }
        }

        if (phase == Phase.CHECKLIST) {
            HoldButton(
                text = "HOLD 10S TO ABANDON",
                holdMillis = 10_000,
                onComplete = {
                    cb.dismiss(id)
                    fx.dismissed()
                    phase = Phase.ABANDONED
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldButton(
                    text = "NOT OKAY TODAY",
                    holdMillis = 3_000,
                    onComplete = {
                        if (cb.notOkay(id) == BadDayResult.BYPASSED) {
                            fx.stop()
                            phase = Phase.BYPASSED
                        } else {
                            essentialNote = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                    color = Palette.Dim,
                )
                PixelButton("CALL", { cb.call() }, color = Palette.Dim)
            }
            if (essentialNote) {
                PixelText("Today is lighter now, but this one is on your essentials list. It stays.", Type.Small)
            }
        }
    }
}

/** Opening frames in the sprite sheet (res/values/eye_sheet.xml); the intro waits for them to play. */
private const val OPEN_FRAMES = 12

@Composable
private fun Checklist(
    directive: Directive,
    ticket: Ticket,
    onShown: () -> Unit,
    onTick: (Int) -> Unit,
    onBlip: () -> Unit,
) {
    val current = ticket.stepsDone
    for ((index, step) in directive.steps.withIndex()) {
        when {
            index < current -> StepRow(step.text, StepBox.Checked, label = null, onClick = null)
            index == current -> CurrentStep(directive, ticket, index, onShown, onTick, onBlip)
            else -> Unit // Later steps stay hidden until their turn.
        }
    }
    PixelText("${current.coerceAtMost(directive.steps.size)}/${directive.steps.size}", Type.Small)
}

@Composable
private fun CurrentStep(
    directive: Directive,
    ticket: Ticket,
    index: Int,
    onShown: () -> Unit,
    onTick: (Int) -> Unit,
    onBlip: () -> Unit,
) {
    val text = directive.steps[index].text
    val alreadyShown = ticket.stepShownAtMs != null
    var typed by remember(ticket.takeoverId, index) { mutableIntStateOf(if (alreadyShown) text.length else 0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(ticket.takeoverId, index) {
        while (typed < text.length) {
            typed++
            if (typed % 3 == 0) onBlip()
            delay(28)
        }
        onShown()
    }
    LaunchedEffect(ticket.takeoverId, index) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }

    val ready = typed >= text.length && ticket.stepShownAtMs != null
    val remaining = if (ready) Engine.lockRemainingSeconds(ticket, directive, now) else directive.steps[index].minSeconds.toLong()
    val box = when {
        !ready -> StepBox.Hidden
        remaining > 0 -> StepBox.Locked
        else -> StepBox.Open
    }
    val label = when (box) {
        StepBox.Locked -> "LOCKED ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}"
        StepBox.Open -> "TAP WHEN DONE"
        else -> null
    }
    StepRow(text.take(typed), box, label, onClick = if (box == StepBox.Open) ({ onTick(index) }) else null)
}

private enum class StepBox { Hidden, Locked, Open, Checked }

@Composable
private fun StepRow(text: String, box: StepBox, label: String?, onClick: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelCheckbox(box)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            PixelText(text, Type.Body, color = if (box == StepBox.Checked) Palette.Dim else Palette.White)
            if (label != null) PixelText(label, Type.Small, color = if (box == StepBox.Open) Palette.White else Palette.Dim)
        }
    }
}

/** Square pixel checkbox: dashed-dim while locked, white when it can be ticked, filled when done. */
@Composable
private fun PixelCheckbox(box: StepBox) {
    val border: Color = when (box) {
        StepBox.Hidden -> Color.Transparent
        StepBox.Locked -> Palette.Faint
        StepBox.Open, StepBox.Checked -> Palette.White
    }
    Box(
        Modifier
            .size(30.dp)
            .border(3.dp, border, RectangleShape)
            .padding(7.dp),
    ) {
        if (box == StepBox.Checked) Box(Modifier.fillMaxSize().background(Palette.White))
    }
}

@Composable
private fun ClosingScreen(headline: String, lines: List<String>, accent: Color, onTap: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxSize()
            .clickable(interaction, indication = null, onClick = onTap),
        verticalArrangement = Arrangement.Center,
    ) {
        PixelText(headline, Type.Huge, color = accent)
        Spacer(Modifier.height(16.dp))
        for (line in lines) {
            PixelText(line, Type.Body)
            Spacer(Modifier.height(8.dp))
        }
    }
}
