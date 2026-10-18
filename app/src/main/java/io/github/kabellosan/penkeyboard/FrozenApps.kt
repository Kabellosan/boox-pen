package io.github.kabellosan.penkeyboard

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Apps Boox has frozen: disabled (or hidden), so Android won't start them.
 *
 * Only the system may switch another app back on, and Android's App info page
 * has no Enable button for apps you installed yourself. Boox's launcher is a
 * system app, and tapping a frozen app there unfreezes and opens it. So the
 * helper (accessibility service) does that tap (see [Unfreezer]).
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
}
