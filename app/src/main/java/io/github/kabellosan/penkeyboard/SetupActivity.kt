package io.github.kabellosan.penkeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}
