package dev.vigil.ui.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.ui.Format
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type

/**
 * The week's plan has expired: one button, EXPORT, for the weekly bundle the planner needs to write the next
 * one. Shown when VIGIL opens with an unexported, finished plan, and from the "plan complete" notification.
 */
@Composable
fun PlanCompleteScreen(export: () -> Unit) {
    val snapshot by Graph.plans.snapshot.collectAsState()
    val state by Graph.state.state.collectAsState()
    val active = remember(snapshot, state) { Graph.activeSet() }

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(56.dp))
        PixelText("PLAN COMPLETE", Type.Huge)
        snapshot.plan?.let {
            Spacer(Modifier.height(6.dp))
            PixelText("WEEK OF ${Format.day(it.weekOf)}", Type.Label, color = Palette.White)
        }
        Spacer(Modifier.height(28.dp))
        PixelText(runningInstead(active), Type.Body)
        Spacer(Modifier.height(10.dp))
        PixelText(
            "Export the week's bundle: the plan, its log, your ratings and check-ins in one file. Give it to your planner " +
                "with vision.md, then import the next plan from PLAN.",
            Type.Small,
        )
        Spacer(Modifier.height(36.dp))
        PixelButton("EXPORT", export, Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }
}
