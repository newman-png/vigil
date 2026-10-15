package dev.vigil.ui

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.core.plan.Issue
import dev.vigil.data.BundleExporter
import dev.vigil.data.PlanRepository
import dev.vigil.engine.Notifications
import dev.vigil.engine.Vigil
import dev.vigil.service.VigilService
import dev.vigil.takeover.TakeoverActivity
import dev.vigil.trigger.TriggerSetup
import dev.vigil.ui.components.scanlines
import dev.vigil.ui.home.HomeActions
import dev.vigil.ui.home.HomeScreen
import dev.vigil.ui.importer.ImportResultScreen
import dev.vigil.ui.plan.PlanCompleteScreen
import dev.vigil.ui.plan.PlanScreen
import dev.vigil.ui.setup.SetupScreen
import dev.vigil.ui.test.TestScreen
import dev.vigil.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * VIGIL's own screens: HOME and the four places it leads (SETUP, PLAN, TEST; QUIET is a switch), plus PLAN
 * COMPLETE and the import result. Back always returns HOME. Takeovers have their own activity.
 */
class MainActivity : ComponentActivity() {
    private sealed interface Screen {
        data object Home : Screen
        data object Setup : Screen
        data object Plan : Screen
        data object PlanComplete : Screen
        data class Test(val revealKillSwitch: Boolean) : Screen
        data class ImportResult(val outcome: PlanRepository.ImportOutcome) : Screen
    }

    /** Bumped on every resume so permission statuses are re-read after visiting system settings. */
    private var resumeTick by mutableIntStateOf(0)

    private var screen by mutableStateOf<Screen>(Screen.Home)

    /**
     * The plan (its import time) PLAN COMPLETE last opened by itself for, during this visit; [NOT_SHOWN] = none.
     * It opens once per visit and per plan until the bundle is exported, not on every resume; leaving VIGIL
     * resets it, and so does the next plan.
     */
    private var planCompleteShownFor = NOT_SHOWN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always black, so the system bars always use light icons.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        planCompleteShownFor = savedInstanceState?.getLong(KEY_PLAN_COMPLETE_SHOWN, NOT_SHOWN) ?: NOT_SHOWN
        screen = when {
            !Graph.settings.setupDone -> Screen.Setup
            savedInstanceState != null -> restore(savedInstanceState.getString(KEY_SCREEN))
            else -> Screen.Home
        }
        // A recreated activity still carries its first intent; it was handled the first time.
        if (savedInstanceState == null) handleOpen(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Opening VIGIL again from the launcher starts on HOME, like a fresh start.
        if (intent.action == Intent.ACTION_MAIN && Graph.settings.setupDone) screen = Screen.Home
        handleOpen(intent)
    }

    override fun onStop() {
        super.onStop()
        // A new visit may show PLAN COMPLETE again (until the bundle is exported).
        if (!isChangingConfigurations) planCompleteShownFor = NOT_SHOWN
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        VigilService.start(this)
        // Opening VIGIL counts as being at the phone: a due morning check-in shows now.
        Vigil.onUserPresent()
        // The week is over and its bundle not exported yet: say so when VIGIL opens on HOME.
        val complete = Vigil.planComplete()
        val plan = currentPlanKey()
        if (!complete && screen == Screen.PlanComplete) screen = Screen.Home
        if (complete && planCompleteShownFor != plan && screen == Screen.Home) {
            planCompleteShownFor = plan
            screen = Screen.PlanComplete
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_PLAN_COMPLETE_SHOWN, planCompleteShownFor)
        outState.putString(KEY_SCREEN, save(screen))
    }

    /** The "plan complete" notification opens PLAN COMPLETE (or HOME, if the bundle was exported meanwhile). */
    private fun handleOpen(intent: Intent?) {
        val i = intent ?: return
        if (i.getStringExtra(Notifications.EXTRA_OPEN) != Notifications.OPEN_PLAN_COMPLETE) return
        i.removeExtra(Notifications.EXTRA_OPEN)
        if (!Graph.settings.setupDone) return
        planCompleteShownFor = currentPlanKey()
        screen = if (Vigil.planComplete()) Screen.PlanComplete else Screen.Home
    }

