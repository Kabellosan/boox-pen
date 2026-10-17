package io.github.kabellosan.penkeyboard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pen Keyboard helper, an accessibility service (off until switched on):
 * - lets Pen Pad write into the text field you were using in another app;
 * - lets the Go back shortcut press Android's real Back button;
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
 */
class PenButtonProbe : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // A held key repeats its down event; one line per press is enough.
        if (event.repeatCount > 0) return false
        val action = if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"
        record(this, "${KeyEvent.keyCodeToString(event.keyCode)} $action (device ${event.deviceId}, source ${event.source})")
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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

    /** Presses the field's Send/Done/Enter action, if it has one. */
    fun pressEnter(): Boolean {
        val n = target ?: return false
        if (!n.refresh() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
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

        private const val PREFS = "pen_button_probe"
        private const val LOG = "log"

        private fun record(context: Context, line: String) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
            val lines = (listOf("$time  $line") + (prefs.getString(LOG, "") ?: "").lines().filter { it.isNotBlank() }).take(12)
            prefs.edit().putString(LOG, lines.joinToString("\n")).apply()
        }

        /** The latest keys seen, newest first. */
        fun log(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LOG, null).orEmpty()
    }
}
