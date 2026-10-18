package dev.vigil.guard

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.vigil.core.common.WallClock
import dev.vigil.core.pool.Block
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.pool.PoolText
import dev.vigil.core.pool.PoolWait
import dev.vigil.core.protocol.HeavyUseConfig
import dev.vigil.takeover.Eye
import dev.vigil.takeover.EyeMode
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * One Guard screen ([GuardMode]): a label at the top, the pixel eye, the big line (a countdown or the reason), its
 * details, a short note and one button. [onHome] goes home (wait, blocked, heavy use); [onClose] just closes (the
 * kill switch). Everything shown comes from the engine; the words for reasons are the core's ([PoolText]).
 */
@Composable
internal fun GuardScreen(
    request: GuardRequest,
    appLabel: String,
    now: Instant,
    wait: PoolWait?,
    opensToday: Int,
    decision: PoolDecision?,
    killLeftMs: Long,
    suspendedUntil: Instant?,
    zone: ZoneId,
    heavyUse: HeavyUseConfig,
    onHome: () -> Unit,
    onClose: () -> Unit,
) {
    val app = appLabel.uppercase()
    val text = when (request.mode) {
        GuardMode.WAIT -> waitText(app, now, wait, opensToday)
        GuardMode.BLOCKED -> blockedText(app, now, decision, zone)
        GuardMode.ENOUGH -> enoughText(app, now, decision, zone, heavyUse)
        GuardMode.KILL -> killText(killLeftMs, suspendedUntil, zone)
    }
    val clock = rememberGuardClock()
    // The eye starts afresh for each screen; it is already open (a hard cut, no opening animation).
    val eyeSince = remember(request, text.eye) { SystemClock.uptimeMillis() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Line(text.label, Label)
            Spacer(Modifier.weight(1f))
            Eye(
                mode = text.eye,
                modeStartedAt = eyeSince,
                tier = text.tier,
                clock = clock,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp),
            )
            Spacer(Modifier.height(32.dp))
            Line(text.headline, if (text.countdown) Countdown else if (text.urgent) Headline.copy(color = Red) else Headline)
            for (detail in text.details) {
                Spacer(Modifier.height(12.dp))
                Line(detail, Detail)
            }
            Spacer(Modifier.weight(1f))
            if (text.note.isNotEmpty()) {
                Line(text.note, Note)
                Spacer(Modifier.height(20.dp))
            }
            GuardButton(text.button, if (text.goesHome) onHome else onClose)
        }
    }
}

/** What one Guard screen says. */
private data class GuardText(
    val label: String,
    val headline: String,
    /** The headline is a countdown (large digits). */
    val countdown: Boolean = false,
    /** The headline in red (heavy use: an urgent state). */
    val urgent: Boolean = false,
    val details: List<String> = emptyList(),
    val note: String = "",
    val button: String = "HOME",
    val goesHome: Boolean = true,
    val eye: EyeMode = EyeMode.OPEN,
    val tier: Int = 1,
)

private fun waitText(app: String, now: Instant, wait: PoolWait?, opensToday: Int): GuardText {
    val left = wait?.let { w -> Duration.between(now, w.startedAt.plusSeconds(w.minutes * 60L)).seconds.coerceAtLeast(0L) }
    return GuardText(
        label = "WAIT · $app",
        headline = left?.let { clockText(it) } ?: "-:--",
        countdown = true,
        details = listOf("OPEN NO. ${opensToday + 1} TODAY"),
        note = "STAY ON THIS SCREEN. AT ZERO $app OPENS. LEAVING MEANS THE SAME WAIT NEXT TIME.",
        button = "NOT NOW",
    )
}

private fun blockedText(app: String, now: Instant, decision: PoolDecision?, zone: ZoneId): GuardText {
    val blocked = decision as? PoolDecision.Blocked ?: return openText(app)
    val first = blocked.blocks.first()
    val closed = first.reason == BlockReason.OUTSIDE_WINDOW || first.reason == BlockReason.WINDOW_CAP
    return GuardText(
        label = (if (closed) "POOL CLOSED" else "POOL BLOCKED") + " · $app",
        headline = PoolText.reason(first),
        details = listOf(untilText(first, now, zone)) + blocked.blocks.drop(1).map { "ALSO ${PoolText.block(it, zone)}" },
        note = noteFor(first.reason),
    )
}

private fun enoughText(app: String, now: Instant, decision: PoolDecision?, zone: ZoneId, heavyUse: HeavyUseConfig): GuardText {
    val blocks = (decision as? PoolDecision.Blocked)?.blocks.orEmpty()
    val cooldown = blocks.firstOrNull { it.reason == BlockReason.HEAVY_USE && it.until != null }
    val details = when {
        cooldown != null -> listOf("COOLDOWN · ${untilText(cooldown, now, zone)}")
        blocks.isNotEmpty() -> listOf(PoolText.block(blocks.first(), zone))
        else -> emptyList()
    }
    return GuardText(
        label = "HEAVY USE · $app",
        headline = "ENOUGH FOR NOW",
        urgent = true,
        details = details,
        note = "${heavyUse.continuousMin} MINUTES IN A ROW, OR ${heavyUse.opens} OPENS WITHIN ${heavyUse.withinMin} MINUTES. " +
            "THE COOLDOWN STARTED EARLY.",
        tier = 3,
    )
}

