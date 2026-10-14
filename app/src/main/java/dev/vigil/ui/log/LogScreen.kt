package dev.vigil.ui.log

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.core.log.EventType
import dev.vigil.core.log.LogEvent
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.Rule
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The event log, newest first, and the export buttons for the weekly hand-back to the planner. */
@Composable
fun LogScreen(exportWeek: () -> Unit, exportAll: () -> Unit, onBack: () -> Unit) {
    // Read off the main thread: the log only grows.
    val events by produceState(initialValue = emptyList<LogEvent>()) {
        value = withContext(Dispatchers.IO) { Graph.eventLog.readAll() }
    }
    Column {
        PixelText("LOG", Type.Title)
        PixelText("Export at the end of the week and give log.json to your planner with vision.md and the last plan.", Type.Small)
        Spacer(Modifier.height(12.dp))
        PixelButton("EXPORT THIS WEEK", exportWeek, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        PixelButton("EXPORT EVERYTHING", exportAll, Modifier.fillMaxWidth())
        Rule()
        PixelText("${events.size} EVENTS", Type.Heading)
        for (e in events.asReversed().take(60)) {
            val time = e.ts.substringAfter('T').take(5)
            val day = e.ts.take(10)
            val what = listOfNotNull(
                e.type.uppercase().replace('_', ' '),
                e.directiveId,
                e.trigger?.let { "($it)" },
                e.stepIndex?.let { "step ${it + 1}" },
                e.durationS?.let { "${it}s" },
            ).joinToString(" ")
            val color = when (e.type) {
                EventType.DIRECTIVE_DISMISSED, EventType.DIRECTIVE_TIMED_OUT, EventType.MISSED, EventType.PLAN_IMPORT_FAILED -> Palette.Red
                EventType.STEP_COMPLETED -> Palette.Dim
                else -> Palette.White
            }
            PixelText("$day $time  $what", Type.Small, color = color)
            e.detail?.let { PixelText("    $it", Type.Small, color = Palette.Faint) }
        }
        Spacer(Modifier.height(16.dp))
        PixelButton("BACK", onBack, Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }
}
