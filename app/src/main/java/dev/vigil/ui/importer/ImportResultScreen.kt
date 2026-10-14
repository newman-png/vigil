package dev.vigil.ui.importer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.vigil.core.plan.Issue
import dev.vigil.data.PlanRepository
import dev.vigil.ui.Format
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.Rule
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type

/** Shown after picking a plan file: what was imported, or why it was rejected. */
@Composable
fun ImportResultScreen(outcome: PlanRepository.ImportOutcome, onDone: () -> Unit) {
    Column {
        when (outcome) {
            is PlanRepository.ImportOutcome.Accepted -> {
                val p = outcome.plan
                PixelText("PLAN ACCEPTED", Type.Title)
                Spacer(Modifier.height(8.dp))
                PixelText("WEEK OF ${p.weekOf}", Type.Body)
                PixelText("EXPIRES ${Format.dateTime(p.expires)}", Type.Body)
                if (!java.time.LocalDateTime.now().isBefore(p.expires)) {
                    PixelText(
                        "This plan has already expired, so VIGIL is running its ${if (p.fallbackPlan != null) "fallback plan" else "built-in minimal plan"}.",
                        Type.Body,
                        color = Palette.Red,
                    )
                }
                p.generatedBy?.let { PixelText("BY $it", Type.Small) }
                Spacer(Modifier.height(8.dp))
                val byTier = p.directives.groupingBy { it.tier }.eachCount()
                PixelText(
                    "${p.directives.size} DIRECTIVES  ·  T1 ${byTier[1] ?: 0}  T2 ${byTier[2] ?: 0}  T3 ${byTier[3] ?: 0}",
                    Type.Body,
                )
                PixelText("BAD DAY KEEPS ${p.badDay.keep.size}  ·  FALLBACK ${p.fallbackPlan?.directives?.size ?: 0}", Type.Small)
                p.context?.let {
                    Spacer(Modifier.height(8.dp))
                    PixelText(it, Type.Small)
                }
                IssueList("WARNINGS", outcome.warnings, Palette.White)
            }
            is PlanRepository.ImportOutcome.Rejected -> {
                PixelText("PLAN REJECTED", Type.Title, color = Palette.Red)
                Spacer(Modifier.height(8.dp))
                PixelText("Nothing changed. Your previous plan is still active.", Type.Body)
                PixelText("Fix these problems (or send them to your planner) and import again:", Type.Small)
                IssueList("PROBLEMS", outcome.errors, Palette.Red)
                IssueList("WARNINGS", outcome.warnings, Palette.White)
            }
        }
        Spacer(Modifier.height(16.dp))
        PixelButton("OK", onDone, Modifier.fillMaxWidth())
    }
}

@Composable
private fun IssueList(title: String, issues: List<Issue>, accent: androidx.compose.ui.graphics.Color) {
    if (issues.isEmpty()) return
    Rule()
    PixelText("$title (${issues.size})", Type.Heading, color = accent)
    for (issue in issues) {
        Column(Modifier.padding(vertical = 4.dp)) {
            if (issue.path.isNotEmpty()) PixelText(issue.path, Type.Small)
            PixelText(issue.message, Type.Body)
        }
    }
}
