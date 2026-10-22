package dev.vigil.ui.setup

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.engine.Dnd
import dev.vigil.engine.Vigil
import dev.vigil.guard.Guard
import dev.vigil.trigger.AdhanListener
import dev.vigil.trigger.StepCounter
import dev.vigil.trigger.TriggerSetup
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.ScreenTitle
import dev.vigil.ui.components.Section
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type
import java.time.Instant

/** One thing the phone must allow. [granted] null means Android cannot tell us (follow the instructions). */
class SetupItem(
    val title: String,
    val why: String,
    val granted: Boolean?,
    val action: String,
    val optional: Boolean = false,
    val onAction: () -> Unit,
)

/** Everything Android must allow: the base items (the Guard first) and each trigger's own (notification access, ...). */
fun permissionItems(context: Context, requestNotifications: () -> Unit, requestActivity: () -> Unit = {}): List<SetupItem> =
    baseItems(context, requestNotifications, requestActivity) + TriggerSetup.items(context)

/** How many required permissions are missing (the home screen's SETUP hint). */
fun missingPermissions(context: Context): Int =
    permissionItems(context, requestNotifications = {}).count { it.granted == false && !it.optional }

/**
 * Everything about this phone, in collapsible sections: PERMISSIONS, RESTRICTED SETTINGS, BATTERY, POOL AND APPS,
 * ADHAN, TAKEOVER and SAFETY. Everything about the day is in MY TIMES and the protocols file. On the first run it ends
 * with DONE.
 *
 * [refreshKey] changes on every resume, so statuses are re-read after a visit to system settings.
 */
