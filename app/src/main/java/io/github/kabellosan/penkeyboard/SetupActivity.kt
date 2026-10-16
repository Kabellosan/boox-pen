package io.github.kabellosan.penkeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Launcher screen: turn the keyboard on, pick it, fetch the models, try it out. */
class SetupActivity : Activity() {

    private lateinit var status: TextView
    private val recognizer = Recognizer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Pen Keyboard"
            textSize = 28f
        })
        root.addView(button("1. Turn on Pen Keyboard") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        root.addView(button("2. Switch to Pen Keyboard") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        root.addView(button("3. Download Danish + English models") { download() })
        status = TextView(this).apply { textSize = 18f }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "\nTry it here:"
            textSize = 18f
        })
        root.addView(EditText(this).apply { hint = "Write with the pen…" })
        root.addView(TextView(this).apply {
            text = "\nPen test: touch the box with the tip and with the button held. " +
                "Then hover just above it and click the button: the counter shows if clicks get through."
            textSize = 18f
        })
        root.addView(penTestBox(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (160 * resources.displayMetrics.density).toInt()))

        setContentView(root)
        refreshStatus()
    }

    override fun onDestroy() {
        recognizer.close()
        super.onDestroy()
    }

    private fun download() {
        status.text = "Downloading models…"
        recognizer.downloadAll()
            .addOnSuccessListener { refreshStatus() }
            .addOnFailureListener { status.text = "Download failed: ${it.message}" }
    }

    private fun refreshStatus() {
        val lines = HashMap<String, String>()
        for (lang in recognizer.languages) {
            recognizer.isDownloaded(lang).addOnCompleteListener { t ->
                lines[lang] = "$lang: " + if (t.isSuccessful && t.result == true) "ready" else "not downloaded"
                if (lines.size == recognizer.languages.size) {
                    status.text = recognizer.languages.joinToString("\n") { lines.getValue(it) }
                }
            }
        }
    }

    /** Shows what Android reports for the pen: tool type and buttons, touching or hovering. */
    private fun penTestBox(): View {
        var lastGesture = "none yet"
        var refresh: () -> Unit = {}
        val button = StylusButton { lastGesture = it.name.lowercase().replace('_', ' '); refresh() }
        val box = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            text = "(nothing yet)"
            setBackgroundResource(android.R.drawable.edit_text)
        }
        val report = { v: View, e: MotionEvent ->
            val tool = when (e.getToolType(0)) {
                MotionEvent.TOOL_TYPE_STYLUS -> "pen tip"
                MotionEvent.TOOL_TYPE_ERASER -> "ERASER end"
                MotionEvent.TOOL_TYPE_FINGER -> "finger"
                else -> "tool ${e.getToolType(0)}"
            }
            val buttons = buildList {
                if (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) add("side button 1")
                if (e.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) add("side button 2")
                if (e.buttonState and MotionEvent.BUTTON_SECONDARY != 0) add("secondary")
            }.ifEmpty { listOf("no buttons") }
            button.feed(e)
            val line = "${MotionEvent.actionToString(e.actionMasked)}\n$tool · ${buttons.joinToString()}" +
                "\nraw buttonState=${e.buttonState}"
            refresh = { (v as TextView).text = "$line\nbutton gesture: $lastGesture" }
            refresh()
            true
        }
        box.setOnTouchListener { v, e -> report(v, e) }
        box.setOnGenericMotionListener { v, e -> report(v, e) }
        box.setOnHoverListener { v, e -> report(v, e) }
        return box
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}
