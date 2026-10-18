package io.github.kabellosan.penkeyboard

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Unfreezes an app through Boox's Freeze Settings screen, then opens it.
 * Run by the helper, which can read and press what's on screen.
 *
 * Freeze Settings lists apps with a toggle each, a few per page, with a search
 * button. The app's row is found by its name (paging, then searching, if it's
 * not on the first page) and its toggle pressed once. Done when Android reports
 * the app on again. If that doesn't happen in time, every step taken and the
 * labels on screen go to the key log, so the next build can follow Boox's UI.
 *
 * If no Boox screen with "freeze" in its name can be opened directly, it goes
 * home, presses Apps, then the snowflake (which opens Freeze Settings).
 */
class Unfreezer(private val service: AccessibilityService) {

    private enum class Step { APPS_TAB, SNOWFLAKE, FIND, SEARCH_FIELD }

    private val main = Handler(Looper.getMainLooper())
    private val pm get() = service.packageManager
    private var app: ComponentName? = null
    private var label = ""
    private var step = Step.FIND
    private var until = 0L
    private var settleUntil = 0L
    private var toggled = false
    private var scrolls = 0
    private var searched = false
    private val trail = ArrayList<String>()

    fun start(app: ComponentName, label: String) {
        main.removeCallbacks(tick)
        this.app = app
        this.label = label
        val now = SystemClock.uptimeMillis()
        until = now + TIMEOUT_MS
        toggled = false
        scrolls = 0
        searched = false
        trail.clear()
        val screen = FrozenApps.freezeSettingsIntent(pm)
        try {
            if (screen != null) {
                service.startActivity(screen)
                step = Step.FIND
                note("opened ${screen.component?.className}")
            } else {
                val screens = FrozenApps.freezeScreens(pm)
                note("no freeze screen to open (${screens.joinToString { it.first.className }})")
                service.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                step = Step.APPS_TAB
            }
        } catch (e: Exception) {
            finish("couldn't open a screen: ${e.message}", null)
            return
        }
        settleUntil = now + SETTLE_MS
        main.postDelayed(tick, POLL_MS)
    }

    private val tick = object : Runnable {
        override fun run() {
            val app = app ?: return
            if (!FrozenApps.isFrozen(pm, app.packageName)) {
                finish("unfroze", null)
                try {
                    service.startActivity(Action.launchIntent(app))
                } catch (e: Exception) {
                    pm.getLaunchIntentForPackage(app.packageName)?.let {
                        service.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                return
            }
            val root = service.rootInActiveWindow?.takeIf { it.packageName?.toString() != service.packageName }
            val now = SystemClock.uptimeMillis()
            if (now > until) {
                finish("gave up", root)
                return
            }
            if (now >= settleUntil && root != null) act(root, now)
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun act(root: AccessibilityNodeInfo, now: Long) {
        when (step) {
            Step.APPS_TAB -> find(root) { textIs(it, "Apps") }?.let {
                if (click(it)) { note("pressed Apps"); step = Step.SNOWFLAKE; settleUntil = now + SETTLE_MS }
            }
            Step.SNOWFLAKE -> find(root) { mentions(it, "freeze") }?.let {
                if (click(it)) { note("pressed the snowflake"); step = Step.FIND; settleUntil = now + SETTLE_MS }
            }
            Step.FIND -> {
                if (toggled) return // pressed once; never press again, it would freeze it back
                val name = find(root) { !it.isEditable && textIs(it, label) }
                if (name != null) {
                    val toggle = toggleFor(name)
                    if (toggle == null) {
                        note("found $label, but no toggle by it")
                        toggled = true
                    } else if (toggle.isCheckable && !toggle.isChecked) {
                        note("$label's toggle is already off")
                        toggled = true
                    } else if (click(toggle)) {
                        note("pressed $label's toggle")
                        toggled = true
                    }
                    return
                }
                if (scrolls < MAX_SCROLLS) {
                    val list = find(root) { it.isScrollable }
                    if (list != null && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                        scrolls++
                        note("next page")
                        settleUntil = now + SETTLE_MS
                        return
                    }
                    scrolls = MAX_SCROLLS
                }
                if (!searched) {
                    searched = true
                    val search = find(root) { mentions(it, "search") }
                    if (search != null && click(search)) {
                        note("pressed search")
                        step = Step.SEARCH_FIELD
                        settleUntil = now + SETTLE_MS
                    }
                }
            }
            Step.SEARCH_FIELD -> find(root) { it.isEditable }?.let { field ->
                val text = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, label)
                }
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)
                note("searched for $label")
                step = Step.FIND
                settleUntil = now + SETTLE_MS
            }
        }
    }

    /**
     * The toggle in [name]'s row. The row is the nearest ancestor that is a list
     * item or can be pressed; it must hold no other app's name, so a setting
     * like "Freeze new apps" is never pressed by mistake.
     */
    private fun toggleFor(name: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var row: AccessibilityNodeInfo = name
        for (i in 0 until 4) {
            val parent = row.parent ?: break
            if (row.isClickable || parent.isScrollable || parent.collectionInfo != null) break
            row = parent
        }
        val otherText = find(row) { n ->
            val t = n.text?.toString()?.trim().orEmpty()
            t.length > 3 && !t.equals(label, ignoreCase = true)
        }
        if (otherText != null) return null
        return find(row) { it.isCheckable }
            ?: row.takeIf { it.isClickable }
            ?: find(row) { it.isClickable }
    }

    private fun finish(outcome: String, root: AccessibilityNodeInfo?) {
        val pkg = app?.packageName
        app = null
        main.removeCallbacks(tick)
        val screen = root?.let { " | ${it.packageName}: " + shortLabels(it).take(14).joinToString(", ") }.orEmpty()
        PenButtonProbe.record(service, "Unfreeze $pkg: $outcome (${trail.joinToString(" > ")})$screen")
    }

    private fun note(step: String) { trail += step }

    companion object {
        private const val TIMEOUT_MS = 20_000L
        private const val POLL_MS = 300L
        private const val SETTLE_MS = 900L
        private const val MAX_SCROLLS = 5

        private fun textIs(n: AccessibilityNodeInfo, s: String) =
            n.text?.toString()?.trim()?.equals(s, ignoreCase = true) == true

        /** [word] in the node's description or view id, as icon buttons have no text. */
        private fun mentions(n: AccessibilityNodeInfo, word: String) =
            n.contentDescription?.toString()?.contains(word, ignoreCase = true) == true ||
                n.viewIdResourceName?.contains(word, ignoreCase = true) == true

        /** The first node under [root] (itself included) that passes [test]. */
        fun find(root: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (test(root)) return root
            for (i in 0 until root.childCount) {
                val child = root.getChild(i) ?: continue
                find(child, test)?.let { return it }
            }
            return null
        }

        /** Presses [n], or the nearest ancestor that can be pressed. */
        fun click(n: AccessibilityNodeInfo): Boolean {
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            return c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }

        /** Short labels on screen (buttons, mostly), for the log. */
        fun shortLabels(root: AccessibilityNodeInfo, out: MutableList<String> = ArrayList()): List<String> {
            (root.text ?: root.contentDescription)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 24 }?.let { out += it }
            for (i in 0 until root.childCount) root.getChild(i)?.let { shortLabels(it, out) }
            return out
        }
    }
}
