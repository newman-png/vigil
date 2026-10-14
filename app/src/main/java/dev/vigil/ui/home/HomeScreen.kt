package dev.vigil.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.core.engine.PlanSource
import dev.vigil.core.plan.DayMode
import dev.vigil.engine.Vigil
import dev.vigil.ui.Format
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.tap
import dev.vigil.ui.plan.planStatusLine
import dev.vigil.ui.rememberNow
import dev.vigil.ui.setup.missingPermissions
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type
import java.time.Instant

/** Where the home screen leads. Quiet mode is switched right here, so it has no action. */
class HomeActions(
    val openSetup: () -> Unit,
    val openPlan: () -> Unit,
    /** TEST; true after seven taps on the title, which also reveals the kill switch. */
    val openTest: (revealKillSwitch: Boolean) -> Unit,
    /** Opens the takeover that is waiting (it does not come back by itself over VIGIL's own screens). */
    val openTakeover: () -> Unit,
)

/**
 * Exactly four items: SETUP, PLAN, QUIET and TEST, each with its state on the right. Above them the plan's
 * status, a line of flags when a mode is on, and a red line when a directive is waiting.
 *
 * [refreshKey] changes on every resume, so the permission count is re-read after a visit to system settings.
 */
@Composable
fun HomeScreen(refreshKey: Int, actions: HomeActions) {
    val context = LocalContext.current
    val snapshot by Graph.plans.snapshot.collectAsState()
    val state by Graph.state.state.collectAsState()
    val settingsVersion by Graph.settings.version.collectAsState()
    val now = rememberNow()

    // The plan source also changes by the clock alone (expiry), hence [now].
    val active = remember(snapshot, state, now) { Graph.activeSet(Instant.ofEpochMilli(now)) }
    val dayMode = remember(snapshot, state, now) { Vigil.dayMode() }
    val planComplete = remember(snapshot, state, settingsVersion, now) { Vigil.planComplete() }
    val suspendedUntil = remember(settingsVersion) { Graph.settings.suspendedUntilMs }
    val missing = remember(refreshKey) { missingPermissions(context) }

    var titleTaps by remember { mutableIntStateOf(0) }
    var lastTapMs by remember { mutableLongStateOf(0L) }

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(32.dp))
        PixelText(
            "VIGIL",
            Type.Logo,
            Modifier.tap {
                // Seven taps in a row (each within 1.5 s of the last) open TEST with the kill switch.
                val t = System.currentTimeMillis()
                titleTaps = if (t - lastTapMs > TAP_GAP_MS) 1 else titleTaps + 1
                lastTapMs = t
                if (titleTaps >= 7) {
                    titleTaps = 0
                    actions.openTest(true)
                }
            },
        )
        Spacer(Modifier.height(4.dp))
        PixelText(planStatusLine(active), Type.Label, color = Palette.White)

        val flags = buildList {
            if (state.quiet) add("QUIET")
            when (dayMode) {
                DayMode.BAD_DAY -> add("NOT OKAY TODAY")
                DayMode.MINIMUM -> add("MINIMUM DAY")
                DayMode.NORMAL -> Unit
            }
            if (suspendedUntil > now) add("SUSPENDED UNTIL ${Format.clock(suspendedUntil, Graph.zone)}")
        }
        if (flags.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            PixelText(flags.joinToString(" · "), Type.Label, color = Palette.White)
        }

        if (state.active != null) {
            Spacer(Modifier.height(12.dp))
            PixelText(
                "A DIRECTIVE IS WAITING · OPEN",
                Type.Body,
                Modifier.fillMaxWidth().tap(onClick = actions.openTakeover).padding(vertical = 8.dp),
                color = Palette.Red,
            )
        }

        Spacer(Modifier.height(40.dp))
        Item(
            "SETUP",
            hint = if (missing > 0) "$missing MISSING" else null,
            hintColor = Palette.Red,
            onClick = actions.openSetup,
        )
        val planHint = when {
            active.source == PlanSource.PLAN -> active.plan?.let { "WEEK ${Format.day(it.weekOf)}" }
            planComplete -> "COMPLETE"
            else -> "IMPORT"
        }
        Item(
            "PLAN",
            hint = planHint,
            // A stored plan that can no longer be read needs a new import.
            hintColor = if (snapshot.loadError != null) Palette.Red else Palette.Dim,
            onClick = actions.openPlan,
        )
        val held = state.quietQueue.size
        Item(
            "QUIET",
            hint = if (!state.quiet) "OFF" else if (held > 0) "ON · $held HELD" else "ON",
            hintColor = if (state.quiet) Palette.White else Palette.Dim,
            onClick = { Vigil.setQuiet(!state.quiet) },
        )
        Item("TEST", hint = null, onClick = { actions.openTest(false) })
        Line()
        Spacer(Modifier.height(24.dp))
    }
}

/** One of the four: a large uppercase word, its state on the right. */
@Composable
private fun Item(label: String, hint: String?, hintColor: Color = Palette.Dim, onClick: () -> Unit) {
    Line()
    Row(
        Modifier
            .fillMaxWidth()
            .tap(onClick = onClick)
            .padding(vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelText(label, Type.Item, Modifier.weight(1f))
        if (hint != null) {
            Spacer(Modifier.width(12.dp))
            PixelText(hint, Type.Label, color = hintColor)
        }
    }
}

@Composable
private fun Line() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Faint))
}

private const val TAP_GAP_MS = 1_500L
