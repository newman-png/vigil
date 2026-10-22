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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.core.engine.AdhanMatcher
import dev.vigil.core.protocol.Prayer
import dev.vigil.engine.Device
import dev.vigil.engine.KillSwitchReceiver
import dev.vigil.engine.Vigil
import dev.vigil.glyph.GlyphFactory
import dev.vigil.trigger.AdhanListener
import dev.vigil.ui.Format
import dev.vigil.ui.components.Field
import dev.vigil.ui.components.Label
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.TextInput
import dev.vigil.ui.components.Toggle
import dev.vigil.ui.components.tap
import dev.vigil.ui.isInstalled
import dev.vigil.ui.rememberAppLabel
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type
import java.time.Instant

/** ADHAN: which app announces the prayers, the words that count, a test, and what the listener has seen. */
@Composable
internal fun AdhanSection() {
    val s = Graph.settings
    val version by s.version.collectAsState()
    val recent by AdhanListener.recent.collectAsState()
    val connected by AdhanListener.connected.collectAsState()
    val appLabel = rememberAppLabel()

    PixelText(
        "Each prayer protocol starts when your adhan app posts its prayer notification (\"It's time for Isha\"). " +
            "VIGIL never calculates prayer times itself; the heads-up and the prayer window are estimated from " +
            "yesterday's adhan.",
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
        PixelText(
            "No app picked: any app's notification saying \"time for Isha\" counts, even a chat message. Pick your adhan " +
                "app below. It is also the adhan app on the takeover's APPS button.",
            Type.Small,
        )
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

    // The fields show what is being typed; they reload from the settings only after a reset.
    var resets by remember { mutableIntStateOf(0) }
    Label("PHRASE BEFORE THE PRAYER NAME")
    Field(s.adhanPhrase, resets) { s.adhanPhrase = it }
    PixelText("\"time for\" matches \"It's time for Isha\" but not \"Isha in 15 minutes\". Empty = no phrase needed.", Type.Small)

    Label("PRAYER WORDS (COMMA-SEPARATED)")
    for (prayer in Prayer.entries) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelText(prayer.title, Type.Label, Modifier.width(84.dp))
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
                seen.prayer?.let { "→ MATCHES ${it.title}" } ?: "→ NO MATCH",
                Type.Label,
                Modifier.padding(bottom = 6.dp),
                color = if (seen.prayer != null) Palette.White else Palette.Faint,
            )
        }
    }
}

/** Type a notification text and see what VIGIL would do with it; optionally fire a test preview of that prayer. */
@Composable
private fun AdhanTest(version: Int) {
    val s = Graph.settings
    var text by remember { mutableStateOf("It's time for Isha") }
    // Recomputed whenever the settings (app, phrase, words) or the test text change.
    val pkg = remember(version) { s.adhanPackages.firstOrNull() ?: "test.adhan.app" }
    val prayer = remember(version, text) { AdhanMatcher.match(s.adhanConfig, pkg, null, text) }
    val ids = prayer?.let { AdhanListener.protocolsFor(it) }.orEmpty()

    Label("TEST TRIGGER")
    TextInput(text, { text = it })
    PixelText(
        when {
            prayer == null -> "NO MATCH: NOTHING WOULD START."
            ids.isEmpty() -> "MATCHES ${prayer.title}, BUT THE PROTOCOLS FILE HAS NO ${prayer.title} PROTOCOL."
            else -> "MATCHES ${prayer.title} → STARTS ${ids.joinToString().uppercase()}"
        },
        Type.Label,
        color = if (prayer != null) Palette.White else Palette.Dim,
    )
    Spacer(Modifier.height(8.dp))
    PixelButton(
        "FIRE TEST PREVIEW",
        { AdhanListener.onNotification(pkg, null, text, System.currentTimeMillis(), test = true) },
        Modifier.fillMaxWidth(),
        enabled = ids.isNotEmpty(),
    )
    PixelText(
        "Starts a TEST preview of that prayer's protocol: logged as a test, and it never changes the real prayer " +
            "times, stages or the report.",
        Type.Small,
    )
}

