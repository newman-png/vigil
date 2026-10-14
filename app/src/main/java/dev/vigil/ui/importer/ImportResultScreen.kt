package dev.vigil.ui.importer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.core.plan.Issue
import dev.vigil.data.PlanRepository
import dev.vigil.ui.Format
import dev.vigil.ui.components.Label
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type
import java.time.LocalDateTime

/** Shown after picking a plan file: what was imported, or why it was rejected. */
@Composable
fun ImportResultScreen(outcome: PlanRepository.ImportOutcome, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        when (outcome) {
            is PlanRepository.ImportOutcome.Accepted -> {
                val p = outcome.plan
                PixelText("PLAN ACCEPTED", Type.Title)
                Spacer(Modifier.height(12.dp))
                PixelText("WEEK OF ${Format.day(p.weekOf)}", Type.Label, color = Palette.White)
                PixelText("EXPIRES ${Format.dateTime(p.expires)}", Type.Label, color = Palette.White)
                p.generatedBy?.let { PixelText("BY ${it.uppercase()}", Type.Label) }
                if (!LocalDateTime.now(Graph.zone).isBefore(p.expires)) {
                    Spacer(Modifier.height(8.dp))
                    PixelText(
                        "This plan has already expired, so VIGIL is running its " +
                            "${if (p.fallbackPlan != null) "fallback plan" else "built-in minimal plan"}.",
                        Type.Small,
                        color = Palette.Red,
                    )
                }
                Spacer(Modifier.height(12.dp))
                val byTier = p.directives.groupingBy { it.tier }.eachCount()
                PixelText(
                    "${p.directives.size} DIRECTIVES · T1 ${byTier[1] ?: 0} · T2 ${byTier[2] ?: 0} · T3 ${byTier[3] ?: 0}",
                    Type.Body,
                )
                PixelText(
                    "BAD DAY KEEPS ${p.badDay.keep.size} · MINIMUM ${p.minimum?.keep?.size ?: 0} · " +
                        "FALLBACK ${p.fallbackPlan?.directives?.size ?: 0}",
                    Type.Label,
                )
                if (p.afterBadDay != null) PixelText("MORNING CHECK-IN AFTER A HARD DAY: ON", Type.Label)
                p.context?.let {
                    Spacer(Modifier.height(10.dp))
                    PixelText(it, Type.Small)
                }
                IssueList("WARNINGS", outcome.warnings, Palette.White)
            }
            is PlanRepository.ImportOutcome.Rejected -> {
                PixelText("PLAN REJECTED", Type.Title, color = Palette.Red)
                Spacer(Modifier.height(12.dp))
                PixelText("NOTHING CHANGED. THE PREVIOUS PLAN IS STILL RUNNING.", Type.Label, color = Palette.White)
                PixelText("Fix these problems (or send them to your planner) and import again.", Type.Small)
                IssueList("PROBLEMS", outcome.errors, Palette.Red)
                IssueList("WARNINGS", outcome.warnings, Palette.White)
            }
        }
        Spacer(Modifier.height(24.dp))
        PixelButton("OK", onDone, Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun IssueList(title: String, issues: List<Issue>, accent: Color) {
    if (issues.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Label("$title (${issues.size})", color = accent)
    for (issue in issues) {
        Column(Modifier.padding(vertical = 4.dp)) {
            if (issue.path.isNotEmpty()) PixelText(issue.path, Type.Label)
            PixelText(issue.message, Type.Small, color = Palette.White)
        }
    }
}
