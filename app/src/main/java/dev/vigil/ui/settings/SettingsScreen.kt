package dev.vigil.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.core.engine.AdhanMatcher
import dev.vigil.core.plan.Prayer
import dev.vigil.trigger.AdhanListener
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.Rule
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type

/**
 * Phone-specific settings. Anything about the week belongs in the plan instead; these only describe this
 * phone (which app announces prayers, whether sound is allowed, ...).
 */
@Composable
fun SettingsScreen(appLabel: (String) -> String, extraSections: @Composable () -> Unit, onBack: () -> Unit) {
    val version by Graph.settings.version.collectAsState()
    val s = Graph.settings

    Column {
        PixelText("SETTINGS", Type.Title)
        Spacer(Modifier.height(8.dp))

        PixelText("TAKEOVER", Type.Heading)
        Toggle("SOUND", s.soundEnabled, version) { s.soundEnabled = it }
        Toggle("VIBRATION", s.vibrationEnabled, version) { s.vibrationEnabled = it }
        Toggle("GLYPH LIGHTS (NOTHING PHONES)", s.glyphEnabled, version) { s.glyphEnabled = it }
        Toggle("LET TIER-3 \"OVERRIDE\" DIRECTIVES RING IN QUIET MODE", s.allowQuietOverride, version) { s.allowQuietOverride = it }
        PixelText("Only plan directives marked quiet: \"override\" (tier 3, e.g. Fajr or sleep) can use this.", Type.Small)

        Rule()
        AdhanSection(appLabel, version)
        extraSections()

        Spacer(Modifier.height(16.dp))
        PixelButton("BACK", onBack, Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AdhanSection(appLabel: (String) -> String, version: Int) {
    val s = Graph.settings
    val recent by AdhanListener.recent.collectAsState()
    val connected by AdhanListener.connected.collectAsState()
    // Text fields keep what is being typed; they only reload from settings after a reset.
    var resets by remember { mutableIntStateOf(0) }

    PixelText("ADHAN", Type.Heading)
    PixelText(
        "VIGIL fires adhan directives when your adhan app posts its prayer notification. " +
            "It never calculates prayer times itself.",
        Type.Small,
    )
    PixelText(if (connected) "NOTIFICATION ACCESS: ON" else "NOTIFICATION ACCESS: OFF (see SETUP)", Type.Body, color = if (connected) Palette.White else Palette.Red)
    Spacer(Modifier.height(8.dp))

    val packages = remember(version) { s.adhanPackages }
    PixelText("ADHAN APP", Type.Body)
    if (packages.isEmpty()) {
        PixelText("No app picked: any app's notification saying \"time for Isha\" counts, even a chat message. Pick your adhan app below.", Type.Small, color = Palette.Red)
    } else {
        for (pkg in packages) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText("${appLabel(pkg)}  ($pkg)", Type.Small, Modifier.weight(1f))
                PixelButton("REMOVE", { s.adhanPackages = packages - pkg })
            }
        }
    }
    val candidates = recent.map { it.packageName }.distinct().filter { it !in packages }.take(6)
    if (candidates.isNotEmpty()) {
        PixelText("Apps that posted notifications recently (tap to use):", Type.Small)
        for (pkg in candidates) {
            PixelText(
                "+ ${appLabel(pkg)}",
                Type.Body,
                Modifier
                    .clickable { s.adhanPackages = packages + pkg }
                    .padding(vertical = 4.dp),
            )
        }
    } else {
        PixelText("Once your adhan app posts a notification it will appear here so you can pick it.", Type.Small)
    }

    Spacer(Modifier.height(8.dp))
    PixelText("PHRASE BEFORE THE PRAYER NAME", Type.Body)
    Field(s.adhanPhrase, resets) { s.adhanPhrase = it }
    PixelText("e.g. \"time for\" matches \"It's time for Isha\" but not \"Isha in 15 minutes\". Empty = no phrase needed.", Type.Small)

    Spacer(Modifier.height(8.dp))
    PixelText("PRAYER WORDS (comma-separated)", Type.Body)
    for (prayer in Prayer.entries) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelText(prayer.json.uppercase(), Type.Small, Modifier.width(80.dp))
            Field(s.adhanKeywords(prayer).joinToString(", "), resets, Modifier.weight(1f)) { s.setAdhanKeywords(prayer, it) }
        }
    }
    PixelButton(
        "RESET ADHAN SETTINGS",
        {
            s.resetAdhan()
            resets++
        },
        Modifier.fillMaxWidth().padding(top = 8.dp),
    )

    Rule()
    AdhanTest(version)

    if (recent.isNotEmpty()) {
        Rule()
        PixelText("RECENT NOTIFICATIONS", Type.Body)
        for (seen in recent.take(8)) {
            PixelText(
                "${appLabel(seen.packageName)}: ${listOfNotNull(seen.title, seen.text).joinToString(" · ").take(80)}",
                Type.Small,
            )
            PixelText(seen.prayer?.let { "  → MATCHES ${it.json.uppercase()}" } ?: "  → no match", Type.Small, color = if (seen.prayer != null) Palette.White else Palette.Faint)
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

    PixelText("TEST TRIGGER", Type.Body)
    Field(text, 0) { text = it }
    PixelText(
        when {
            prayer == null -> "NO MATCH: nothing would fire."
            ids.isEmpty() -> "MATCHES ${prayer.json.uppercase()}, but the active plan has no ${prayer.json} directive."
            else -> "MATCHES ${prayer.json.uppercase()} → fires ${ids.joinToString()}"
        },
        Type.Small,
        color = if (prayer != null) Palette.White else Palette.Red,
    )
    PixelButton(
        "FIRE TEST TRIGGER",
        { AdhanListener.onNotification(pkg, null, text, System.currentTimeMillis(), test = true) },
        Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = ids.isNotEmpty(),
    )
    PixelText("Goes through the real path (quiet and bad-day mode apply) and is logged as \"adhan_test\".", Type.Small)
}

@Composable
fun Toggle(label: String, value: Boolean, @Suppress("UNUSED_PARAMETER") version: Int, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!value) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelText(if (value) "[X]" else "[ ]", Type.Body)
        Spacer(Modifier.width(10.dp))
        PixelText(label, Type.Body, Modifier.weight(1f))
    }
}

/** A single-line pixel text field that saves on every change. It reloads [initial] only when [resetKey] changes. */
@Composable
fun Field(initial: String, resetKey: Any?, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    var value by remember(resetKey) { mutableStateOf(initial) }
    BasicTextField(
        value = value,
        onValueChange = {
            value = it
            onChange(it)
        },
        singleLine = true,
        textStyle = Type.Body,
        cursorBrush = SolidColor(Palette.White),
        modifier = modifier
            .padding(vertical = 4.dp)
            .border(2.dp, Palette.Faint, RectangleShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
