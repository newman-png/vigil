package dev.vigil.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.engine.Vigil
import dev.vigil.service.VigilService
import dev.vigil.takeover.TakeoverActivity
import dev.vigil.trigger.StepCounter
import dev.vigil.trigger.TriggerSetup
import dev.vigil.ui.audio.AudioScreen
import dev.vigil.ui.components.scanlines
import dev.vigil.ui.home.HomeActions
import dev.vigil.ui.home.HomeScreen
import dev.vigil.ui.setup.SetupScreen
import dev.vigil.ui.test.TestScreen
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.times.MyTimesScreen

/**
 * VIGIL's own screens: HOME and the places it leads (MY TIMES, SETUP, TEST, AUDIO; QUIET, the walking and friends
 * switches, GOING TO SLEEP and EXPORT act right on HOME). Back always returns HOME. Takeovers, the pool's wait and block
 * screens and the kill switch's wait have their own activities.
 */
class MainActivity : ComponentActivity() {
    private enum class Screen(val key: String) {
        HOME("home"),
        MY_TIMES("times"),
        SETUP("setup"),
        TEST("test"),
        AUDIO("audio"),
    }

    /** Bumped on every resume so permission statuses are re-read after visiting system settings. */
    private var resumeTick by mutableIntStateOf(0)

    private var screen by mutableStateOf(Screen.HOME)

    /** Bumped when LATER on the always-on notification opened VIGIL: HOME opens its LATER sheet. */
    private var laterRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always black, so the system bars always use light icons.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        val saved = savedInstanceState?.getString(KEY_SCREEN)
        screen = when {
            !Graph.settings.setupDone -> Screen.SETUP
            saved != null -> Screen.entries.firstOrNull { it.key == saved } ?: Screen.HOME
            else -> Screen.HOME
        }
        if (savedInstanceState == null) openLaterIfAsked(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Opening VIGIL again from the launcher starts on HOME, like a fresh start.
        if (intent.action == Intent.ACTION_MAIN && Graph.settings.setupDone) screen = Screen.HOME
        openLaterIfAsked(intent)
    }

    /** LATER on the always-on notification: HOME with its LATER sheet open. */
    private fun openLaterIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_LATER, false) != true || !Graph.settings.setupDone) return
        screen = Screen.HOME
        laterRequest++
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        VigilService.start(this)
        // Bring the engine up to date, so HOME shows what is true now (a lock that ended, a window that opened).
        try {
            Vigil.onTick()
        } catch (e: Exception) {
            Log.e(TAG, "Engine tick failed", e)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.key)
    }

    /** SETUP's DONE on the first run: MY TIMES next (its first save applies at once), else HOME. */
    private fun setupFinished() {
        Graph.settings.setupDone = true
        screen = if (Vigil.myTimes().edited) Screen.HOME else Screen.MY_TIMES
    }

    @Composable
    private fun App() {
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

        // PHYSICAL ACTIVITY: the step counter (a walk ends when you stop moving). Denied for good: VIGIL's app info.
        val activityPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)) {
                TriggerSetup.open(this, TriggerSetup.appInfoIntent(this), Intent(Settings.ACTION_SETTINGS))
            }
            resumeTick++
            StepCounter.sync(this)
        }
        val requestActivity = { activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION) }

        BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

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
                            // The safe area includes the keyboard, so MY TIMES fields stay reachable while typing.
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        when (screen) {
                            Screen.HOME -> HomeScreen(
                                refreshKey = resumeTick,
                                laterRequest = laterRequest,
                                actions = HomeActions(
                                    openMyTimes = { screen = Screen.MY_TIMES },
                                    openSetup = { screen = Screen.SETUP },
                                    openTest = { screen = Screen.TEST },
                                    openTakeover = { startActivity(TakeoverActivity.intent(this@MainActivity)) },
                                    openAudio = { screen = Screen.AUDIO },
                                    requestActivity = requestActivity,
                                ),
                            )
                            Screen.MY_TIMES -> MyTimesScreen(onSaved = { screen = Screen.HOME })
                            Screen.SETUP -> SetupScreen(
                                refreshKey = resumeTick,
                                firstRun = !Graph.settings.setupDone,
                                requestNotifications = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                requestActivity = requestActivity,
                                onDone = ::setupFinished,
                            )
                            Screen.TEST -> TestScreen(openAudio = { screen = Screen.AUDIO })
                            Screen.AUDIO -> AudioScreen()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "Vigil"
        private const val KEY_SCREEN = "screen"
        private const val EXTRA_LATER = "later"

        /** Opens HOME with its LATER sheet (a 10-minute promise): the always-on notification's LATER. */
        fun laterIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_LATER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
