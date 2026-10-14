package dev.vigil.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.vigil.Graph
import dev.vigil.core.plan.Trigger
import dev.vigil.core.plan.UsageLimit
import dev.vigil.engine.Device
import dev.vigil.trigger.UsageWatcher
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.Rule
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type
import kotlinx.coroutines.delay

/** Settings sections contributed by triggers other than adhan. */
@Composable
fun TriggerSettings(appLabel: (String) -> String) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tick++
        }
    }

    Rule()
    PixelText("APP USAGE", Type.Heading)
    val mode = when {
        UsageWatcher.accessibilityConnected -> "INSTANT (ACCESSIBILITY)"
        Device.hasUsageAccess(context) -> "EVERY ~5 SECONDS (USAGE ACCESS)"
        else -> "OFF: GRANT USAGE ACCESS IN SETUP"
    }
    PixelText("DETECTION: $mode", Type.Body, color = if (mode.startsWith("OFF")) Palette.Red else Palette.White)
    // Re-read the live counts every couple of seconds.
    key(tick) {
        UsageWatcher.foreground?.let { PixelText("IN FRONT NOW: ${appLabel(it)}", Type.Small) }
        val usage = Graph.activeSet().directives.mapNotNull { d -> (d.trigger as? Trigger.AppUsage)?.let { d to it } }
        if (usage.isEmpty()) PixelText("The active plan has no app_usage directives.", Type.Small)
        for ((d, t) in usage) {
            val status = when (val l = t.limit) {
                is UsageLimit.Opens -> "${UsageWatcher.openCount(d.id)}/${l.opens} opens in ${l.windowMin} min"
                is UsageLimit.Continuous -> "fires after ${l.minutes} min nonstop"
            }
            PixelText("${d.title.uppercase()}: $status · cooldown ${t.cooldownMin} min", Type.Small)
        }
    }
}
