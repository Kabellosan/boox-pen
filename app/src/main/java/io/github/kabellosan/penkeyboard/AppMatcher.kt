package io.github.kabellosan.penkeyboard

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import java.text.Normalizer

data class AppEntry(val label: String, val component: ComponentName)

/** Finds installed apps whose name matches what was written. */
class AppMatcher(pm: PackageManager, ownPackage: String) {

    val apps: List<AppEntry> = pm.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
    ).map {
        AppEntry(
            it.loadLabel(pm).toString(),
            ComponentName(it.activityInfo.packageName, it.activityInfo.name)
        )
    }.filter { it.component.packageName != ownPackage }
        .distinctBy { it.component }

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
                val s = score(q, norm(app.label)) - rank * 2
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
