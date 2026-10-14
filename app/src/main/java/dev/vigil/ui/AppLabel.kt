package dev.vigil.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** An app's name as the launcher shows it, or its package name when Android will not say. */
fun appLabel(context: Context, packageName: String): String = try {
    val pm = context.packageManager
    val info = pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    pm.getApplicationLabel(info).toString()
} catch (e: PackageManager.NameNotFoundException) {
    packageName
}

/** [appLabel] for the current screen, as a function the formatters can take. */
@Composable
fun rememberAppLabel(): (String) -> String {
    val context = LocalContext.current
    return remember(context) { { packageName: String -> appLabel(context, packageName) } }
}
