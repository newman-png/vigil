package dev.vigil.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.core.engine.ActiveSet
import dev.vigil.core.engine.Engine
import dev.vigil.core.engine.PlanSource
import dev.vigil.core.engine.RuntimeState
import dev.vigil.engine.Vigil
import dev.vigil.ui.components.HoldButton
import dev.vigil.core.plan.Directive
import dev.vigil.ui.Format
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.Rule
import dev.vigil.ui.components.TierPips
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type

/** What the user can do from the home screen. */
class HomeActions(
    val importPlan: () -> Unit,
    val fireDirective: (String) -> Unit,
    val demoTakeover: () -> Unit,
    val openSetup: () -> Unit,
    /** Hidden: seven taps on the title. */
    val openDeveloper: () -> Unit,
    val openSettings: () -> Unit,
    val openLog: () -> Unit,
    /** Opens the takeover that is waiting (it does not come back by itself over VIGIL's own screens). */
    val openTakeover: () -> Unit,
)

@Composable
fun HomeScreen(actions: HomeActions, appLabel: (String) -> String) {
    val snapshot by Graph.plans.snapshot.collectAsState()
    // Re-read when the runtime state changes so the plan source (plan / fallback / default) stays current.
    val state by Graph.state.state.collectAsState()
    val active = remember(snapshot, state) { Graph.activeSet() }

    var titleTaps by remember { mutableIntStateOf(0) }
    val settingsVersion by Graph.settings.version.collectAsState()

    Column {
        PixelText(
            "VIGIL",
            Type.Huge,
            Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
                titleTaps++
                if (titleTaps >= 7) {
                    titleTaps = 0
                    actions.openDeveloper()
                }
            },
        )
        PixelText("THE APP NEVER THINKS. IT ONLY EXECUTES.", Type.Small)
        Spacer(Modifier.height(16.dp))

        if (state.active != null) {
            PixelText("A DIRECTIVE IS WAITING", Type.Heading, color = Palette.Red)
            Spacer(Modifier.height(6.dp))
            PixelButton("OPEN IT", actions.openTakeover, Modifier.fillMaxWidth(), color = Palette.Red)
            Spacer(Modifier.height(16.dp))
        }

        val suspendedUntil = remember(settingsVersion) { Graph.settings.suspendedUntilMs }
        if (suspendedUntil > System.currentTimeMillis()) {
            PixelText("SUSPENDED BY THE KILL SWITCH", Type.Heading, color = Palette.Red)
            PixelText("No takeovers until ${java.time.Instant.ofEpochMilli(suspendedUntil).atZone(Graph.zone).toLocalTime().withNano(0)}.", Type.Small)
            Spacer(Modifier.height(12.dp))
        }

        val plan = snapshot.plan
        when (active.source) {
            PlanSource.PLAN -> {
                PixelText("PLAN · WEEK OF ${plan!!.weekOf}", Type.Heading)
                PixelText("EXPIRES ${Format.dateTime(plan.expires)}", Type.Small)
            }
            PlanSource.FALLBACK -> {
                PixelText("PLAN EXPIRED · RUNNING FALLBACK", Type.Heading, color = Palette.Red)
                PixelText("Import next week's plan.", Type.Small)
            }
            PlanSource.DEFAULT -> {
                PixelText(if (plan == null) "NO PLAN IMPORTED" else "PLAN EXPIRED", Type.Heading, color = Palette.Red)
                PixelText("Running the built-in minimal plan: prayers, water, sleep.", Type.Small)
            }
        }
        snapshot.loadError?.let { PixelText(it, Type.Small, color = Palette.Red) }
        val needsAdhanApp = remember(settingsVersion, active) {
            Graph.settings.adhanPackages.isEmpty() && active.directives.any { it.trigger is dev.vigil.core.plan.Trigger.Adhan }
        }
        if (needsAdhanApp) {
            PixelText("Pick your adhan app in SETTINGS: until then any app saying \"time for Isha\" counts, even a chat message.", Type.Small, color = Palette.Red)
        }
        if (snapshot.warnings.isNotEmpty() && active.source == PlanSource.PLAN) {
            PixelText("${snapshot.warnings.size} WARNINGS AT IMPORT", Type.Small)
        }

        Spacer(Modifier.height(12.dp))
        PixelButton("IMPORT PLAN", actions.importPlan, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        PixelButton("SETUP & PERMISSIONS", actions.openSetup, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        PixelButton("SETTINGS", actions.openSettings, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        PixelButton("LOG & EXPORT", actions.openLog, Modifier.fillMaxWidth())

        Rule()
        Modes(state, active)

        Rule()
        PixelText("DIRECTIVES", Type.Heading)
        Spacer(Modifier.height(4.dp))
        for (d in active.directives) DirectiveRow(d, appLabel, onFire = { actions.fireDirective(d.id) })

        Rule()
        PixelText("DEBUG", Type.Heading)
        PixelText("Tap a directive above to fire it now, or try the demo.", Type.Small)
        Spacer(Modifier.height(8.dp))
        PixelButton("DEMO TAKEOVER", actions.demoTakeover, Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }
}

/** Quiet mode and "I'm not okay today". */
@Composable
private fun Modes(state: RuntimeState, active: ActiveSet) {
    PixelText("MODES", Type.Heading)
    Spacer(Modifier.height(4.dp))
    val held = state.quietQueue.size
    PixelButton(
        if (state.quiet) "QUIET MODE: ON${if (held > 0) " · $held HELD" else ""}" else "QUIET MODE: OFF",
        { Vigil.setQuiet(!state.quiet) },
        Modifier.fillMaxWidth(),
    )
    PixelText(
        if (state.quiet) "Directives arrive silently, wait until quiet ends, or are skipped, as the plan says."
        else "Turn on in class, meetings or prayer. Directives follow their quiet rule.",
        Type.Small,
    )
    Spacer(Modifier.height(12.dp))
    val env = remember(state, active) { Graph.env() }
    if (Engine.isBadDay(state, env)) {
        PixelText("NOT OKAY TODAY", Type.Body)
        PixelText(
            "Until ${active.dayBoundary} only the essentials fire (${active.badDayKeep.size} kept). Not a failure. Rest.",
            Type.Small,
        )
        Spacer(Modifier.height(6.dp))
        PixelButton("I'M OKAY AGAIN", { Vigil.setBadDay(false) }, Modifier.fillMaxWidth())
    } else {
        HoldButton("I'M NOT OKAY TODAY", holdMillis = 3_000, onComplete = { Vigil.setBadDay(true) }, modifier = Modifier.fillMaxWidth())
        PixelText("Hold 3 s. For the rest of the day only the plan's essentials fire. It is not logged as a failure.", Type.Small)
    }
}

@Composable
private fun DirectiveRow(d: Directive, appLabel: (String) -> String, onFire: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TierPips(d.tier)
                Spacer(Modifier.width(8.dp))
                PixelText(d.title.uppercase(), Type.Body)
            }
            PixelText(Format.trigger(d.trigger, appLabel), Type.Small)
        }
        Spacer(Modifier.width(8.dp))
        PixelButton("FIRE", onFire)
    }
}
