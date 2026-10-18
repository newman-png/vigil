package dev.vigil.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.core.machine.HomeStatus
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.run.Run
import dev.vigil.core.run.RunPhase
import dev.vigil.core.timeline.StartSource
import dev.vigil.core.timeline.StartStatus
import dev.vigil.core.timeline.TimelineItem
import dev.vigil.data.ReportExporter
import dev.vigil.engine.Vigil
import dev.vigil.guard.GuardScreens
import dev.vigil.ui.Format
import dev.vigil.ui.components.Label
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.TierPips
import dev.vigil.ui.components.stagePips
import dev.vigil.ui.components.tap
import dev.vigil.ui.rememberNow
import dev.vigil.ui.setup.missingPermissions
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/** Where the home screen leads. QUIET, GOING TO SLEEP and EXPORT act right here, so they have no action. */
class HomeActions(
    val openMyTimes: () -> Unit,
    val openSetup: () -> Unit,
    val openTest: () -> Unit,
    /** Opens the takeover of the run that owns the screen (it does not come back by itself over VIGIL's own screens). */
    val openTakeover: () -> Unit,
)

/**
 * HOME: the day, the pool, the flags, a red line while a protocol owns the screen, prayers waiting for CONFIRM and
 * today's timeline (with when each protocol last fired); then QUIET, GOING TO SLEEP, MY TIMES, SETUP, TEST and EXPORT.
 * Everything shown comes from the engine ([Vigil.status], [Vigil.timeline]); nothing is decided here.
 *
 * [refreshKey] changes on every resume, so the permission count is re-read after a visit to system settings.
 */