@Composable
fun SetupScreen(
    refreshKey: Int,
    firstRun: Boolean,
    requestNotifications: () -> Unit,
    requestActivity: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val items = remember(refreshKey, requestNotifications) { permissionItems(context, requestNotifications, requestActivity) }
    val missing = items.count { it.granted == false && !it.optional }
    val state by Vigil.state.collectAsState()
    val settingsVersion by Graph.settings.version.collectAsState()
    val connected by AdhanListener.connected.collectAsState()
    val batteryOk = remember(refreshKey) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    Column(Modifier.fillMaxWidth()) {
        ScreenTitle(
            "SETUP",
            "VIGIL can only keep its promises if Android lets it. Everything here is about this phone; " +
                "your times are in MY TIMES.",
        )

        Section(
            "PERMISSIONS",
            hint = if (missing == 0) "ALL GRANTED" else "$missing MISSING",
            hintColor = if (missing == 0) Palette.Dim else Palette.Red,
            initiallyOpen = firstRun || missing > 0,
        ) {
            for ((i, item) in items.withIndex()) {
                if (i > 0) Spacer(Modifier.height(12.dp))
                PermissionRow(item)
            }
        }

        Section("RESTRICTED SETTINGS", hint = "SIDELOADED APPS", initiallyOpen = firstRun) {
            RestrictedSection()
        }

        Section("BATTERY", hint = if (batteryOk) "UNRESTRICTED" else "RESTRICTED", hintColor = if (batteryOk) Palette.Dim else Palette.Red) {
            BatterySection(batteryOk)
        }

        Section("POOL AND APPS") {
            PoolSection()
        }

        // Every prayer protocol starts at its adhan, so a listener that is not connected is urgent.
        val adhanPackages = remember(settingsVersion) { Graph.settings.adhanPackages }
        val adhanHint = when {
            !connected -> "NOT CONNECTED"
            adhanPackages.isEmpty() -> "ANY APP"
            adhanPackages.size == 1 -> "1 APP"
            else -> "${adhanPackages.size} APPS"
        }
        Section("ADHAN", hint = adhanHint, hintColor = if (!connected || adhanPackages.isEmpty()) Palette.Red else Palette.Dim) {
            AdhanSection()
        }

        Section("TAKEOVER") {
            TakeoverSection()
        }

        val suspended = state.suspendedUntil?.isAfter(Instant.now()) == true
        Section("SAFETY", hint = if (suspended) "SUSPENDED" else null, hintColor = Palette.Red) {
            SafetySection()
        }

        if (firstRun) {
            Spacer(Modifier.height(24.dp))
            PixelButton(if (missing == 0) "DONE" else "CONTINUE ANYWAY", onDone, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionRow(item: SetupItem) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val status = when (item.granted) {
                true -> "OK"
                false -> if (item.optional) "OPTIONAL" else "MISSING"
                null -> "CHECK"
            }
            Row {
                PixelText(item.title, Type.Body, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                PixelText(status, Type.Label, color = if (item.granted == false && !item.optional) Palette.Red else Palette.Dim)
            }
            PixelText(item.why, Type.Small)
        }
        if (item.granted != true) {
            Spacer(Modifier.width(12.dp))
            PixelButton(item.action, item.onAction)
        }
    }
}

/** RESTRICTED SETTINGS: why accessibility and notification access can be greyed out, and how to allow them. */
@Composable
private fun RestrictedSection() {
    val context = LocalContext.current
    PixelText(
        "VIGIL is installed from a file, so Android blocks two of its permissions until you allow them: accessibility " +
            "(the Guard) and notification access (the adhan). If either page is greyed out or says \"restricted setting\":",
        Type.Small,
    )
    Spacer(Modifier.height(8.dp))
    PixelText("1. Open App info (the button below).", Type.Small, color = Palette.White)
    PixelText("2. Tap ⋮ (top right) › Allow restricted settings, and confirm.", Type.Small, color = Palette.White)
    PixelText("3. Come back here and grant the permission again.", Type.Small, color = Palette.White)
    Spacer(Modifier.height(8.dp))
    PixelText("The ⋮ menu only appears after you have tried to turn one of them on once.", Type.Small)
    Spacer(Modifier.height(12.dp))
    PixelButton(
        "OPEN APP INFO",
        { TriggerSetup.open(context, TriggerSetup.appInfoIntent(context), Intent(Settings.ACTION_SETTINGS)) },
        Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BatterySection(batteryOk: Boolean) {
    val context = LocalContext.current
    PixelText(
        if (batteryOk) "Android lets VIGIL run in the background." else "Android may put VIGIL to sleep, and protocols would start late or not at all.",
        Type.Small,
    )
    Spacer(Modifier.height(8.dp))
    PixelText(
        "Nothing phones: Settings › Apps › VIGIL › Battery › Unrestricted. " +
            "If protocols stop starting after a while, don't swipe VIGIL away in Recents.",
        Type.Small,
    )
    Spacer(Modifier.height(12.dp))
    PixelButton(
        "OPEN APP INFO",
        { TriggerSetup.open(context, TriggerSetup.appInfoIntent(context), Intent(Settings.ACTION_SETTINGS)) },
        Modifier.fillMaxWidth(),
    )
}

private fun baseItems(context: Context, requestNotifications: () -> Unit, requestActivity: () -> Unit): List<SetupItem> {
    val pkg = context.packageName
    val packageUri = Uri.parse("package:$pkg")
    val nm = context.getSystemService(NotificationManager::class.java)
    val pm = context.getSystemService(PowerManager::class.java)
    val appInfo = TriggerSetup.appInfoIntent(context)
    fun open(intent: Intent) = TriggerSetup.open(context, intent, appInfo)
    return listOf(
        SetupItem(
            "GUARD (ACCESSIBILITY)",
            "Required. The Guard keeps the pool apps closed during locks and once today's minutes are used, and opens " +
                "takeovers full screen while you use the phone. It only sees " +
                "which app is in front, never what is on the screen. Turn on \"VIGIL Guard\" under Accessibility (see " +
                "RESTRICTED SETTINGS if it is greyed out).",
            Guard.accessibilityEnabled(context),
            "OPEN",
        ) { open(Guard.accessibilitySettingsIntent(context)) },
        SetupItem(
            "NOTIFICATIONS",
            "Needed for the always-on notification, prayer heads-ups and CONFIRM cards, and to open takeovers.",
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            "ALLOW",
        ) { requestNotifications() },
        SetupItem(
            "FULL-SCREEN TAKEOVERS",
            "Lets a protocol take over the screen when the phone is locked or asleep, and the wake alarm open full screen.",
            if (Build.VERSION.SDK_INT >= 34) nm?.canUseFullScreenIntent() ?: false else true,
            "OPEN",
        ) {
            if (Build.VERSION.SDK_INT >= 34) {
                open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri))
            } else {
                open(appInfo)
            }
        },
        SetupItem(
            "DISPLAY OVER OTHER APPS",
            "Lets the takeover appear over whatever app is open, and come back if you leave it while its lock holds.",
            Settings.canDrawOverlays(context),
            "OPEN",
        ) { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)) },
        SetupItem(
            "BATTERY: UNRESTRICTED",
            "Stops Android from putting VIGIL to sleep, so protocols start on time.",
            pm?.isIgnoringBatteryOptimizations(pkg) ?: false,
            "OPEN",
        ) {
            @Suppress("BatteryLife")
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
        },
        SetupItem(
            "EXACT ALARMS",
            "Planned protocols, prayer heads-ups and the end of a lock happen on the minute.",
            TriggerSetup.exactAlarms(context),
            "OPEN",
        ) { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)) },
        SetupItem(
            "DO NOT DISTURB ACCESS",
            "Jummah silences the phone: Do Not Disturb (priority only) for the Jummah lock, and back as it was after. " +
                "Turn on VIGIL under Do Not Disturb access. Without it the lock still shows, but the phone can ring.",
            Dnd.hasAccess(context),
            "OPEN",
        ) { open(Dnd.accessIntent()) },
        SetupItem(
            "PHYSICAL ACTIVITY",
            "Ends a walk when you stop moving (10 minutes without steps), and lets the movement nudge come after 90 still " +
                "minutes. VIGIL reads only the step count. Without it a walk ends by its time (a commute's end, or 3 hours)." +
                if (StepCounter.present(context)) "" else " This phone has no step counter.",
            StepCounter.permitted(context),
            "ALLOW",
            optional = true,
        ) { requestActivity() },
    )
}
