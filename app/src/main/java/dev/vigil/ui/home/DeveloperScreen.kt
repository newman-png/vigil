package dev.vigil.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.vigil.engine.Vigil
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.theme.Type

/**
 * The hidden developer kill switch (seven taps on the VIGIL title). Suspending drops whatever is showing
 * and blocks takeovers until it runs out. The same switch is reachable over adb; see the README.
 */
@Composable
fun DeveloperScreen(onBack: () -> Unit) {
    Column {
        PixelText("KILL SWITCH", Type.Title)
        PixelText("Stops every takeover. For emergencies and development, not for skipping directives.", Type.Small)
        Spacer(Modifier.height(16.dp))
        PixelButton("SUSPEND 1 HOUR", { Vigil.suspend(60); onBack() }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        PixelButton("SUSPEND 24 HOURS", { Vigil.suspend(24 * 60); onBack() }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        PixelButton("RESUME NOW", { Vigil.resume(); onBack() }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        PixelButton("BACK", onBack, Modifier.fillMaxWidth())
    }
}
