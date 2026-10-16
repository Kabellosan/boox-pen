package io.github.kabellosan.penkeyboard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Probe: does the pen's side button send anything the whole system can see?
 *
 * Apps only get pen strokes inside their own windows, so button-held drawing
 * can't work everywhere. But an accessibility service sees hardware key events
 * from every app. If the Go 7's button sends one, a button click anywhere could
 * open the quick pad. This service only records which keys arrive (never what
 * is typed on screen) and passes them all on untouched.
 */
class PenButtonProbe : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val action = if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"
        record(this, "${KeyEvent.keyCodeToString(event.keyCode)} $action (device ${event.deviceId}, source ${event.source})")
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
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
