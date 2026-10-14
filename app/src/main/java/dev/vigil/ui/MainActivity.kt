package dev.vigil.ui

import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.vigil.core.engine.Engine
import dev.vigil.data.PlanRepository
import dev.vigil.engine.Vigil
import dev.vigil.ui.components.scanlines
import dev.vigil.ui.home.HomeActions
import dev.vigil.ui.home.HomeScreen
import dev.vigil.ui.importer.ImportResultScreen
import dev.vigil.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private sealed interface Screen {
        data object Home : Screen
        data class ImportResult(val outcome: PlanRepository.ImportOutcome) : Screen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always black, so the system bars always use light icons.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent { App() }
    }

    override fun onResume() {
        super.onResume()
        Vigil.showIfActive()
    }

    @Composable
    private fun App() {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        val scope = rememberCoroutineScope()

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val read = withContext(Dispatchers.IO) { readPlanFile(uri) }
                    screen = Screen.ImportResult(importPlan(read))
                }
            }
        }

        BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

        Box(
            Modifier
                .fillMaxSize()
                .background(Palette.Black)
                .scanlines(),
        ) {
            Box(
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        HomeActions(
                            importPlan = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                            fireDirective = { Vigil.fireNow(it) },
                            demoTakeover = { Vigil.fireNow(Engine.DEMO_ID) },
                        ),
                        appLabel = ::appLabel,
                    )
                    is Screen.ImportResult -> ImportResultScreen(s.outcome) { screen = Screen.Home }
                }
            }
        }
    }

    /** Reads the picked file (off the main thread): its name and text, or null text if it can't be read. */
    private fun readPlanFile(uri: Uri): Pair<String?, String?> {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val text = try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytes(MAX_IMPORT_BYTES + 1)
                if (bytes.size > MAX_IMPORT_BYTES) null else bytes.decodeToString()
            }
        } catch (e: Exception) {
            null
        }
        return name to text
    }

    private fun importPlan(read: Pair<String?, String?>): PlanRepository.ImportOutcome {
        val (name, text) = read
        return if (text == null) {
            PlanRepository.ImportOutcome.Rejected(
                listOf(dev.vigil.core.plan.Issue("", "Could not read that file (it may be too large or not a text file).")),
                emptyList(),
            )
        } else {
            Vigil.importPlan(text, name)
        }
    }

    private fun appLabel(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        packageManager.getApplicationLabel(info).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 1_000_000
    }
}
