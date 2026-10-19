package dev.vigil.ui.test

import android.content.Context
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
import dev.vigil.core.machine.JummahTestPart
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.pool.PoolText
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Protocol
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.run.Run
import dev.vigil.core.run.RunPhase
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
 * Try things out: start any protocol now (the walk, friends, a call card and a nudge have their own row; one without a
 * screen lock, like the morning, a meal or the gym, opens its takeover at once), a real alarm
 * with the screen off, the wake alarm, the AUDIO screen, what the pool would do for a pool app, and the newest journal
 * entries. Every protocol started here is a TEST preview (stage 1, journaled as a
 * test, no stages, penalties, prayer memory or report counts). There are no kill switch buttons here: its only way in
 * is the 5-minute wait (seven taps on the HOME title or the takeover's eye).
 */
@Composable
fun TestScreen(openAudio: () -> Unit) {
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
            FireRow(p, status.stages[p.id] ?: 1) {
                // A promise needs its line and minutes: a 1-minute TEST promise.
                if (p.kind == ProtocolKind.PROMISE) testPromise(context) else Vigil.startManual(p.id)?.let { r -> showPreview(context, r) }
            }
        }

        if (file.protocols.any { it.kind == ProtocolKind.JUMMAH }) {
            Spacer(Modifier.height(12.dp))
            Label("JUMMAH · EACH PART NOW")
            for ((label, part) in JUMMAH_PARTS) {
                PixelButton(label, { Vigil.testJummah(part) }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
            PixelText(
                "Each part as a TEST preview with the real screens. The lock turns Do Not Disturb on (LEAVE turns it off " +
                    "at no cost); the card is a silent notification.",
                Type.Small,
            )
        }
        val m8 = listOf(ProtocolKind.WALK to "WALK", ProtocolKind.FRIENDS to "FRIENDS", ProtocolKind.CALL to "CALL CARD", ProtocolKind.NUDGE to "NUDGE")
            .mapNotNull { (kind, label) -> file.protocols.firstOrNull { it.kind == kind }?.let { it to label } }
        if (m8.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Label("WALK · FRIENDS · CALL · NUDGE")
            for (pair in m8.chunked(2)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    pair.forEachIndexed { i, (p, label) ->
                        if (i > 0) Spacer(Modifier.width(8.dp))
                        PixelButton(label, { Vigil.startManual(p.id) }, Modifier.weight(1f))
                    }
                }
            }
            PixelText(
                "TEST previews. The walk and friends show their notification for 2 minutes and block nothing (the switches on " +
                    "HOME are the real thing). The call card is the real full card (CALL, NOT NOW, DONE after a 3-minute call); " +
                    "it is missed after an hour. A nudge shows now, unless a protocol holds the screen.",
                Type.Small,
            )
        }
        Spacer(Modifier.height(12.dp))
        Label("AUDIO")
        PixelButton("OPEN AUDIO", openAudio, Modifier.fillMaxWidth())
        PixelText("White noise, the metronome and your own files, with the screen locked too.", Type.Small)

        if (file.protocols.any { it.kind == ProtocolKind.PROMISE }) {
            Spacer(Modifier.height(12.dp))
            Label("10-MINUTE PROMISE")
            PixelButton("1-MINUTE PROMISE", { testPromise(context) }, Modifier.fillMaxWidth())
            PixelText("A TEST promise due in 1 minute: its full takeover, SNOOZE, ON IT and DONE.", Type.Small)
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
        Label("WAKE ALARM")
        PixelButton(
            "RING WAKE ALARM NOW",
            {
                Vigil.testWakeAlarm()
                Toast.makeText(context, "The wake alarm rings now (TEST). Hold I'M UP for 3 s to stop it.", Toast.LENGTH_LONG).show()
            },
            Modifier.fillMaxWidth(),
        )
        PixelText(
            "The real alarm screen and sound, on the alarm stream. Holding I'M UP starts a preview of the morning; " +
                "nothing else changes. " +
                (status.wakeAlarmAt?.let { "The next real alarm rings ${Format.dayTime(it, zone)}." } ?: "VIGIL rings no wake alarm."),
            Type.Small,
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

/**
 * A preview without a screen lock (the morning, a meal, the gym) only posts its quiet notification, so its takeover is
 * opened here, as the wake alarm opens the morning's. One with a screen lock shows by itself; the walk and friends
 * previews are their notification.
 */
private fun showPreview(context: Context, run: Run) {
    if (run.phase != RunPhase.ACTIVE || run.lock == LockType.SCREEN || run.kind == ProtocolKind.WALK || run.kind == ProtocolKind.FRIENDS) return
    Vigil.openTakeover(context, run.id)
}

/** A 1-minute TEST promise (it counts for nothing). */
private fun testPromise(context: Context) {
    val notMade = Vigil.promise("TEST: this is your line", 1, test = true)
    Toast.makeText(context, notMade ?: "A TEST promise is due in 1 minute.", Toast.LENGTH_SHORT).show()
}

/** TEST > JUMMAH: its parts, in order. */
private val JUMMAH_PARTS = listOf(
    "LEAVE-BY" to JummahTestPart.LEAVE,
    "I'M AT UNI" to JummahTestPart.ARRIVAL,
    "CLASS-END CARD" to JummahTestPart.CARD,
    "LOCK (DO NOT DISTURB)" to JummahTestPart.LOCK,
)

private const val RECENT = 30