/** POOL AND APPS: the blocked apps (with the browsers found on this phone), its rules, and the always-allowed apps. */
@Composable
internal fun PoolSection() {
    val context = LocalContext.current
    val appLabel = rememberAppLabel()
    val file = remember { Vigil.file() }
    val pool = file.pool
    val browsers = remember { if (pool.allBrowsers) Device.browsers(context).sorted() else emptyList() }
    val whitelist = remember { Vigil.whitelistApps() }

    PixelText(
        "The pool: these apps share one counter of minutes a day, each open after a short wait. Calls and the apps " +
            "below always work.",
        Type.Small,
    )
    Label("POOL APPS")
    for (pkg in pool.apps) {
        val installed = isInstalled(context, pkg)
        PixelText(
            if (installed) appLabel(pkg).uppercase() else "$pkg (NOT INSTALLED)",
            Type.Label,
            Modifier.padding(vertical = 2.dp),
            color = if (installed) Palette.White else Palette.Faint,
        )
    }
    if (pool.allBrowsers) {
        Label("BROWSERS ON THIS PHONE (ALL IN THE POOL)")
        if (browsers.isEmpty()) PixelText("None found.", Type.Small)
        for (pkg in browsers) {
            PixelText(appLabel(pkg).uppercase(), Type.Label, Modifier.padding(vertical = 2.dp), color = Palette.White)
        }
    }

    Label("RULES")
    PixelText(
        listOf(
            "${pool.dailyBudgetMin} minutes a day, at any time nothing else blocks the pool.",
            "Every open waits ${pool.waitMin} min first.",
            "${pool.heavyUse.continuousMin} minutes in a row closes the app for a ${pool.heavyUse.breakMin}-minute break.",
            "Leaving a protocol early locks the pool ${pool.bailOut.extraLockMin / 60} more hours (at most ${pool.bailOut.dailyCapMin / 60} a day).",
        ).joinToString(" "),
        Type.Small,
    )

    Label("APPS BUTTON (USABLE IN EVERY LOCK)")
    if (whitelist.isEmpty()) PixelText("None found on this phone.", Type.Small)
    for (app in whitelist) {
        PixelText(app.label.uppercase(), Type.Label, Modifier.padding(vertical = 2.dp), color = Palette.White)
    }
    PixelText(
        "The phone app and emergency calls always work. The adhan app joins once you pick it in ADHAN (or once it " +
            "announces a prayer); an app named Prayer (Prayer Times) is in the list anyway.",
        Type.Small,
    )
}

/** TAKEOVER: what a full-intensity takeover may use on this phone. */
@Composable
internal fun TakeoverSection() {
    val s = Graph.settings
    val version by s.version.collectAsState()
    val sound = remember(version) { s.soundEnabled }
    val vibration = remember(version) { s.vibrationEnabled }
    val glyph = remember(version) { s.glyphEnabled }

    PixelText(
        "A full takeover has sound, vibration and the Glyph lights; a subtle one is a quiet screen. QUIET on HOME " +
            "silences every takeover. These switches turn each effect off for good.",
        Type.Small,
    )
    Spacer(Modifier.height(4.dp))
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
}

/** SAFETY: every way out of a takeover, the kill switch, and the adb escape. */
@Composable
internal fun SafetySection() {
    val context = LocalContext.current
    val state by Vigil.state.collectAsState()
    val bail = remember { Vigil.file().pool.bailOut }
    val until = state.suspendedUntil?.takeIf { it.isAfter(Instant.now()) }

    if (until != null) {
        PixelText("SUSPENDED UNTIL ${Format.dateTime(until, Graph.zone)}", Type.Label, color = Palette.Red)
        PixelText("No takeovers and the pool is open until then.", Type.Small)
        Spacer(Modifier.height(8.dp))
        PixelButton("RESUME NOW", { Vigil.resume() }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }

    Label("CALL")
    PixelText(
        "Every takeover shows CALL at the bottom from its first frame. One tap silences it, opens the dialer (the emergency " +
            "dialer when the phone is locked) and keeps VIGIL away for 10 minutes. Takeovers never appear during a call " +
            "and never cover the phone app. A call pauses the protocol's timers and lock for its length; it never ends " +
            "the protocol and costs nothing.",
        Type.Small,
    )
    Label("APPS")
    PixelText("The takeover's APPS button opens the always-allowed apps (POOL AND APPS) in every lock.", Type.Small)
    Label("NOT OKAY · LEAVE")
    PixelText(
        "Hold NOT OKAY 3 s for a shorter version of the protocol (a prayer's lock shortens to its minimum); it costs " +
            "nothing. Hold LEAVE 10 s to quit early: logged as a bail-out, and the pool stays locked " +
            "${bail.extraLockMin / 60} more hours (at most ${bail.dailyCapMin / 60} a day).",
        Type.Small,
    )
    Label("KILL SWITCH")
    PixelText(
        "Tap the VIGIL title on HOME 7 times (or the eye on a takeover), then stay on the 5-minute wait: VIGIL is " +
            "suspended for 1 hour (no takeovers, the pool open). For emergencies, not for skipping protocols.",
        Type.Small,
    )
    Label("NO DEVICE ADMIN")
    PixelText(
        "VIGIL never covers Settings, force stop or the uninstall prompt, and it has no device admin: it can be " +
            "uninstalled like any app.",
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
    PixelText(
        "SUSPEND takes minutes (at most a week). Use it rather than force-stop: with notification access on, Android " +
            "restarts VIGIL right after a force-stop.",
        Type.Small,
    )
}
