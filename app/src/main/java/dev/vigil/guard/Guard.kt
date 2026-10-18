package dev.vigil.guard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager

/**
 * The Guard for the SETUP screen: whether VIGIL's accessibility service (the pool enforcer, [GuardService]) is
 * switched on, and the system page where the user switches it on. A sideloaded app's accessibility switch stays grey
 * until "Allow restricted settings" is chosen in VIGIL's App info (the three-dot menu).
 */
object Guard {
    private const val TAG = "VigilGuard"

    /** Settings highlights the item named by this extra (Pixel and AOSP-based phones; ignored elsewhere). */
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    /** The Guard's component, as the accessibility settings list it. */
    fun component(context: Context): ComponentName = ComponentName(context, GuardService::class.java)

    /** True when the Guard is switched on in the accessibility settings (or is running right now). */
    fun accessibilityEnabled(context: Context): Boolean {
        if (GuardService.connected) return true
        val me = component(context)
        val listed = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read the enabled accessibility services: ${e.message}")
            null
        }
        if (listed != null && listed.split(':').any { ComponentName.unflattenFromString(it.trim()) == me }) return true
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return try {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
                val service = info.resolveInfo?.serviceInfo
                service != null && service.packageName == me.packageName && service.name == me.className
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not list the enabled accessibility services: ${e.message}")
            false
        }
    }

    /** The system's accessibility settings, with the Guard highlighted where the phone supports it. */
    fun accessibilitySettingsIntent(context: Context): Intent {
        val name = component(context).flattenToString()
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, name)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, name) })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
