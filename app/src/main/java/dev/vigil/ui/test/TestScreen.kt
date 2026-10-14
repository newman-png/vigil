package dev.vigil.ui.test

import android.widget.Toast
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.core.engine.Engine
import dev.vigil.core.log.LogEvent
import dev.vigil.core.plan.Directive
import dev.vigil.engine.Vigil
import dev.vigil.trigger.TestAlarm
import dev.vigil.ui.Format
import dev.vigil.ui.components.Label
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.ScreenTitle
import dev.vigil.ui.components.TierPips
import dev.vigil.ui.rememberAppLabel
import dev.vigil.ui.rememberNow
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Try things out: the three tiers, the morning check-in, a real alarm with the screen off, and every running
 * directive. Everything fired from here is logged as a test and left out of the bundle's summary.
 * The kill switch shows after seven taps on the home title ([revealKillSwitch]) and while it is on.
 */
@Composable
fun TestScreen(revealKillSwitch: Boolean) {
    val context = LocalContext.current
    val snapshot by Graph.plans.snapshot.collectAsState()
    val state by Graph.state.state.collectAsState()
    val settingsVersion by Graph.settings.version.collectAsState()
    val now = rememberNow(5_000L)
    val active = remember(snapshot, state, now) { Graph.activeSet(Instant.ofEpochMilli(now)) }
    val suspendedUntil = remember(settingsVersion) { Graph.settings.suspendedUntilMs }
    val suspended = suspendedUntil > now
    val appLabel = rememberAppLabel()

    // The log is a file: read it off the main thread, and again every few seconds while this screen shows.
    val events by produceState(emptyList<LogEvent>()) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { Graph.eventLog.tail(12) }.getOrDefault(emptyList()) }
            delay(3_000)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        ScreenTitle("TEST", "Logged as tests (trigger \"debug\") and left out of the bundle's summary.")

        Label("TIER PREVIEW")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PixelButton("1", { Vigil.fireNow(Engine.DEMO_1_ID) }, Modifier.weight(1f))
            PixelButton("2", { Vigil.fireNow(Engine.DEMO_2_ID) }, Modifier.weight(1f))
            PixelButton("3", { Vigil.fireNow(Engine.DEMO_3_ID) }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        PixelButton("CHECK-IN PREVIEW", { Vigil.fireNow(Engine.CHECK_IN_ID) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        PixelButton(
            "FIRE IN 60 S (LOCK THE PHONE)",
            {
                TestAlarm.schedule(context, 60)
                Toast.makeText(context, "Tier-2 preview in 60 s. Lock the phone now.", Toast.LENGTH_LONG).show()
            },
            Modifier.fillMaxWidth(),
        )
        PixelText("A real alarm: tries the whole path with the screen off and the phone locked.", Type.Small)
        if (suspended) {
            PixelText("THE KILL SWITCH IS ON: NOTHING WILL SHOW.", Type.Label, color = Palette.Red)
        }

        Spacer(Modifier.height(12.dp))
        Label("RUNNING · ${active.directives.size} DIRECTIVES")
        for (d in active.directives) {
            FireRow(d, appLabel) { Vigil.fireNow(d.id) }
        }

        if (revealKillSwitch || suspended) {
            Spacer(Modifier.height(12.dp))
            Label("KILL SWITCH")
            PixelText(
                if (suspended) "SUSPENDED UNTIL ${Format.dateTime(suspendedUntil, Graph.zone)}" else "OFF",
                Type.Label,
                color = if (suspended) Palette.Red else Palette.White,
            )
            PixelText("Stops every takeover and drops whatever is showing. For emergencies and development, not for skipping directives.", Type.Small)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PixelButton("SUSPEND 1 H", { Vigil.suspend(60) }, Modifier.weight(1f))
                PixelButton("SUSPEND 24 H", { Vigil.suspend(24 * 60) }, Modifier.weight(1f))
            }
            if (suspended) {
                Spacer(Modifier.height(10.dp))
                PixelButton("RESUME", { Vigil.resume() }, Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(12.dp))
        Label("LAST EVENTS")
        if (events.isEmpty()) PixelText("Nothing logged yet.", Type.Small)
        for (e in events.asReversed()) {
            PixelText(Format.event(e), Type.Label, Modifier.padding(vertical = 2.dp), color = Palette.White, maxLines = 1)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FireRow(d: Directive, appLabel: (String) -> String, onFire: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TierPips(d.tier)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            PixelText(d.title.uppercase(), Type.Body)
            PixelText(Format.trigger(d.trigger, appLabel), Type.Label)
        }
        Spacer(Modifier.width(8.dp))
        PixelButton("FIRE", onFire)
    }
}
