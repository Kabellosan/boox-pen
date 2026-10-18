package io.github.kabellosan.penkeyboard

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pen Keyboard helper, an accessibility service (off until switched on):
 * - lets Pen Pad write into the text field you were using in another app;
 * - lets the Go back shortcut press Android's real Back button;
 * - turns eraser taps on any screen (outside the keyboard's strip) into shortcuts;
 * - unfreezes an app Boox froze when Pad Launch opens it, by tapping it in Boox's Apps screen;
 * - probes whether the pen's side button sends anything the whole system can see.
 *
 * Text fields: it remembers the last editable field that got focus in another
 * app, and only reads or changes that field when Pen Pad asks it to.
 *
 * Probe: apps only get pen strokes inside their own windows, so button-held drawing
 * can't work everywhere. But an accessibility service sees hardware key events
 * from every app. If the Go 7's button sends one, a button click anywhere could
 * open Pad Launch. This service only records which keys arrive (never what
 * is typed on screen) and passes them all on untouched.
 *
 * Eraser taps anywhere: Android 13 gives no app other apps' pen strokes, and a
 * layer that took them would break normal touch. What it does allow is a window
 * that asks to hear about touches outside itself: it is told only that a touch
 * started, with the position hidden, and the touch still goes to the app below.
 * That plus the tool type (the button makes the pen an eraser) is enough to count
 * eraser taps on any screen.
 */
class PenButtonProbe : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        watchTouches()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        stopWatching()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        stopWatching()
        super.onDestroy()
    }

    // ── Eraser taps on any screen ───────────────────────────────────

    private val main = Handler(Looper.getMainLooper())
    private var watcher: View? = null
    private val seenTools = HashSet<Int>()
    private var taps = 0
    private var lastTapAt = 0L

    private val finishTaps = Runnable {
        val tap = when {
            taps >= 3 -> AnywhereTap.TRIPLE
            taps == 2 -> AnywhereTap.DOUBLE
            else -> null
        }
        taps = 0
        if (tap != null) {
            val action = Shortcuts(this).action(tap)
            // A shortcut must never take the app down (the keyboard shares its process).
            try {
                perform(action)
                record(this, "Anywhere: ${tap.label} → ${action.label(packageManager)}")
            } catch (e: Exception) {
                record(this, "Anywhere: ${tap.label} → ${action.label(packageManager)} failed: ${e.message}")
            }
        }
    }

    /** A see-through 1-pixel window in the corner that hears about every touch outside it. */
    private fun watchTouches() {
        if (watcher != null) return
        val view = View(this)
        view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onTouchAnywhere(e)
            false
        }
        val params = WindowManager.LayoutParams(1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSPARENT).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
            watcher = view
        } catch (e: Exception) {
            record(this, "Anywhere: can't watch the pen (${e.message})")
        }
    }

    private fun stopWatching() {
        main.removeCallbacks(finishTaps)
        val view = watcher ?: return
        watcher = null
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
    }

    private fun onTouchAnywhere(e: MotionEvent) {
        val tool = e.getToolType(0)
        // Android hides where a touch landed only when it landed in another app. So a
        // position means the keyboard's own strip, whose taps are its shortcuts.
        val onKeyboard = PenIME.shown && (e.x != 0f || e.y != 0f)
        if (tool != MotionEvent.TOOL_TYPE_ERASER) {
            // Once per kind and start, so the log shows the watcher works without filling up.
            if (seenTools.add(tool)) record(this, "Anywhere: ${toolName(tool)} seen (watching works)")
            if (tool == MotionEvent.TOOL_TYPE_STYLUS && !onKeyboard) {
                tipDowns.addLast(e.eventTime)
                while (tipDowns.size > 8) tipDowns.removeFirst()
            }
            return
        }
        val skip = when {
            onKeyboard -> "the keyboard"
            else -> rootInActiveWindow?.packageName?.toString()?.takeIf { it == packageName || it in PEN_APPS }
        }
        if (skip != null) {
            record(this, "Anywhere: eraser tap, left to $skip")
            return
        }
        if (e.eventTime - lastTapAt > TAP_GAP_MS) taps = 0
        lastTapAt = e.eventTime
        taps++
        record(this, "Anywhere: eraser tap $taps")
        main.removeCallbacks(finishTaps)
        main.postDelayed(finishTaps, TAP_GAP_MS)
    }

    /** When the pen tip last touched down outside the keyboard, oldest first. */
    private val tipDowns = ArrayDeque<Long>()

    /**
     * How many tip touches started in the [windowMs] before [time] (both uptime ms),
     * forgetting them so they count once. For page button + tip taps: Boox sends a
     * page press only when the button is let go, so the taps come before the key.
     */
    fun takeTipTaps(time: Long, windowMs: Long): Int {
        val n = tipDowns.count { it in (time - windowMs)..time }
        if (n > 0) tipDowns.clear()
        return n
    }

    private fun toolName(tool: Int) = when (tool) {
        MotionEvent.TOOL_TYPE_STYLUS -> "pen tip"
        MotionEvent.TOOL_TYPE_FINGER -> "finger"
        else -> "tool $tool"
    }

    /** Runs a shortcut from here, where there is no keyboard to type with. */
    private fun perform(action: Action) {
        when (action) {
            Action.NONE -> {}
            // With the keyboard open, Back would only close it: close it first, then go back.
            Action.BACK -> if (PenIME.shown) {
                PenIME.instance?.requestHideSelf(0)
                main.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 150)
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            Action.QUICK_PAD -> launch(Intent(this, LauncherActivity::class.java))
            Action.PEN_PAD -> launch(Intent(this, PenPadActivity::class.java))
            Action.FULL_SCREEN -> if (PenIME.shown) PenIME.toggleFullScreen()
            Action.SEND -> pressEnter()
            Action.HIDE_KEYBOARD -> PenIME.instance?.requestHideSelf(0)
            in Action.FIELD -> if (PenIME.shown) PenIME.runFieldAction(action) else fieldAction(action)
            in Action.GLOBAL -> globalAction(action)
            Action.LANG -> Shortcuts.setLanguage(this, if (Shortcuts.language(this) == "da") "en" else "da")
            Action.DELETE_WORD -> {
                val before = textBeforeCursor(64)
                val word = Regex("\\S+\\s*$").find(before)?.value ?: before.takeLastWhile { it.isWhitespace() }
                if (word.isNotEmpty()) edit(word.length, "")
            }
            else -> if (BooxTools.isTool(action)) BooxTools.run(this, action) else action.app?.let {
                if (FrozenApps.isFrozen(packageManager, it.packageName)) unfreezeAndOpen(it)
                else startActivity(Action.launchIntent(it))
            }
        }
    }

    // ── Unfreezing apps Boox froze ──────────────────────────────────

    private val unfreezer by lazy { Unfreezer(this) }

    /** Taps [app] in Boox's Apps screen, which unfreezes and opens it. */
    fun unfreezeAndOpen(app: ComponentName, label: String = FrozenApps.label(packageManager, app.packageName)) =
        unfreezer.start(app, label)

    private fun launch(intent: Intent) = startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // A held key repeats its down event; one line per press is enough.
        if (event.repeatCount > 0) return false
        val action = if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"
        record(this, "${KeyEvent.keyCodeToString(event.keyCode)} $action (device ${event.deviceId}, source ${event.source})")
        return false
    }

    /** The last Boox screen opened, so each one is logged once. */
    private var lastBooxWindow: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Which Boox screen opened (e.g. from the smart button), so its tools can be found.
            val pkg = event.packageName?.toString().orEmpty()
            val window = "$pkg/${event.className}"
            if ((pkg.startsWith("com.onyx") || pkg == "com.android.systemui") && window != lastBooxWindow) {
                lastBooxWindow = window
                record(this, "Window: $window")
            }
            return
        }
        val node = event?.source ?: return
        if (node.isEditable && node.packageName?.toString() != packageName) target = node
    }

    // ── Text field of the app underneath Pen Pad ────────────────────

    /** The last editable field focused in another app. */
    private var target: AccessibilityNodeInfo? = null

    /** A short name for where text will go, or null if there is no field. */
    fun targetLabel(): String? {
        val n = target ?: return null
        if (!n.refresh()) return null
        val app = n.packageName?.toString()?.let { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }
        val hint = n.hintText?.toString()?.takeIf { it.isNotBlank() }
        return listOfNotNull(app, hint).joinToString(": ").ifEmpty { "text field" }
    }

    /** Up to [n] characters before the cursor in the target field. */
    fun textBeforeCursor(n: Int): String {
        val (text, start, _) = fieldState() ?: return ""
        return text.substring(maxOf(0, start - n), start)
    }

    /**
     * Deletes [deleteBefore] characters before the cursor (and any selection),
     * inserts [insert], and puts the cursor after it. False if it didn't work.
     */
    fun edit(deleteBefore: Int, insert: String): Boolean {
        val n = target ?: return false
        val (text, start, end) = fieldState() ?: return false
        val from = maxOf(0, start - deleteBefore)
        val updated = text.substring(0, from) + insert + text.substring(end)
        val set = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, set)) return false
        val cursor = from + insert.length
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
        return true
    }

    /** Presses Home, Recents, the notifications or takes a screenshot. False if it couldn't. */
    fun globalAction(action: Action): Boolean {
        val global = when (action) {
            Action.HOME -> GLOBAL_ACTION_HOME
            Action.RECENTS -> GLOBAL_ACTION_RECENTS
            Action.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            Action.SCREENSHOT -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return false
        }
        return performGlobalAction(global)
    }

    /**
     * Cursor moves and the clipboard on the target field, for when the keyboard
     * isn't open. Undo has no accessibility action, so it needs the keyboard.
     */
    fun fieldAction(action: Action): Boolean {
        val n = target ?: return false
        val (text, _, _) = fieldState() ?: return false
        fun select(start: Int, end: Int) = n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        })
        return when (action) {
            Action.CURSOR_START -> select(0, 0)
            Action.CURSOR_END -> select(text.length, text.length)
            Action.SELECT_ALL -> select(0, text.length)
            Action.COPY -> n.performAction(AccessibilityNodeInfo.ACTION_COPY)
            Action.PASTE -> n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            else -> false
        }
    }

    /** Presses the field's Send/Done/Enter action, if it has one. */
    fun pressEnter(): Boolean {
        val n = target ?: return false
        if (!n.refresh() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /**
     * Taps the Send button of [pkg]'s screen (a chat app whose field has no Send
     * action, so Enter only makes a new line). False if there is none to find.
     */
    fun clickSend(pkg: String?): Boolean {
        val root = rootInActiveWindow ?: return false
        if (pkg != null && root.packageName?.toString() != pkg) return false
        val button = findSend(root) ?: return false
        return button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findSend(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase()
        if (label != null && label in SEND_LABELS && n.isVisibleToUser) {
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            if (c != null) return c
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { child -> findSend(child)?.let { return it } }
        return null
    }

    /** The field's text and selection, with the cursor at the end if unknown. */
    private fun fieldState(): Triple<String, Int, Int>? {
        val n = target ?: return null
        if (!n.refresh()) return null
        val text = if (n.isShowingHintText) "" else n.text?.toString().orEmpty()
        var start = n.textSelectionStart
        var end = n.textSelectionEnd
        if (start !in 0..text.length) start = text.length
        if (end !in 0..text.length) end = start
        return Triple(text, minOf(start, end), maxOf(start, end))
    }

    override fun onInterrupt() {}

    companion object {
        /** The running service, while it is switched on. */
        var instance: PenButtonProbe? = null
            private set

        /**
         * Most from one eraser touch-down to the next for them to count as one series
         * of taps. Short, so quick rubbing-out strokes in other apps don't add up.
         */
        private const val TAP_GAP_MS = 500L

        /** Apps where the eraser is for erasing (Boox Notes): their taps are left alone. */
        private val PEN_APPS = setOf("com.onyx.android.note")

        /** What chat apps call their Send button, in English and Danish. */
        private val SEND_LABELS = setOf("send", "send message", "send besked", "send meddelelse")

        private const val PREFS = "pen_button_probe"
        private const val LOG = "log"

        /** Adds a line to the key log shown in the Pen Keyboard app. */
        fun record(context: Context, line: String) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
            val lines = (listOf("$time  $line") + (prefs.getString(LOG, "") ?: "").lines().filter { it.isNotBlank() }).take(20)
            prefs.edit().putString(LOG, lines.joinToString("\n")).apply()
        }

        /** The latest keys seen, newest first. */
        fun log(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LOG, null).orEmpty()
    }
}
