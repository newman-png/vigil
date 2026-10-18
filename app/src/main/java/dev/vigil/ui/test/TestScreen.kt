package dev.vigil.ui.test

import android.widget.Toast
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.core.journal.Entry
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.pool.PoolText
import dev.vigil.core.protocol.Protocol
import dev.vigil.engine.Device
import dev.vigil.engine.Vigil
import dev.vigil.trigger.TestAlarm
import dev.vigil.ui.Format
import dev.vigil.ui.components.Label
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.ScreenTitle
import dev.vigil.ui.components.TierPips
import dev.vigil.ui.components.stagePips
import dev.vigil.ui.isInstalled
import dev.vigil.ui.rememberAppLabel
import dev.vigil.ui.rememberNow
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Try things out: start any protocol now, a real alarm with the screen off, what the pool would do for a pool app, and
 * the newest journal entries. Every protocol started here is a TEST preview (stage 1, journaled as a test, no stages,
 * penalties, prayer memory or report counts). There are no kill switch buttons here: its only way in is the 5-minute
 * wait (seven taps on the HOME title or the takeover's eye).
 */
@Composable
fun TestScreen() {
    val context = LocalContext.current
    val state by Vigil.state.collectAsState()
    val now = rememberNow(5_000L)
    val zone = Graph.zone
    val file = remember { Vigil.file() }
    val status = remember(state, now) { Vigil.status() }
    val appLabel = rememberAppLabel()
    val suspendedUntil = state.suspendedUntil?.takeIf { it.isAfter(Instant.ofEpochMilli(now)) }

    // The journal is a file: read it off the main thread, and again every few seconds while this screen shows.
    val events by produceState(emptyList<Entry>()) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { Vigil.recentEntries(RECENT) }.getOrDefault(emptyList()) }
            delay(3_000)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        ScreenTitle(
            "TEST",
            "Protocols started here are TEST previews: stage 1, logged as tests, and left out of stages, penalties, " +
                "prayer times and the report.",
        )
        if (suspendedUntil != null) {
            PixelText("VIGIL IS SUSPENDED UNTIL ${Format.clock(suspendedUntil, zone)}: NOTHING WILL SHOW.", Type.Label, color = Palette.Red)
            Spacer(Modifier.height(8.dp))
        }
        status.activeRun?.let { owner ->
            val title = file.find(owner.protocol)?.title ?: owner.protocol
            PixelText("${title.uppercase()} OWNS THE SCREEN: A PREVIEW WAITS BEHIND IT.", Type.Label, color = Palette.White)
            Spacer(Modifier.height(8.dp))
        }

        Label("START NOW · ${file.protocols.size} PROTOCOLS")
        for (p in file.protocols) {
            FireRow(p, status.stages[p.id] ?: 1) { Vigil.startManual(p.id) }
        }

        Spacer(Modifier.height(12.dp))
        Label("REAL ALARM")
        val alarmProtocol = remember(state) { TestAlarm.defaultProtocol()?.let { id -> file.find(id)?.title ?: id } }
        PixelButton(
            "FIRE IN 60 S (LOCK THE PHONE)",
            {
                TestAlarm.schedule(context, 60)
                val what = alarmProtocol?.let { "A preview of $it" } ?: "A preview"
                Toast.makeText(context, "$what starts in 60 s. Lock the phone now.", Toast.LENGTH_LONG).show()
            },
            Modifier.fillMaxWidth(),
            enabled = alarmProtocol != null,
        )
        PixelText(
            "A real exact alarm starts a preview of ${alarmProtocol?.uppercase() ?: "the next prayer"}: it tries the whole " +
                "path with the screen off and the phone locked.",
            Type.Small,
        )
        Spacer(Modifier.height(8.dp))
        PixelButton(
            "CANCEL THE TEST ALARM",
            {
                TestAlarm.cancel(context)
                Toast.makeText(context, "Test alarm cancelled.", Toast.LENGTH_SHORT).show()
            },
            Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        Label("POOL CHECK")
        PixelText(status.pool, Type.Label, color = Palette.White)
        PixelText("What the pool would do right now if you opened one of these:", Type.Small)
        PoolCheck(appLabel)

        Spacer(Modifier.height(12.dp))
        Label("LAST EVENTS")
        if (events.isEmpty()) PixelText("Nothing journaled yet.", Type.Small)
        for (e in events.asReversed()) {
            PixelText(
                Format.entry(e),
                Type.Label,
                Modifier.padding(vertical = 2.dp),
                color = if (e.test == true) Palette.Dim else Palette.White,
                maxLines = 2,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One protocol: title, kind, trigger and stage, with START. */
@Composable
private fun FireRow(p: Protocol, stage: Int, onFire: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TierPips(stagePips(stage))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            PixelText(p.title.uppercase(), Type.Body)
            PixelText("${p.kind.json.uppercase()} · ${p.trigger.uppercase()} · STAGE $stage", Type.Label)
        }
        Spacer(Modifier.width(8.dp))
        PixelButton("START", onFire)
    }
}

/** The pool apps on this phone (installed listed apps and browsers); CHECK asks the engine what it would do. */
@Composable
private fun PoolCheck(appLabel: (String) -> String) {
    val context = LocalContext.current
    val zone = Graph.zone
    val apps = remember {
        val pool = Vigil.file().pool
        val listed = pool.apps.filter { isInstalled(context, it) }
        val browsers = if (pool.allBrowsers) Device.browsers(context).sorted() else emptyList()
        (listed + browsers).distinct().ifEmpty { pool.apps }
    }
    var checked by remember { mutableStateOf<Pair<String, PoolDecision>?>(null) }

    for (pkg in apps) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelText(appLabel(pkg).uppercase(), Type.Body, Modifier.weight(1f), maxLines = 1)
            Spacer(Modifier.width(8.dp))
            PixelButton("CHECK", { checked = pkg to Vigil.poolDecision(pkg) })
        }
        val result = checked
        if (result != null && result.first == pkg) {
            val decision = result.second
            PixelText(Format.decision(decision, zone), Type.Label, color = Palette.White)
            if (decision is PoolDecision.Blocked && decision.blocks.size > 1) {
                PixelText(
                    "ALSO: " + decision.blocks.drop(1).joinToString(" · ") { PoolText.block(it, zone) },
                    Type.Label,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
    PixelText("A check only asks; it never opens the pool or starts a wait.", Type.Small)
}

private const val RECENT = 30
