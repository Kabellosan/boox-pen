package io.github.kabellosan.penkeyboard

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Apps Boox has frozen: disabled (or hidden), so Android won't start them.
 *
 * Only the system may switch another app back on, so we can't unfreeze one
 * ourselves. What we can do is open the app's App info page, whose button
 * does it. With the helper (accessibility service) on, the helper presses that
 * button too, so writing a frozen app's name still opens it.
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

    fun appInfoIntent(pkg: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Button labels that switch an app back on, in English and Danish. Matched
     * whole, so "Disable" and the like are never pressed.
     */
    private val ENABLE_WORDS = setOf(
        "enable", "unfreeze", "activate", "turn on",
        "aktivér", "aktiver", "optø", "slå til",
    )

    /** The Enable/Unfreeze button on screen, as something clickable, or null. */
    fun findEnableButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = (root.text ?: root.contentDescription)?.toString()?.trim()?.lowercase()
        if (label != null && label in ENABLE_WORDS && root.isEnabled) {
            var n: AccessibilityNodeInfo? = root
            while (n != null && !n.isClickable) n = n.parent
            if (n != null) return n
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findEnableButton(child)?.let { return it }
        }
        return null
    }

    /** Short labels on screen (buttons, mostly), to log when no button was found. */
    fun shortLabels(root: AccessibilityNodeInfo, out: MutableList<String> = ArrayList()): List<String> {
        (root.text ?: root.contentDescription)?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 24 }?.let { out += it }
        for (i in 0 until root.childCount) root.getChild(i)?.let { shortLabels(it, out) }
        return out
    }
}
