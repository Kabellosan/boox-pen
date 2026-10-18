package io.github.kabellosan.penkeyboard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Opens an app Boox froze, the way you would: on Boox's Apps screen, tapping
 * a frozen app unfreezes and opens it (the launcher is a system app, so it may).
 * Run by the helper, which can read and press what's on screen.
 *
 * Goes Home, presses Apps, then looks for the app's name on screen, turning
 * pages (forward, then back once the last page stops changing) until it shows
 * up, and taps its icon. The tap is a real touch, not an accessibility
 * "click": Boox's app grid ignores those (seen on the Go 7), and it also keeps
 * neighbouring pages' names in its layout, so only names visible on screen count.
 * If the tap does nothing, it presses and holds the icon and picks Unfreeze
 * from Boox's menu.
 * If the app isn't open and on again in time, every step and the last
 * screen's labels go to the key log.
 */
class Unfreezer(private val service: AccessibilityService) {

    private enum class Step { APPS_TAB, FIND, MENU }

    private val main = Handler(Looper.getMainLooper())
    private val pm get() = service.packageManager
    private var app: ComponentName? = null
    private var label = ""
    private var step = Step.APPS_TAB
    private var until = 0L
    private var settleUntil = 0L
    private var tapped = false
    private var tappedAt = 0L
    private var taps = 0
    private var viaMenu = false
    private var forward = true
    private var pages = 0
    private var launcherPackage: String? = null
    private var lastPage = ""
    private var lastLabels = ""
    private val trail = ArrayList<String>()

    fun start(app: ComponentName, label: String) {
        main.removeCallbacks(tick)
        this.app = app
        this.label = label
        val now = SystemClock.uptimeMillis()
        until = now + TIMEOUT_MS
        step = Step.APPS_TAB
        tapped = false
        taps = 0
        viaMenu = false
        forward = true
        pages = 0
        lastPage = ""
        lastLabels = ""
        trail.clear()
        try {
            service.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            finish("couldn't go home: ${e.message}")
            return
        }
        settleUntil = now + SETTLE_MS
        main.postDelayed(tick, POLL_MS)
    }

    private val tick = object : Runnable {
        override fun run() {
            val app = app ?: return
            if (!FrozenApps.isFrozen(pm, app.packageName)) {
                finish("unfroze")
                // Tapping it in Apps opens it too; Unfreeze in the menu doesn't.
                if (!tapped || viaMenu) try {
                    service.startActivity(Action.launchIntent(app))
                } catch (e: Exception) {}
                return
            }
            val now = SystemClock.uptimeMillis()
            if (now > until) {
                finish("gave up")
                return
            }
            val root = service.rootInActiveWindow?.takeIf { it.packageName?.toString() != service.packageName }
            if (root != null) {
                lastLabels = "${root.packageName}: " + shortLabels(root, visibleOnly = true).take(16).joinToString(", ")
                if (now >= settleUntil) act(root, now)
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun act(root: AccessibilityNodeInfo, now: Long) {
        when (step) {
            Step.APPS_TAB -> {
                val apps = find(root) { textIs(it, "Apps") }
                if (apps != null && click(apps)) note("pressed Apps")
                else note("no Apps button, looking here")
                step = Step.FIND
                settleUntil = now + SETTLE_MS
            }
            Step.FIND -> {
                // Tapped: wait for Boox to unfreeze it. If the grid still shows a
                // while later, the tap did nothing: press and hold for Boox's menu.
                if (tapped && (now - tappedAt < RETAP_MS || taps >= 2 ||
                        root.packageName?.toString() != launcherPackage)) return
                val name = find(root) { !it.isEditable && it.isVisibleToUser && namesApp(it) }
                if (name != null) {
                    val (x, y) = iconSpot(name)
                    val hold = taps > 0
                    if (touch(x, y, if (hold) HOLD_MS else TAP_MS, if (hold) "held" else "tapped")) {
                        tapped = true
                        tappedAt = now
                        taps++
                        launcherPackage = root.packageName?.toString()
                        note("${if (hold) "held" else "tapped"} $label at ${x.toInt()},${y.toInt()}")
                        if (hold) { step = Step.MENU; settleUntil = now + SETTLE_MS + HOLD_MS }
                    }
                    return
                }
                val page = shortLabels(root, visibleOnly = true).joinToString("|")
                if (pages > 0 && page == lastPage) {
                    // The page didn't change: that was the last one this way.
                    if (!forward) return
                    forward = false
                    note("back")
                }
                if (pages >= MAX_PAGES) return
                lastPage = page
                pages++
                turnPage(root)
                settleUntil = now + SETTLE_MS
            }
            // Boox's press-and-hold menu: Uninstall, Manage, Optimize, Unfreeze, Add to Group.
            Step.MENU -> {
                // The menu may be a popup window of its own, not the active one.
                val roots = listOf(root) + service.windows.mapNotNull { it.root }
                val item = roots.firstNotNullOfOrNull { w -> find(w) { it.isVisibleToUser && textIs(it, "Unfreeze") } }
                if (item == null) {
                    if (now - tappedAt > MENU_WAIT_MS) { note("no Unfreeze in the menu"); step = Step.FIND; taps = 2 }
                    return
                }
                val r = Rect().also { item.getBoundsInScreen(it) }
                if (touch(r.exactCenterX(), r.exactCenterY(), TAP_MS, "Unfreeze")) {
                    note("tapped Unfreeze")
                    viaMenu = true
                }
                step = Step.FIND
                taps = 2 // no more tries; wait for Boox
                tappedAt = now
            }
        }
    }

    /**
     * Where to touch the app: its icon, just above the name. Touches on the
     * name itself did nothing in Boox's grid (Go 7).
     */
    private fun iconSpot(name: AccessibilityNodeInfo): Pair<Float, Float> {
        val r = Rect().also { name.getBoundsInScreen(it) }
        val parent = name.parent
        if (parent != null) for (i in 0 until parent.childCount) {
            val c = parent.getChild(i) ?: continue
            if (!c.text.isNullOrEmpty()) continue
            val b = Rect().also { c.getBoundsInScreen(it) }
            if (!b.isEmpty && b.bottom <= r.top + 4 && b.contains(r.centerX(), b.centerY())) {
                return b.exactCenterX() to b.exactCenterY()
            }
        }
        return r.exactCenterX() to (r.top - ICON_ABOVE_DP * service.resources.displayMetrics.density)
    }

    /** Scrolls the app grid a page, or swipes across the screen if it can't be scrolled. */
    private fun turnPage(root: AccessibilityNodeInfo) {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val list = find(root) { it.isScrollable }
        if (list != null && list.performAction(action)) {
            note(if (forward) "next page" else "previous page")
            return
        }
        val m = service.resources.displayMetrics
        val y = m.heightPixels * 0.45f
        val (from, to) = if (forward) m.widthPixels * 0.85f to m.widthPixels * 0.15f
            else m.widthPixels * 0.15f to m.widthPixels * 0.85f
        val path = Path().apply { moveTo(from, y); lineTo(to, y) }
        service.dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_MS)).build(), null, null)
        note(if (forward) "swiped to next page" else "swiped to previous page")
    }

