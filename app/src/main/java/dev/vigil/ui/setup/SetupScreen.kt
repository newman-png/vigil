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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.vigil.glyph.GlyphFactory
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.Rule
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

/**
 * The first-run checklist (also reachable from the home screen). Everything VIGIL needs to fire reliably
 * on a sideloaded install, with a button straight to the right settings page.
 *
 * [extraItems] lets each trigger add its own permissions (notification access, usage access, ...).
 */
@Composable
fun SetupScreen(
    @Suppress("UNUSED_PARAMETER") refreshKey: Int,
    requestNotifications: () -> Unit,
    extraItems: List<SetupItem>,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val items = baseItems(context, requestNotifications) + extraItems
    val missing = items.count { it.granted == false && !it.optional }

    Column {
        PixelText("SETUP", Type.Title)
        PixelText(
            "VIGIL can only keep its promises if Android lets it. Grant each item; come back here any time from the home screen.",
            Type.Small,
        )
        Spacer(Modifier.height(8.dp))
        PixelText(if (missing == 0) "ALL REQUIRED ITEMS GRANTED" else "$missing REQUIRED ITEMS MISSING", Type.Heading, color = if (missing == 0) Palette.White else Palette.Red)
        for (item in items) {
            Rule()
            SetupRow(item)
        }
        Rule()
        PixelText("NOTHING PHONE", Type.Heading)
        PixelText(
            "Battery: Settings › Apps › VIGIL › Battery › Unrestricted (the battery item above opens this). " +
                "If directives stop firing after a while, don't swipe VIGIL away in Recents.",
            Type.Small,
        )
        Spacer(Modifier.height(6.dp))
        PixelText(
            if (GlyphFactory.AVAILABLE) {
                "Glyph lights: included. On Android 16 they work without setup. On Android 15, enable Glyph debug mode " +
                    "from a computer (lasts 48 hours): adb shell settings put global nt_glyph_interface_debug_enable 1"
            } else {
                "Glyph lights: this build was made without Nothing's Glyph SDK."
            },
            Type.Small,
        )
        Spacer(Modifier.height(16.dp))
        PixelButton(if (missing == 0) "DONE" else "CONTINUE ANYWAY", onDone, Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SetupRow(item: SetupItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val status = when (item.granted) {
                true -> "[OK]"
                false -> if (item.optional) "[OPTIONAL]" else "[MISSING]"
                null -> "[CHECK]"
            }
            PixelText("$status ${item.title}", Type.Body, color = if (item.granted == false && !item.optional) Palette.Red else Palette.White)
            PixelText(item.why, Type.Small)
        }
        Spacer(Modifier.width(8.dp))
        if (item.granted != true) PixelButton(item.action, item.onAction)
    }
}

private fun baseItems(context: Context, requestNotifications: () -> Unit): List<SetupItem> {
    val pkg = context.packageName
    val packageUri = Uri.parse("package:$pkg")
    val nm = context.getSystemService(NotificationManager::class.java)
    val pm = context.getSystemService(PowerManager::class.java)
    val am = context.getSystemService(AlarmManager::class.java)
    fun open(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
    }
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
