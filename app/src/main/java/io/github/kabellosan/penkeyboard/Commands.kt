package io.github.kabellosan.penkeyboard

import android.content.pm.PackageManager

/** Something a written word can run in command mode: an action, or an app to open. */
data class Command(val label: String, val action: Action, val app: AppEntry? = null)

/**
 * The keyboard's command mode: a written word is matched against action names
 * (English and Danish) and installed app names, on the same scale, so "back"
 * goes back and "tele" opens Telegram.
 */
object Commands {

    /** Words for the actions that make sense with no text field, each in en + da. */
    private val NAMED = listOf(
        listOf("back", "tilbage") to Action.BACK,
        listOf("home", "hjem") to Action.HOME,
        listOf("recents", "recent", "seneste") to Action.RECENTS,
        listOf("notifications", "notifikationer") to Action.NOTIFICATIONS,
        listOf("screenshot", "skærmbillede") to Action.SCREENSHOT,
        listOf("note", "notat", "quick note") to Action.QUICK_NOTE,
        listOf("scroll", "rul") to Action.PEN_SCROLL,
        listOf("pad", "pen pad") to Action.PEN_PAD,
    )

    /**
     * [candidates] are the recognizer's guesses, best first. Returns commands
     * sorted by score, best first; actions go before apps on a tie.
     */
    fun match(candidates: List<String>, apps: AppMatcher, pm: PackageManager): List<Pair<Command, Int>> {
        val best = LinkedHashMap<Action, Int>()
        candidates.take(8).forEachIndexed { rank, raw ->
            val q = AppMatcher.norm(raw)
            if (q.isEmpty()) return@forEachIndexed
            for ((words, action) in NAMED) {
                val s = words.maxOf { AppMatcher.score(q, AppMatcher.norm(it)) } - rank * 2
                if (s > 0 && s > (best[action] ?: 0)) best[action] = s
            }
        }
        val named = best.map { (action, s) -> Command(action.label(pm), action) to s }
        val opened = apps.match(candidates).map { (app, s) -> Command(app.label, Action.openApp(app.component), app) to s }
        return (named + opened).sortedByDescending { it.second }
    }
}
