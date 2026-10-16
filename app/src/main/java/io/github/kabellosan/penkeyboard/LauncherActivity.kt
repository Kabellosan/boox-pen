package io.github.kabellosan.penkeyboard

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout

/**
 * Pen launcher: write an app name ("Tele") and that app opens.
 * Has its own home-screen icon so a Boox button can be mapped to open it.
 *
 * Uses the keyboard's InkView (Onyx fast ink) and Recognizer (da + en).
 */
class LauncherActivity : Activity(), InkView.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var recognizer: Recognizer
    private lateinit var matcher: AppMatcher
    private lateinit var status: TextView
    private lateinit var ink: InkView
    private lateinit var choices: LinearLayout

    private val pauseMs = 700L
    private val recognizeRunnable = Runnable { recognize() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recognizer = Recognizer()
        recognizer.downloadAll()
        matcher = AppMatcher(packageManager, packageName)

        val dp = resources.displayMetrics.density
        status = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.BLACK)
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad / 2)
        }
        ink = InkView(this, this)
        choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ink, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(choices, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(button("Clear") { reset() }, LinearLayout.LayoutParams(WRAP_CONTENT, (52 * dp).toInt()).apply {
                gravity = Gravity.END
            })
        })
    }

    override fun onResume() {
        super.onResume()
        reset()
        ink.doOnLayout { ink.startPen() }
    }

    override fun onPause() {
        main.removeCallbacks(recognizeRunnable)
        ink.stopPen()
        super.onPause()
    }

    override fun onDestroy() {
        recognizer.close()
        super.onDestroy()
    }

    // ── Pen events ──────────────────────────────────────────────────

    override fun onStrokeStart() {
        main.removeCallbacks(recognizeRunnable)
    }

    override fun onStrokeEnd() {
        main.removeCallbacks(recognizeRunnable)
        main.postDelayed(recognizeRunnable, pauseMs)
    }

    /** Eraser rubs out the strokes it touches; over an empty pad it starts over. */
    override fun onErase(path: Stroke) {
        main.removeCallbacks(recognizeRunnable)
        if (ink.strokes.isEmpty()) { reset(); return }
        ink.eraseAlong(path)
        if (ink.strokes.isNotEmpty()) main.postDelayed(recognizeRunnable, pauseMs)
    }

    /** Reads the ink in both languages, then matches all guesses against app names. */
    private fun recognize() {
        val strokes = ink.strokes.toList()
        if (strokes.isEmpty()) return
        val w = ink.width.toFloat()
        val h = ink.height.toFloat()
        val results = HashMap<String, List<String>>()
        for (lang in recognizer.languages) {
            recognizer.recognize(lang, strokes, w, h, "") { candidates ->
                results[lang] = candidates
                if (results.size == recognizer.languages.size) showMatches(results)
            }
        }
    }

    private fun showMatches(results: Map<String, List<String>>) {
        // Interleave the languages' guesses, best first: en1, da1, en2, da2…
        val lists = listOf("en", "da").mapNotNull { results[it] }
        val texts = (0 until (lists.maxOfOrNull { it.size } ?: 0))
            .flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
            .distinct()
        ink.clear()
        choices.removeAllViews()

        val read = texts.firstOrNull().orEmpty()
        val matches = matcher.match(texts)
        if (matches.isEmpty()) {
            status.text = if (read.isEmpty()) "Couldn't read that, try again" else "\"$read\": no app found"
            return
        }
        // Open straight away when exactly one app's name starts with what was written.
        val prefixHits = matches.filter { it.second >= AppMatcher.PREFIX }.map { it.first }.distinct()
        if (prefixHits.size == 1) {
            launch(prefixHits[0])
            return
        }
        status.text = "\"$read\": pick one"
        val dp = resources.displayMetrics.density
        for ((app, _) in matches.take(4)) {
            choices.addView(button(app.label) { launch(app) },
                LinearLayout.LayoutParams(MATCH_PARENT, (56 * dp).toInt()))
        }
    }

    private fun launch(app: AppEntry) {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
        finish()
    }

    private fun reset() {
        main.removeCallbacks(recognizeRunnable)
        ink.clear()
        choices.removeAllViews()
        status.text = "Write an app name"
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(Color.BLACK)
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, 0, pad, 0)
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener { onClick() }
    }
}
