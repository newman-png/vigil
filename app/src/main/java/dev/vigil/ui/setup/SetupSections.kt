package dev.vigil.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.core.engine.ActiveSet
import dev.vigil.core.engine.AdhanMatcher
import dev.vigil.core.plan.DayMode
import dev.vigil.core.plan.Prayer
import dev.vigil.core.plan.Trigger
import dev.vigil.core.plan.UsageLimit
import dev.vigil.engine.Device
import dev.vigil.engine.KillSwitchReceiver
import dev.vigil.engine.Vigil
import dev.vigil.glyph.GlyphFactory
import dev.vigil.trigger.AdhanListener
import dev.vigil.trigger.UsageWatcher
import dev.vigil.ui.Format
import dev.vigil.ui.components.Field
import dev.vigil.ui.components.HoldButton
import dev.vigil.ui.components.Label
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.Toggle
import dev.vigil.ui.components.tap
import dev.vigil.ui.rememberAppLabel
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type
import kotlinx.coroutines.delay

/** ADHAN: which app announces the prayers, the words that count, a test, and what the listener has seen. */
@Composable
internal fun AdhanSection() {
    val s = Graph.settings
    val version by s.version.collectAsState()
    val recent by AdhanListener.recent.collectAsState()
    val connected by AdhanListener.connected.collectAsState()
    val appLabel = rememberAppLabel()
    // Text fields keep what is being typed; they only reload from settings after a reset.
    var resets by remember { mutableIntStateOf(0) }

    PixelText(
        "VIGIL fires adhan directives when your adhan app posts its prayer notification. It never calculates prayer times itself.",
        Type.Small,
    )
    Spacer(Modifier.height(8.dp))
    PixelText(
        if (connected) "LISTENER CONNECTED" else "LISTENER NOT CONNECTED: GRANT NOTIFICATION ACCESS IN PERMISSIONS",
        Type.Label,
        color = if (connected) Palette.White else Palette.Red,
    )

    val packages = remember(version) { s.adhanPackages }
    Label("ADHAN APP")
    if (packages.isEmpty()) {
        PixelText("No app picked: any app's notification saying \"time for Isha\" counts, even a chat message. Pick your adhan app below.", Type.Small)
    } else {
        for (pkg in packages) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PixelText(appLabel(pkg).uppercase(), Type.Body)
                    PixelText(pkg, Type.Small)
                }
                Spacer(Modifier.width(12.dp))
                PixelButton("REMOVE", { s.adhanPackages = packages - pkg })
            }
        }
    }
    val candidates = recent.map { it.packageName }.distinct().filter { it !in packages }.take(6)
    if (candidates.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        PixelText("Apps that posted notifications recently. Tap yours:", Type.Small)
        for (pkg in candidates) {
            PixelText(
                "+ ${appLabel(pkg).uppercase()}",
                Type.Body,
                Modifier
                    .fillMaxWidth()
                    .tap { s.adhanPackages = packages + pkg }
                    .padding(vertical = 8.dp),
            )
        }
    } else {
        PixelText("Once your adhan app posts a notification it appears here so you can pick it.", Type.Small)
    }

    Label("PHRASE BEFORE THE PRAYER NAME")
    Field(s.adhanPhrase, resets) { s.adhanPhrase = it }
    PixelText("\"time for\" matches \"It's time for Isha\" but not \"Isha in 15 minutes\". Empty = no phrase needed.", Type.Small)

    Label("PRAYER WORDS (COMMA-SEPARATED)")
    for (prayer in Prayer.entries) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelText(prayer.json.uppercase(), Type.Label, Modifier.width(84.dp))
            Field(s.adhanKeywords(prayer).joinToString(", "), resets, Modifier.weight(1f)) { s.setAdhanKeywords(prayer, it) }
        }
    }
    Spacer(Modifier.height(8.dp))
    PixelButton(
        "RESET ADHAN SETTINGS",
        {
            s.resetAdhan()
            resets++
        },
        Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(8.dp))
    AdhanTest(version)

    if (recent.isNotEmpty()) {
        Label("RECENT NOTIFICATIONS")
        for (seen in recent.take(8)) {
            PixelText(
                "${appLabel(seen.packageName)}: ${listOfNotNull(seen.title, seen.text).joinToString(" · ").take(80)}",
                Type.Small,
            )
            PixelText(
                seen.prayer?.let { "→ MATCHES ${it.json.uppercase()}" } ?: "→ NO MATCH",
                Type.Label,
                Modifier.padding(bottom = 6.dp),
                color = if (seen.prayer != null) Palette.White else Palette.Faint,
            )
        }
    }
}