@Composable
fun HomeScreen(refreshKey: Int, actions: HomeActions) {
    val context = LocalContext.current
    val state by Vigil.state.collectAsState()
    // Minutes left, windows opening and planned starts going by change with the clock alone.
    val now = rememberNow()
    val status = remember(state, now) { Vigil.status() }
    val timeline = remember(state, now) { Vigil.timeline() }
    val missing = remember(refreshKey) { missingPermissions(context) }
    val zone = Graph.zone
    val nowAt = Instant.ofEpochMilli(now)

    var titleTaps by remember { mutableIntStateOf(0) }
    var lastTapMs by remember { mutableLongStateOf(0L) }
    var exporting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(32.dp))
        PixelText(
            "VIGIL",
            Type.Logo,
            Modifier.tap {
                // Seven taps in a row (each within 1.5 s of the last) open the kill switch's 5-minute wait.
                val t = System.currentTimeMillis()
                titleTaps = if (t - lastTapMs > TAP_GAP_MS) 1 else titleTaps + 1
                lastTapMs = t
                if (titleTaps >= 7) {
                    titleTaps = 0
                    val until = status.suspendedUntil
                    if (until != null) {
                        Toast.makeText(context, "VIGIL is already suspended until ${Format.clock(until, zone)}.", Toast.LENGTH_SHORT).show()
                    } else {
                        GuardScreens.killSwitch(context)
                    }
                }
            },
        )
        Spacer(Modifier.height(4.dp))
        PixelText(dayLine(status, zone), Type.Label, color = Palette.White)
        PixelText(status.pool, Type.Label, color = Palette.White)

        val flags = buildList {
            if (status.quiet) add("QUIET")
            status.suspendedUntil?.let { add("SUSPENDED UNTIL ${Format.clock(it, zone)}") }
            if (status.callActive) add("IN A CALL · TIMERS PAUSED")
            if (status.walking) add("WALKING")
            if (status.friends) add("WITH FRIENDS")
        }
        if (flags.isNotEmpty()) PixelText(flags.joinToString(" · "), Type.Label, color = Palette.White)
        prayersLine(status)?.let { PixelText(it, Type.Label) }
        status.nextStart?.let { PixelText("NEXT · ${startTime(it, zone)} ${it.start.title.uppercase()}", Type.Label) }

        val owner = status.activeRun
        if (owner != null) {
            Spacer(Modifier.height(12.dp))
            PixelText(
                "A PROTOCOL IS RUNNING · OPEN",
                Type.Body,
                Modifier.fillMaxWidth().tap(onClick = actions.openTakeover).padding(vertical = 8.dp),
                color = Palette.Red,
            )
        }
        val others = status.runs.filter { it.id != owner?.id && (it.phase == RunPhase.ACTIVE || it.phase == RunPhase.QUEUED) }
        if (others.isNotEmpty()) {
            PixelText(
                others.joinToString(" · ") { (if (it.phase == RunPhase.QUEUED) "WAITING: " else "RUNNING: ") + title(it) },
                Type.Label,
            )
        }

        // Prayers waiting for CONFIRM (lock over, a card during class, a reminder), and stage-3 reminders to mark done.
        val open = status.toConfirm + status.runs.filter { it.prayer == null && it.phase == RunPhase.REMINDER }
        if (open.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Label("OPEN")
            for (r in open) OpenRow(r, status, zone)
        }

        Spacer(Modifier.height(12.dp))
        Label("TODAY")
        if (timeline.isEmpty()) {
            PixelText("Nothing planned yet. A prayer shows here once its adhan has come at least once.", Type.Small)
        }
        for (item in timeline) TimelineRow(item, status.stages[item.start.protocolId] ?: 1, nowAt, zone)

        Spacer(Modifier.height(32.dp))
        Item(
            "QUIET",
            hint = if (status.quiet) "ON · TAKEOVERS SILENT" else "OFF",
            hintColor = if (status.quiet) Palette.White else Palette.Dim,
            onClick = { Vigil.setQuiet(!status.quiet) },
        )
        HoldItem(
            "GOING TO SLEEP",
            hint = "HOLD 2 S · ENDS THE DAY",
            holdMillis = 2_000,
            onComplete = {
                // Before the day's own wake time the engine keeps the day going (it only journals the tap as skipped).
                val before = Vigil.state.value.day?.startedAt
                Vigil.goingToSleep()
                val ended = Vigil.state.value.day?.startedAt != before
                val text = if (ended) "The day has ended. Sleep well." else "It is before today's wake time, so the day goes on."
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            },
        )
        Item(
            "MY TIMES",
            hint = if (state.times.pending != null) "CHANGED · FROM TOMORROW" else if (!state.times.edited) "NOT SET" else null,
            hintColor = if (!state.times.edited) Palette.Red else Palette.Dim,
            onClick = actions.openMyTimes,
        )
        Item(
            "SETUP",
            hint = if (missing > 0) "$missing MISSING" else null,
            hintColor = Palette.Red,
            onClick = actions.openSetup,
        )
        Item("TEST", hint = null, onClick = actions.openTest)
        Item(
            "EXPORT",
            hint = if (exporting) "PREPARING..." else "REPORT + JOURNAL",
            onClick = {
                if (!exporting) {
                    exporting = true
                    ReportExporter.share(context) { opened ->
                        exporting = false
                        if (!opened) Toast.makeText(context, "Could not export the report.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
        Line()
        Spacer(Modifier.height(24.dp))
    }
}

/** SUN 18 OCT · SINCE 06:12 · ENDS BY 06:00 */
private fun dayLine(status: HomeStatus, zone: ZoneId): String =
    "${Format.dayKey(status.day)} · SINCE ${Format.clock(status.dayStartedAt, zone)} · ENDS BY ${Format.clock(status.dayEndsBy, zone)}"

/** PRAYED FAJR, DHUHR · MISSED ASR, or null before anything happened today. */
private fun prayersLine(status: HomeStatus): String? {
    val parts = buildList {
        if (status.confirmedToday.isNotEmpty()) add("PRAYED " + status.confirmedToday.joinToString(", ") { it.title })
        if (status.missedToday.isNotEmpty()) add("MISSED " + status.missedToday.joinToString(", ") { it.title })
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** A planned start's time; a prayer whose adhan has not come yet today is an estimate (~). */
private fun startTime(item: TimelineItem, zone: ZoneId): String =
    (if (item.start.estimated) "~" else "") + Format.clock(item.start.at, zone)

private fun title(r: Run): String = (Vigil.file().find(r.protocol)?.title ?: r.prayer?.title ?: r.protocol).uppercase()

/** A run waiting for CONFIRM (prayers) or DONE (a stage-3 reminder), with where it stands. */
@Composable
private fun OpenRow(r: Run, status: HomeStatus, zone: ZoneId) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            PixelText(title(r), Type.Body)
            val where = when (r.phase) {
                RunPhase.CARD -> "IN CLASS · THE TAKEOVER COMES WHEN IT ENDS"
                RunPhase.RETURNED -> "WINDOW CLOSING · THE TAKEOVER IS BACK"
                RunPhase.REMINDER -> "REMINDER"
                else -> "LOCK OVER · NOT CONFIRMED"
            }
            val window = when {
                r.prayer == null -> null
                r.prayer == Prayer.ISHA -> "UNTIL THE DAY ENDS (${Format.clock(status.dayEndsBy, zone)})"
                r.windowEstimated -> r.windowEndsAt?.let { "UNTIL ~${Format.clock(it, zone)}" }
                else -> null
            }
            PixelText(listOfNotNull(where, window).joinToString(" · "), Type.Label)
        }
        Spacer(Modifier.width(12.dp))
        PixelButton(if (r.prayer != null) "CONFIRM" else "DONE", { Vigil.confirm(r.id) })
    }
}

/** One planned start: time, title, stage pips, what happened, and when that protocol last fired. */
@Composable
private fun TimelineRow(item: TimelineItem, stage: Int, now: Instant, zone: ZoneId) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelText(startTime(item, zone), Type.Body, Modifier.width(64.dp))
            PixelText(item.start.title.uppercase(), Type.Body, Modifier.weight(1f), maxLines = 1)
            Spacer(Modifier.width(8.dp))
            TierPips(stagePips(stage))
            Spacer(Modifier.width(8.dp))
            val (text, color) = when (item.status) {
                StartStatus.PLANNED -> (if (item.start.source == StartSource.ADHAN) "AT ADHAN" else "PLANNED") to Palette.Dim
                StartStatus.FIRED -> "FIRED" to Palette.White
                StartStatus.SKIPPED -> "SKIPPED" to Palette.Dim
                StartStatus.NOT_FIRED -> "NOT FIRED" to Palette.Red
            }
            PixelText(text, Type.Label, color = color)
        }
        val last = item.lastFired?.let { "LAST FIRED ${Format.ago(it, now, zone)}" } ?: "NEVER FIRED"
        PixelText(last, Type.Label, Modifier.padding(start = 64.dp), color = Palette.Faint)
    }
}