    /** Identifies the imported plan by its import time (-1 when it has none). */
    private fun currentPlanKey(): Long = Graph.plans.snapshot.value.importedAtMs ?: -1L

    @Composable
    private fun App() {
        val scope = rememberCoroutineScope()
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Denied twice, Android no longer asks: open VIGIL's notification settings instead.
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                TriggerSetup.open(
                    this,
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                )
            }
            resumeTick++
            VigilService.start(this)
        }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val read = withContext(Dispatchers.IO) { readPlanFile(uri) }
                    screen = Screen.ImportResult(importPlan(read))
                }
            }
        }

        val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { BundleExporter.write(this@MainActivity, uri) }
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) "Bundle saved. Give it to your planner." else "Could not save the bundle.",
                        Toast.LENGTH_SHORT,
                    ).show()
                    // PLAN COMPLETE has done its job.
                    if (ok && screen == Screen.PlanComplete) screen = Screen.Home
                }
            }
        }
        val pickPlan = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        val startExport = { exporter.launch(BundleExporter.suggestedName()) }

        BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

        // Black, white and red only: text selection and cursor handles too (Compose's default is blue).
        val selection = TextSelectionColors(handleColor = Palette.White, backgroundColor = Palette.White.copy(alpha = 0.3f))
        CompositionLocalProvider(LocalTextSelectionColors provides selection) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Palette.Black)
                    .scanlines(0.12f),
            ) {
                // Each screen keeps its own scroll position.
                key(screen) {
                    Box(
                        Modifier
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        when (val s = screen) {
                            Screen.Home -> HomeScreen(
                                refreshKey = resumeTick,
                                actions = HomeActions(
                                    openSetup = { screen = Screen.Setup },
                                    openPlan = { screen = Screen.Plan },
                                    openTest = { reveal -> screen = Screen.Test(reveal) },
                                    openTakeover = { startActivity(TakeoverActivity.intent(this@MainActivity)) },
                                ),
                            )
                            Screen.Setup -> SetupScreen(
                                refreshKey = resumeTick,
                                firstRun = !Graph.settings.setupDone,
                                requestNotifications = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                onDone = {
                                    Graph.settings.setupDone = true
                                    screen = Screen.Home
                                },
                            )
                            Screen.Plan -> PlanScreen(importPlan = pickPlan, exportBundle = startExport)
                            Screen.PlanComplete -> PlanCompleteScreen(export = startExport)
                            is Screen.Test -> TestScreen(revealKillSwitch = s.revealKillSwitch)
                            is Screen.ImportResult -> ImportResultScreen(s.outcome) { screen = Screen.Plan }
                        }
                    }
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
                listOf(Issue("", "Could not read that file (it may be too large or not a text file).")),
                emptyList(),
            )
        } else {
            Vigil.importPlan(text, name)
        }
    }

    /** A screen as a string for the saved state; the import result is not kept (PLAN shows the plan instead). */
    private fun save(s: Screen): String = when (s) {
        Screen.Home -> "home"
        Screen.Setup -> "setup"
        Screen.Plan, is Screen.ImportResult -> "plan"
        Screen.PlanComplete -> "plan_complete"
        is Screen.Test -> if (s.revealKillSwitch) "test_kill" else "test"
    }

    private fun restore(saved: String?): Screen = when (saved) {
        "setup" -> Screen.Setup
        "plan" -> Screen.Plan
        "plan_complete" -> Screen.PlanComplete
        "test" -> Screen.Test(revealKillSwitch = false)
        "test_kill" -> Screen.Test(revealKillSwitch = true)
        else -> Screen.Home
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 1_000_000
        const val KEY_SCREEN = "screen"
        const val KEY_PLAN_COMPLETE_SHOWN = "plan_complete_shown"
        const val NOT_SHOWN = Long.MIN_VALUE
    }
}