    /** A real touch at ([x], [y]) on screen for [ms]; a cancelled one is noted as [what]. */
    private fun touch(x: Float, y: Float, ms: Long, what: String): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return service.dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCancelled(gestureDescription: GestureDescription?) { note("$what was cancelled") }
            }, null)
    }

    /** Matches the app's name, also when the launcher cut a long one short ("Wheel of Time Compendium (Unoffic…"). */
    private fun namesApp(n: AccessibilityNodeInfo): Boolean {
        val t = n.text?.toString()?.trim() ?: return false
        if (t.equals(label, ignoreCase = true)) return true
        val cut = t.removeSuffix("…").removeSuffix("...")
        return cut.length != t.length && cut.length >= 8 && label.startsWith(cut, ignoreCase = true)
    }

    private fun finish(outcome: String) {
        val pkg = app?.packageName
        app = null
        main.removeCallbacks(tick)
        val screen = if (outcome == "unfroze" || lastLabels.isEmpty()) "" else " | $lastLabels"
        PenButtonProbe.record(service, "Unfreeze $pkg: $outcome (${trail.joinToString(" > ")})$screen")
    }

    private fun note(step: String) { trail += step }

    companion object {
        private const val TIMEOUT_MS = 25_000L
        private const val POLL_MS = 300L
        private const val SETTLE_MS = 1_000L
        private const val SWIPE_MS = 250L
        private const val TAP_MS = 80L
        private const val HOLD_MS = 900L
        private const val MENU_WAIT_MS = 4_000L
        private const val ICON_ABOVE_DP = 40
        private const val RETAP_MS = 4_000L
        private const val MAX_PAGES = 12

        private fun textIs(n: AccessibilityNodeInfo, s: String) =
            n.text?.toString()?.trim()?.equals(s, ignoreCase = true) == true

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

        /** Short labels on screen (app names and buttons, mostly). */
        fun shortLabels(
            root: AccessibilityNodeInfo,
            visibleOnly: Boolean = false,
            out: MutableList<String> = ArrayList(),
        ): List<String> {
            if (!visibleOnly || root.isVisibleToUser) (root.text ?: root.contentDescription)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 40 }?.let { out += it }
            for (i in 0 until root.childCount) root.getChild(i)?.let { shortLabels(it, visibleOnly, out) }
            return out
        }
    }
}