/** A large uppercase word with its state on the right. */
@Composable
private fun Item(label: String, hint: String?, hintColor: Color = Palette.Dim, onClick: () -> Unit) {
    Line()
    Row(
        Modifier
            .fillMaxWidth()
            .tap(onClick = onClick)
            .padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelText(label, Type.Item, Modifier.weight(1f))
        if (hint != null) {
            Spacer(Modifier.width(12.dp))
            PixelText(hint, Type.Label, color = hintColor)
        }
    }
}

/**
 * Like [Item], but it fires only after being held for [holdMillis]: while held, the hint counts down the seconds
 * left; letting go early does nothing.
 */
@Composable
private fun HoldItem(label: String, hint: String, holdMillis: Long, onComplete: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    val complete by rememberUpdatedState(onComplete)

    LaunchedEffect(holding) {
        if (!holding) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (holding) {
            val left = holdMillis - (System.currentTimeMillis() - start)
            if (left <= 0) {
                holding = false
                complete()
                break
            }
            secondsLeft = ((left + 999) / 1000).toInt()
            delay(50)
        }
    }

    Line()
    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(holdMillis) {
                awaitEachGesture {
                    awaitFirstDown()
                    secondsLeft = ((holdMillis + 999) / 1000).toInt()
                    holding = true
                    waitForUpOrCancellation()
                    holding = false
                }
            }
            .padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelText(label, Type.Item, Modifier.weight(1f), color = if (holding) Palette.Red else Palette.White)
        Spacer(Modifier.width(12.dp))
        PixelText(if (holding) "KEEP HOLDING · $secondsLeft" else hint, Type.Label, color = if (holding) Palette.White else Palette.Dim)
    }
}

@Composable
private fun Line() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Faint))
}

private const val TAP_GAP_MS = 1_500L