private fun killText(leftMs: Long, suspendedUntil: Instant?, zone: ZoneId): GuardText =
    if (suspendedUntil == null) {
        GuardText(
            label = "KILL SWITCH",
            headline = clockText((leftMs + 999L) / 1000L),
            countdown = true,
            details = listOf("THEN VIGIL IS OFF FOR AN HOUR"),
            note = "NO TAKEOVERS AND THE POOL OPEN FOR 60 MINUTES. STAY ON THIS SCREEN: LEAVING IT CANCELS.",
            button = "CANCEL",
            goesHome = false,
        )
    } else {
        GuardText(
            label = "KILL SWITCH",
            headline = "VIGIL IS OFF",
            details = listOf("UNTIL ${WallClock.hhmm(suspendedUntil, zone)}"),
            note = "NO TAKEOVERS, THE POOL IS OPEN. VIGIL COMES BACK BY ITSELF.",
            button = "OK",
            goesHome = false,
            eye = EyeMode.SLIT,
        )
    }

/** The blocked screens once the engine no longer blocks (a cooldown ran out while the screen was up). */
private fun openText(app: String) = GuardText(
    label = "POOL · $app",
    headline = "NOT BLOCKED NOW",
    details = listOf("OPEN THE APP AGAIN TO START ITS WAIT"),
)

/** How long a block lasts, e.g. "24 MIN · UNTIL 14:20", or when the next free window opens. */
private fun untilText(block: Block, now: Instant, zone: ZoneId): String {
    val until = block.until
    return when (block.reason) {
        BlockReason.OUTSIDE_WINDOW, BlockReason.WINDOW_CAP ->
            if (until != null) "NEXT WINDOW ${WallClock.hhmm(until, zone)} · IN ${minutesText(now, until)}" else "NO MORE WINDOWS TODAY"
        BlockReason.FRIENDS, BlockReason.WALKING ->
            until?.let { "${minutesText(now, it)} · UNTIL ${WallClock.hhmm(it, zone)}" } ?: "UNTIL YOU SWITCH IT OFF"
        else -> until?.let { "${minutesText(now, it)} · UNTIL ${WallClock.hhmm(it, zone)}" } ?: "UNTIL IT ENDS"
    }
}

/** Minutes left, rounded up: "24 MIN", "2 H 05 MIN". */
private fun minutesText(now: Instant, until: Instant): String {
    val minutes = WallClock.minutesCeil(now, until)
    return if (minutes < 120) "$minutes MIN" else "${minutes / 60} H ${(minutes % 60).toString().padStart(2, '0')} MIN"
}

/** Seconds as m:ss. */
private fun clockText(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

private fun noteFor(reason: BlockReason): String = when (reason) {
    BlockReason.RUN_LOCK -> "A PROTOCOL IS RUNNING. THE POOL STAYS SHUT UNTIL ITS LOCK ENDS."
    BlockReason.FRIENDS -> "NO WAIT OPTION WHILE YOU'RE WITH FRIENDS."
    BlockReason.WALKING -> "LOOK AROUND. THE POOL OPENS WHEN THE WALK ENDS."
    BlockReason.NIGHT_LOCK -> "THE NIGHT LOCK HOLDS UNTIL YOUR WAKE ALARM."
    BlockReason.OUTSIDE_WINDOW -> "THE POOL OPENS ONLY IN A FREE WINDOW."
    BlockReason.WINDOW_CAP -> "THIS WINDOW'S MINUTES ARE USED UP."
    BlockReason.COOLDOWN -> "AFTER A SESSION THE POOL RESTS FOR LONGER THAN YOU USED IT."
    BlockReason.PENALTY -> "LEAVING A PROTOCOL EARLY KEEPS THE POOL SHUT FOR LONGER."
    BlockReason.HEAVY_USE -> "THE COOLDOWN AFTER HEAVY USE."
}

// ------------------------------------------------------------------ look (black, white, red; monospace, wide tracking)

private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val Red = Color(0xFFFF2B2B)
private val Dim = White.copy(alpha = 0.55f)

private fun mono(size: Int, line: Int, tracking: Double, color: Color = White) = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
    color = color,
    textAlign = TextAlign.Center,
)

private val Label = mono(12, 18, 0.24, Dim)
private val Countdown = mono(64, 72, 0.08)
private val Headline = mono(28, 36, 0.14)
private val Detail = mono(15, 22, 0.18)
private val Note = mono(12, 18, 0.12, Dim)
private val ButtonText = mono(15, 20, 0.22)

@Composable
private fun Line(text: String, style: TextStyle) {
    BasicText(text = text, modifier = Modifier.fillMaxWidth(), style = style)
}

/** A thin-bordered button; a tap is a hard cut (no ripple, no highlight). */
@Composable
private fun GuardButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, White, RectangleShape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = text, style = ButtonText)
    }
}

/** The eye's clock (uptime), moved on about 30 times a second while the screen is drawn. */
@Composable
private fun rememberGuardClock(): LongState {
    val clock = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            clock.longValue = SystemClock.uptimeMillis()
            delay(CLOCK_TICK_MS)
        }
    }
    return clock
}

private const val CLOCK_TICK_MS = 33L