/** Type a notification text and see what VIGIL would do with it; optionally fire it for real. */
@Composable
private fun AdhanTest(version: Int) {
    val s = Graph.settings
    var text by remember { mutableStateOf("It's time for Isha") }
    // Recomputed whenever the settings (app, phrase, words) or the test text change.
    val pkg = remember(version) { s.adhanPackages.firstOrNull() ?: "test.adhan.app" }
    val prayer = remember(version, text) { AdhanMatcher.match(s.adhanConfig, pkg, null, text) }
    val ids = prayer?.let { AdhanListener.directivesFor(it) }.orEmpty()

    Label("TEST TRIGGER")
    Field(text, Unit) { text = it }
    PixelText(
        when {
            prayer == null -> "NO MATCH: NOTHING WOULD FIRE."
            ids.isEmpty() -> "MATCHES ${prayer.json.uppercase()}, BUT THE RUNNING PLAN HAS NO ${prayer.json.uppercase()} DIRECTIVE."
            else -> "MATCHES ${prayer.json.uppercase()} → FIRES ${ids.joinToString()}"
        },
        Type.Label,
        color = if (prayer != null) Palette.White else Palette.Dim,
    )
    Spacer(Modifier.height(8.dp))
    PixelButton(
        "FIRE TEST TRIGGER",
        { AdhanListener.onNotification(pkg, null, text, System.currentTimeMillis(), test = true) },
        Modifier.fillMaxWidth(),
        enabled = ids.isNotEmpty(),
    )
    PixelText("Goes through the real path (quiet and bad-day mode apply) and is logged as \"adhan_test\".", Type.Small)
}

/** APP USAGE: how app switches are detected, and the live counts of the plan's app-usage directives. */
@Composable
internal fun AppUsageSection() {
    val context = LocalContext.current
    val appLabel = rememberAppLabel()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tick++
        }
    }

    // Re-read the detection mode and the live counts every couple of seconds.
    key(tick) {
        val mode = when {
            UsageWatcher.accessibilityConnected -> "INSTANT (ACCESSIBILITY)"
            Device.hasUsageAccess(context) -> "EVERY ~5 SECONDS (USAGE ACCESS)"
            else -> "OFF: GRANT USAGE ACCESS IN PERMISSIONS"
        }
        PixelText("DETECTION: $mode", Type.Label, color = if (mode.startsWith("OFF")) Palette.Red else Palette.White)
        PixelText(
            "Accessibility (optional, in PERMISSIONS) notices app switches at once; it only sees which app is in front, never screen content.",
            Type.Small,
        )
        UsageWatcher.foreground?.let {
            Spacer(Modifier.height(8.dp))
            PixelText("IN FRONT NOW: ${appLabel(it).uppercase()}", Type.Label)
        }
        val usage = Graph.activeSet().directives.mapNotNull { d -> (d.trigger as? Trigger.AppUsage)?.let { d to it } }
        Spacer(Modifier.height(8.dp))
        if (usage.isEmpty()) PixelText("The running plan has no app_usage directives.", Type.Small)
        for ((d, t) in usage) {
            val status = when (val l = t.limit) {
                is UsageLimit.Opens -> "${UsageWatcher.openCount(d.id)}/${l.opens} OPENS IN ${l.windowMin} MIN"
                is UsageLimit.Continuous -> "FIRES AFTER ${l.minutes} MIN NONSTOP"
            }
            PixelText(d.title.uppercase(), Type.Body)
            PixelText("$status · COOLDOWN ${t.cooldownMin} MIN", Type.Label, Modifier.padding(bottom = 6.dp))
        }
    }
}

