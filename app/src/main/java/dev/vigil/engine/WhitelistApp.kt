package dev.vigil.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.SystemClock
import android.provider.AlarmClock
import android.util.Log
import dev.vigil.core.protocol.AppsButtonEntry

/**
 * One app of the takeover's APPS button (the whitelist: usable in every lock), resolved on this phone. [launch] opens
 * it in its own task.
 */
data class WhitelistApp(val label: String, val packageName: String, val launch: Intent)

/**
 * Resolves the data file's `apps_button` entries to installed apps (design section 1, "Whitelist"): `dialer` = the
 * default phone app, `clock` = the app that shows the alarms, `adhan` = the adhan app picked in SETUP, a package when
 * it is installed, `label_contains` = the first launcher app whose label contains that text. Missing apps are left
 * out. Cached for a few minutes (and until the adhan app setting changes).
 */
internal object Whitelist {
    private const val TAG = "VigilWhitelist"
    private const val TTL_MS = 5 * 60_000L

    private class Cache(val key: Any, val at: Long, val apps: List<WhitelistApp>)

    @Volatile
    private var cache: Cache? = null

    /** The resolved apps, in file order, one per package. Each call returns fresh intents (callers may change them). */
    fun apps(context: Context, entries: List<AppsButtonEntry>, adhanPackages: Set<String>): List<WhitelistApp> =
        cached(context, entries, adhanPackages).map { it.copy(launch = Intent(it.launch)) }

    /** The resolved apps' package names. */
    fun packages(context: Context, entries: List<AppsButtonEntry>, adhanPackages: Set<String>): Set<String> =
        cached(context, entries, adhanPackages).mapTo(HashSet()) { it.packageName }

    private fun cached(context: Context, entries: List<AppsButtonEntry>, adhanPackages: Set<String>): List<WhitelistApp> {
        val key = entries to adhanPackages
        val now = SystemClock.elapsedRealtime()
        val c = cache
        if (c != null && c.key == key && now - c.at < TTL_MS) return c.apps
        return resolve(context, entries, adhanPackages).also { cache = Cache(key, now, it) }
    }

    private fun resolve(context: Context, entries: List<AppsButtonEntry>, adhanPackages: Set<String>): List<WhitelistApp> {
        val pm = context.packageManager
        val out = LinkedHashMap<String, WhitelistApp>()
        for (e in entries) {
            val role = e.role
            val pkg = e.packageName
            val labelContains = e.labelContains
            val app = try {
                when {
                    role != null -> byRole(context, pm, role, adhanPackages)
                    pkg != null -> launchable(pm, pkg)
                    labelContains != null -> byLabel(pm, labelContains)
                    else -> null
                }
            } catch (ex: RuntimeException) {
                Log.w(TAG, "Could not resolve an APPS entry ($e): ${ex.message}")
                null
            }
            if (app != null && app.packageName != context.packageName && app.packageName !in out) out[app.packageName] = app
        }
        return out.values.toList()
    }

    private fun byRole(context: Context, pm: PackageManager, role: String, adhanPackages: Set<String>): WhitelistApp? = when (role) {
        "dialer" -> {
            val pkg = Device.defaultDialer(context) ?: resolvedPackage(pm, Intent(Intent.ACTION_DIAL))
            pkg?.let { launchable(pm, it) ?: WhitelistApp(label(pm, it) ?: "Phone", it, Intent(Intent.ACTION_DIAL).setPackage(it).newTask()) }
        }
        "clock" -> {
            val show = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            resolvedPackage(pm, show)?.let { launchable(pm, it) ?: WhitelistApp(label(pm, it) ?: "Clock", it, show.setPackage(it).newTask()) }
        }
        "adhan" -> adhanPackages.sorted().firstNotNullOfOrNull { launchable(pm, it) }
        else -> null
    }

    /** The app that handles [intent]: the default one, else the first that can (never the system chooser). */
    private fun resolvedPackage(pm: PackageManager, intent: Intent): String? {
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        val default = pm.resolveActivity(intent, flags)?.activityInfo?.packageName
        if (default != null && default != "android") return default
        return pm.queryIntentActivities(intent, flags).firstOrNull()?.activityInfo?.packageName
    }

    /** [pkg] with its launcher intent, or null when it is not installed or has no launcher entry. */
    private fun launchable(pm: PackageManager, pkg: String): WhitelistApp? {
        val launch = pm.getLaunchIntentForPackage(pkg) ?: return null
        return WhitelistApp(label(pm, pkg) ?: pkg, pkg, launch.newTask())
    }

    private fun label(pm: PackageManager, pkg: String): String? = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** The first launcher app (by label) whose label contains [text], ignoring case. */
    private fun byLabel(pm: PackageManager, text: String): WhitelistApp? {
        if (text.isBlank()) return null
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            .map { it to labelOf(pm, it) }
            .filter { (_, label) -> label.contains(text, ignoreCase = true) }
            .sortedBy { (_, label) -> label.lowercase() }
        val (info, label) = matches.firstOrNull() ?: return null
        val pkg = info.activityInfo?.packageName ?: return null
        return launchable(pm, pkg)?.copy(label = label)
    }

    private fun labelOf(pm: PackageManager, info: ResolveInfo): String = try {
        info.loadLabel(pm).toString()
    } catch (e: RuntimeException) {
        ""
    }

    private fun Intent.newTask(): Intent = addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
