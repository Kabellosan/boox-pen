package io.github.kabellosan.penkeyboard

import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserManager
import android.util.Log
import java.text.Normalizer

data class AppEntry(val label: String, val component: ComponentName)

/**
 * Finds installed apps whose name matches what was written.
 *
 * The app list is gathered three ways and merged, because Boox firmware has been
 * seen to hide apps from a plain launcher query (Telegram was missing on a Go 7):
 * LauncherApps (what home screens use, all profiles), the launcher intent query,
 * and installed packages that have a launch intent.
 */
class AppMatcher(
    private val pm: PackageManager,
    private val own: String,
    private val context: Context? = null, // needed for the LauncherApps source
) {
    constructor(context: Context) : this(context.packageManager, context.packageName, context)

    val apps: List<AppEntry> = loadApps()

    private fun loadApps(): List<AppEntry> {
        val found = LinkedHashMap<String, AppEntry>() // keyed by package

        fun add(label: CharSequence?, component: ComponentName) {
            if (component.packageName == own || component.packageName in found) return
            val name = label?.toString()?.takeIf { it.isNotBlank() } ?: component.packageName
            found[component.packageName] = AppEntry(name, component)
        }

        if (context != null) try {
            val launcher = context.getSystemService(LauncherApps::class.java)
            val users = context.getSystemService(UserManager::class.java).userProfiles
            for (user in users) {
                for (a in launcher.getActivityList(null, user)) add(a.label, a.componentName)
            }
        } catch (e: Throwable) {
            Log.w(PenApp.TAG, "LauncherApps failed: ${e.message}")
        }

        try {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (r in pm.queryIntentActivities(main, PackageManager.MATCH_ALL)) {
                add(r.loadLabel(pm), ComponentName(r.activityInfo.packageName, r.activityInfo.name))
            }
        } catch (e: Throwable) {
            Log.w(PenApp.TAG, "Launcher query failed: ${e.message}")
        }

        try {
            for (info in pm.getInstalledApplications(0)) {
                if (info.packageName in found) continue
                val component = pm.getLaunchIntentForPackage(info.packageName)?.component ?: continue
                add(info.loadLabel(pm), component)
            }
        } catch (e: Throwable) {
            Log.w(PenApp.TAG, "Installed apps query failed: ${e.message}")
        }

        Log.i(PenApp.TAG, "AppMatcher: ${found.size} apps")
        return found.values.toList()
    }

    /**
     * [candidates] are the recognizer's guesses, best first.
     * Returns apps sorted by score, best first, with their scores.
     */
    fun match(candidates: List<String>): List<Pair<AppEntry, Int>> {
        val best = HashMap<AppEntry, Int>()
        candidates.take(8).forEachIndexed { rank, raw ->
            val q = norm(raw)
            if (q.isEmpty()) return@forEachIndexed
            for (app in apps) {
                val byLabel = score(q, norm(app.label))
                val byPackage = packageScore(q, app.component.packageName)
                val s = maxOf(byLabel, byPackage) - rank * 2
                if (s > 0 && s > (best[app] ?: 0)) best[app] = s
            }
        }
        return best.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    companion object {
        const val PREFIX = 100

        fun norm(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
                .replace(Regex("[^a-z0-9æøå ]"), "")
                .trim()

        /** Package names like org.telegram.messenger: a part starting with [q] counts, below a name match. */
        fun packageScore(q: String, pkg: String): Int {
            val qt = q.replace(" ", "")
            if (qt.length < 3) return 0
            val parts = pkg.lowercase().split('.').filter { it !in GENERIC_PARTS }
            return if (parts.any { it.startsWith(qt) }) 85 else 0
        }

        private val GENERIC_PARTS = setOf("com", "org", "net", "io", "app", "android", "github", "google")

        fun score(q: String, label: String): Int {
            val tight = label.replace(" ", "")
            val qt = q.replace(" ", "")
            if (qt.isEmpty() || tight.isEmpty()) return 0
            if (tight.startsWith(qt)) return PREFIX + qt.length
            if (label.split(" ").any { it.startsWith(qt) }) return 90
            if (tight.contains(qt)) return 80
            // Allow small recognition mistakes: compare with the start of the name.
            val head = tight.take(qt.length)
            val d = levenshtein(qt, head)
            val allowed = maxOf(1, qt.length / 3)
            return if (qt.length >= 3 && d <= allowed) 70 - 15 * d else 0
        }

        private fun levenshtein(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(
                        dp[j] + 1,
                        dp[j - 1] + 1,
                        prev + if (a[i - 1] == b[j - 1]) 0 else 1
                    )
                    prev = tmp
                }
            }
            return dp[b.length]
        }
    }
}