/** TAKEOVER: what a takeover may use on this phone. */
@Composable
internal fun TakeoverSection() {
    val s = Graph.settings
    val version by s.version.collectAsState()
    val sound = remember(version) { s.soundEnabled }
    val vibration = remember(version) { s.vibrationEnabled }
    val glyph = remember(version) { s.glyphEnabled }
    val override = remember(version) { s.allowQuietOverride }

    Toggle("SOUND", sound) { s.soundEnabled = it }
    Toggle("VIBRATION", vibration) { s.vibrationEnabled = it }
    Toggle("GLYPH LIGHTS", glyph) { s.glyphEnabled = it }
    PixelText(
        if (GlyphFactory.AVAILABLE) {
            "Nothing phones only. On Android 16 they work without setup. On Android 15, enable Glyph debug mode from a computer " +
                "(lasts 48 hours): adb shell settings put global nt_glyph_interface_debug_enable 1"
        } else {
            "This build was made without Nothing's Glyph SDK, so the lights stay off."
        },
        Type.Small,
    )
    Spacer(Modifier.height(8.dp))
    Toggle("TIER-3 OVERRIDE IN QUIET MODE", override) { s.allowQuietOverride = it }
    PixelText(
        "Only directives the plan marks quiet: \"override\" (tier 3, e.g. Fajr or sleep) can ring in quiet mode. Off: they arrive silently.",
        Type.Small,
    )
}

/** SAFETY: a lighter day, and every way out of a takeover. */
@Composable
internal fun SafetySection(dayMode: DayMode, active: ActiveSet) {
    val context = LocalContext.current
    val until = Format.clock(active.dayBoundary)
    when (dayMode) {
        DayMode.NORMAL -> {
            HoldButton("I'M NOT OKAY TODAY", holdMillis = 3_000, onComplete = { Vigil.setBadDay(true) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            PixelText(
                "Hold 3 s. Until $until only the plan's essentials fire (${active.badDayKeep.size} kept). " +
                    "It is never logged as a failure. Also on every takeover: NOT OKAY.",
                Type.Small,
            )
        }
        DayMode.BAD_DAY, DayMode.MINIMUM -> {
            val badDay = dayMode == DayMode.BAD_DAY
            PixelText(if (badDay) "NOT OKAY TODAY" else "MINIMUM DAY", Type.Body)
            PixelText(
                if (badDay) {
                    "Until $until only the essentials fire (${active.badDayKeep.size} kept). Not a failure. Rest."
                } else {
                    "Chosen by this morning's check-in. Until $until only the minimum fires (${active.minimumKeep.size} kept). Not a failure."
                },
                Type.Small,
            )
            Spacer(Modifier.height(8.dp))
            PixelButton("I'M OKAY AGAIN", { Vigil.setBadDay(false) }, Modifier.fillMaxWidth())
        }
    }

    Label("CALL", Modifier.padding(top = 12.dp))
    PixelText(
        "Every takeover shows CALL at the bottom from its first frame. One tap silences it, opens the dialer (the emergency " +
            "dialer when the phone is locked) and keeps VIGIL away for 10 minutes. Takeovers never appear during a call " +
            "and never cover the phone app.",
        Type.Small,
    )
    Label("NOT OKAY · LEAVE")
    PixelText(
        "On a takeover, hold NOT OKAY 3 s for a lighter day, or hold LEAVE 10 s to abandon it (logged). A takeover closes " +
            "itself after 90 minutes.",
        Type.Small,
    )
    Label("KILL SWITCH")
    PixelText(
        "Tap the VIGIL title on the home screen 7 times (or the eye on a takeover): SUSPEND 1 H or 24 H stops every takeover. " +
            "For emergencies and development, not for skipping directives.",
        Type.Small,
    )
    Label("ADB ESCAPE")
    PixelText("From a computer. Only adb and the system can send these:", Type.Small)
    Spacer(Modifier.height(4.dp))
    val receiver = "${context.packageName}/.engine.KillSwitchReceiver"
    SelectionContainer {
        Column {
            PixelText("adb shell am broadcast -a ${KillSwitchReceiver.ACTION_SUSPEND} -n $receiver --ei minutes 60", Type.Small, color = Palette.White)
            Spacer(Modifier.height(4.dp))
            PixelText("adb shell am broadcast -a ${KillSwitchReceiver.ACTION_RESUME} -n $receiver", Type.Small, color = Palette.White)
            Spacer(Modifier.height(4.dp))
            PixelText("adb uninstall ${context.packageName}", Type.Small, color = Palette.White)
        }
    }
    Spacer(Modifier.height(4.dp))
    PixelText("Use SUSPEND rather than force-stop: with notification access on, Android restarts VIGIL right after a force-stop.", Type.Small)
}
