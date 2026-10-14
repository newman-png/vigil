package dev.vigil.ui.setup

import android.Manifest
import android.app.AlarmManager
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
import dev.vigil.core.plan.DayMode
import dev.vigil.core.plan.Trigger
import dev.vigil.engine.Device
import dev.vigil.engine.Vigil
import dev.vigil.trigger.AdhanListener
import dev.vigil.trigger.TriggerSetup
import dev.vigil.trigger.UsageWatcher
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.ScreenTitle
import dev.vigil.ui.components.Section
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type

/** One thing the phone must allow. [granted] null means Android cannot tell us (follow the instructions). */
class SetupItem(
    val title: String,
    val why: String,
    val granted: Boolean?,
    val action: String,
    val optional: Boolean = false,
    val onAction: () -> Unit,
)

/** Everything Android must allow: the base items and each trigger's own (notification access, usage access, ...). */
fun permissionItems(context: Context, requestNotifications: () -> Unit): List<SetupItem> =
    baseItems(context, requestNotifications) + TriggerSetup.items(context)

/** How many required permissions are missing (the home screen's SETUP hint). */
fun missingPermissions(context: Context): Int =
    permissionItems(context, requestNotifications = {}).count { it.granted == false && !it.optional }

/**
 * Everything about this phone, in collapsible sections: PERMISSIONS, BATTERY, ADHAN, APP USAGE, TAKEOVER and
 * SAFETY. Anything about the week belongs in the plan instead. On the first run it ends with DONE.
 *
 * [refreshKey] changes on every resume, so statuses are re-read after a visit to system settings.
 */
@Composable
fun SetupScreen(
    refreshKey: Int,
    firstRun: Boolean,
    requestNotifications: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val items = remember(refreshKey, requestNotifications) { permissionItems(context, requestNotifications) }
    val missing = items.count { it.granted == false && !it.optional }
    val state by Graph.state.state.collectAsState()
    val snapshot by Graph.plans.snapshot.collectAsState()
    val settingsVersion by Graph.settings.version.collectAsState()
    val connected by AdhanListener.connected.collectAsState()
    val active = remember(snapshot, state) { Graph.activeSet() }
    val dayMode = remember(snapshot, state) { Vigil.dayMode() }
    val batteryOk = remember(refreshKey) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    Column(Modifier.fillMaxWidth()) {
        ScreenTitle(
            "SETUP",
            "VIGIL can only keep its promises if Android lets it. Everything here is about this phone; " +
                "everything about the week is in the plan.",
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

        Section("BATTERY", hint = if (batteryOk) "UNRESTRICTED" else "RESTRICTED", hintColor = if (batteryOk) Palette.Dim else Palette.Red) {
            BatterySection(batteryOk)
        }

        // Red only when the running plan has adhan directives that cannot fire properly.
        val hasAdhan = active.directives.any { it.trigger is Trigger.Adhan }
        val adhanPackages = remember(settingsVersion) { Graph.settings.adhanPackages }
        val adhanHint = when {
            !connected -> "NOT CONNECTED"
            adhanPackages.isEmpty() -> "ANY APP"
            adhanPackages.size == 1 -> "1 APP"
            else -> "${adhanPackages.size} APPS"
        }
        val adhanUrgent = hasAdhan && (!connected || adhanPackages.isEmpty())
        Section("ADHAN", hint = adhanHint, hintColor = if (adhanUrgent) Palette.Red else Palette.Dim) {
            AdhanSection()
        }

        val hasUsage = active.directives.any { it.trigger is Trigger.AppUsage }
        val usageHint = remember(refreshKey) {
            when {
                UsageWatcher.accessibilityConnected -> "INSTANT"
                Device.hasUsageAccess(context) -> "EVERY ~5 S"
                else -> "OFF"
            }
        }
        Section("APP USAGE", hint = usageHint, hintColor = if (hasUsage && usageHint == "OFF") Palette.Red else Palette.Dim) {
            AppUsageSection()
        }

        Section("TAKEOVER") {
            TakeoverSection()
        }

        Section(
            "SAFETY",
            hint = when (dayMode) {
                DayMode.BAD_DAY -> "NOT OKAY TODAY"
                DayMode.MINIMUM -> "MINIMUM DAY"
                DayMode.NORMAL -> null
            },
        ) {
            SafetySection(dayMode, active)
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

@Composable
private fun BatterySection(batteryOk: Boolean) {
    val context = LocalContext.current
    PixelText(
        if (batteryOk) "Android lets VIGIL run in the background." else "Android may put VIGIL to sleep, and directives would fire late or not at all.",
        Type.Small,
    )
    Spacer(Modifier.height(8.dp))
    PixelText(
        "Nothing phones: Settings › Apps › VIGIL › Battery › Unrestricted. " +
            "If directives stop firing after a while, don't swipe VIGIL away in Recents.",
        Type.Small,
    )
    Spacer(Modifier.height(12.dp))
    PixelButton(
        "OPEN APP INFO",
        {
            TriggerSetup.open(
                context,
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                Intent(Settings.ACTION_SETTINGS),
            )
        },
        Modifier.fillMaxWidth(),
    )
}

private fun baseItems(context: Context, requestNotifications: () -> Unit): List<SetupItem> {
    val pkg = context.packageName
    val packageUri = Uri.parse("package:$pkg")
    val nm = context.getSystemService(NotificationManager::class.java)
    val pm = context.getSystemService(PowerManager::class.java)
    val am = context.getSystemService(AlarmManager::class.java)
    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    fun open(intent: Intent) = TriggerSetup.open(context, intent, appInfo)
    return listOf(
        SetupItem(
            "NOTIFICATIONS",
            "Needed for the always-on notification and to open takeovers.",
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            "ALLOW",
        ) { requestNotifications() },
        SetupItem(
            "FULL-SCREEN TAKEOVERS",
            "Lets a directive take over the screen when the phone is locked or asleep.",
            if (Build.VERSION.SDK_INT >= 34) nm?.canUseFullScreenIntent() ?: false else true,
            "OPEN",
        ) {
            if (Build.VERSION.SDK_INT >= 34) {
                open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri))
            } else {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            }
        },
        SetupItem(
            "DISPLAY OVER OTHER APPS",
            "Lets the takeover appear over whatever app is open, and come back if you leave it unfinished.",
            Settings.canDrawOverlays(context),
            "OPEN",
        ) { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)) },
        SetupItem(
            "BATTERY: UNRESTRICTED",
            "Stops Android from putting VIGIL to sleep, so directives fire on time.",
            pm?.isIgnoringBatteryOptimizations(pkg) ?: false,
            "OPEN",
        ) {
            @Suppress("BatteryLife")
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
        },
        SetupItem(
            "EXACT ALARMS",
            "Time directives fire on the minute.",
            am?.canScheduleExactAlarms() ?: false,
            "OPEN",
        ) { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)) },
        SetupItem(
            "ALLOW RESTRICTED SETTINGS",
            "Android blocks notification access and accessibility for apps installed from a file. If those pages are greyed out: " +
                "App info › ⋮ (top right) › Allow restricted settings, then come back.",
            null,
            "APP INFO",
        ) { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)) },
    )
}
