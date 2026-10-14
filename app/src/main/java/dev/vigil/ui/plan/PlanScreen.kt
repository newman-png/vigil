package dev.vigil.ui.plan

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.core.plan.Directive
import dev.vigil.ui.Format
import dev.vigil.ui.components.Label
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.ScreenTitle
import dev.vigil.ui.components.Section
import dev.vigil.ui.components.TierPips
import dev.vigil.ui.rememberAppLabel
import dev.vigil.ui.rememberNow
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type
import java.time.Instant

/**
 * The week's plan: its status, IMPORT PLAN, EXPORT BUNDLE (the week so far) and the directives running now,
 * with EASIER on those that stepped down to their easier variant.
 */
@Composable
fun PlanScreen(importPlan: () -> Unit, exportBundle: () -> Unit) {
    val snapshot by Graph.plans.snapshot.collectAsState()
    val state by Graph.state.state.collectAsState()
    val settingsVersion by Graph.settings.version.collectAsState()
    val now = rememberNow()
    val active = remember(snapshot, state, now) { Graph.activeSet(Instant.ofEpochMilli(now)) }
    val lastExport = remember(settingsVersion) { Graph.settings.lastBundleExportAtMs }
    val appLabel = rememberAppLabel()
    val plan = snapshot.plan

    Column(Modifier.fillMaxWidth()) {
        ScreenTitle("PLAN", "Written by your planner, executed here. The app never decides anything about the week.")

        if (plan != null) {
            Fact("WEEK OF", Format.day(plan.weekOf))
            Fact("EXPIRES", Format.dateTime(plan.expires))
            plan.generatedBy?.let { Fact("BY", it.uppercase()) }
        } else {
            Fact("PLAN", "NONE IMPORTED")
        }
        Fact("RUNNING", sourceName(active.source))
        snapshot.importedAtMs?.let { at ->
            Fact("IMPORTED", Format.dateTime(at, Graph.zone))
        }
        snapshot.fileName?.let { Fact("FILE", it) }
        snapshot.loadError?.let {
            Spacer(Modifier.height(6.dp))
            PixelText(it, Type.Small, color = Palette.Red)
        }

        if (snapshot.warnings.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Section("WARNINGS", hint = "${snapshot.warnings.size}") {
                for (issue in snapshot.warnings) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        if (issue.path.isNotEmpty()) PixelText(issue.path, Type.Label)
                        PixelText(issue.message, Type.Small, color = Palette.White)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        PixelButton("IMPORT PLAN", importPlan, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        PixelButton("EXPORT BUNDLE", exportBundle, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        PixelText(
            "The week so far in one file: the plan, its log, your ratings and check-ins. Give it to your planner with vision.md.",
            Type.Small,
        )
        if (lastExport > 0) PixelText("LAST EXPORT ${Format.dateTime(lastExport, Graph.zone)}", Type.Label)

        Spacer(Modifier.height(20.dp))
        Label("RUNNING · ${active.directives.size} DIRECTIVES")
        for (d in active.directives) {
            DirectiveRow(d, easier = d.id in state.steppedDown && d.easier != null, appLabel = appLabel)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One status line: a dim key and its value. */
@Composable
private fun Fact(key: String, value: String, color: Color = Palette.White) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        PixelText(key, Type.Label, Modifier.width(96.dp))
        PixelText(value, Type.Label, Modifier.weight(1f), color = color)
    }
}

@Composable
private fun DirectiveRow(d: Directive, easier: Boolean, appLabel: (String) -> String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TierPips(d.tier)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            PixelText(d.title.uppercase(), Type.Body)
            PixelText(Format.trigger(d.trigger, appLabel), Type.Label)
        }
        if (easier) {
            Spacer(Modifier.width(8.dp))
            PixelText("EASIER", Type.Label, color = Palette.White)
        }
    }
}
