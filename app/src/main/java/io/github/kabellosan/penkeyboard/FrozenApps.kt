package io.github.kabellosan.penkeyboard

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * Apps Boox has frozen: disabled (or hidden), so Android won't start them.
 *
 * Only the system may switch another app back on, and Android's App info page
 * has no Enable button for apps you installed yourself. Boox's own Freeze
 * Settings screen does: each app has a toggle there. So Pad Launch opens that
 * screen, and the helper (accessibility service) switches the app's toggle off
 * (see [Unfreezer]).
 */
object FrozenApps {

    private const val FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES

    fun isFrozen(info: ApplicationInfo): Boolean =
        !info.enabled || (info.flags and ApplicationInfo.FLAG_INSTALLED) == 0

    fun isFrozen(pm: PackageManager, pkg: String): Boolean = try {
        isFrozen(pm.getApplicationInfo(pkg, FLAGS))
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun label(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationInfo(pkg, FLAGS).loadLabel(pm).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    /** Boox's screens with "freeze" in their name, found among its own apps. */
    fun freezeScreens(pm: PackageManager): List<Pair<ComponentName, Boolean>> {
        val found = ArrayList<Pair<ComponentName, Boolean>>()
        try {
            for (app in pm.getInstalledApplications(0)) {
                if (!app.packageName.startsWith("com.onyx")) continue
                val activities = try {
                    pm.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES).activities
                } catch (e: Exception) { null } ?: continue
                for (a in activities) {
                    if (!a.name.contains("freeze", ignoreCase = true)) continue
                    found += ComponentName(a.packageName, a.name) to (a.exported && a.enabled)
                }
            }
        } catch (e: Exception) {
            Log.w(PenApp.TAG, "Freeze screen search failed: ${e.message}")
        }
        // Settings screens first: that's the one with a toggle per app.
        return found.sortedByDescending { it.first.className.contains("setting", ignoreCase = true) }
    }

    /** An intent for Boox's Freeze Settings, if one of its screens can be opened by other apps. */
    fun freezeSettingsIntent(pm: PackageManager): Intent? =
        freezeScreens(pm).firstOrNull { it.second }?.first?.let {
            Intent().setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
