package io.github.kabellosan.penkeyboard

import android.app.Activity
import android.content.pm.PackageManager
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
 * Pad Launch: write an app name ("Tele") and that
 * app opens, or draw one of your recorded gestures (Pen shortcuts) to run it.
 * Has its own home-screen icon so a Boox button can be mapped to open it.
 *
 * Uses the keyboard's InkView (Onyx fast ink) and Recognizer (da + en).
 */
class LauncherActivity : Activity(), InkView.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var recognizer: Recognizer
    private lateinit var matcher: AppMatcher
    private lateinit var shortcuts: Shortcuts
    private lateinit var status: TextView
    private lateinit var ink: InkView
    private lateinit var choices: LinearLayout
    private lateinit var diag: TextView
    private val diagRunnable = object : Runnable {
        override fun run() {
            diag.text = ink.diagnostics()
            main.postDelayed(this, 500)
        }
    }

    private val pauseMs = 700L
    private val recognizeRunnable = Runnable { recognize() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recognizer = Recognizer()
        recognizer.downloadAll()
        matcher = AppMatcher(this)
        requestAppListPermission()
        shortcuts = Shortcuts(this)

        val dp = resources.displayMetrics.density
        status = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.BLACK)
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad / 2)
        }
        ink = InkView(this, this)
        choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Temporary: pen diagnostics for the missing-ink bug.
        diag = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            val pad = (16 * dp).toInt()
            setPadding(pad, 0, pad, 0)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(diag, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
        main.post(diagRunnable)
    }

    override fun onPause() {
        main.removeCallbacks(recognizeRunnable)
        main.removeCallbacks(diagRunnable)
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
    override fun onErase(path: Stroke, durationMs: Long) {
        main.removeCallbacks(recognizeRunnable)
        if (ink.strokes.isEmpty()) { reset(); return }
        ink.eraseAlong(path)
        if (ink.strokes.isNotEmpty()) main.postDelayed(recognizeRunnable, pauseMs)
    }

    /**
     * A recorded gesture that opens an app wins. Otherwise the ink is read in
     * both languages and all guesses are matched against app names.
     */
    private fun recognize() {
        val strokes = ink.strokes.toList()
        if (strokes.isEmpty()) return
        shortcuts.matchGesture(strokes)?.first?.action?.app?.let { app ->
            startActivity(Action.launchIntent(app))
            finish()
            return
        }
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

    /**
     * Some Android skins (and, it seems, Boox) hide other apps unless the user
     * allows "read installed apps" (com.android.permission.GET_INSTALLED_APPS).
     * Ask for it when the system knows that permission and we don't have it yet.
     */
    private fun requestAppListPermission() {
        val known = try {
            packageManager.getPermissionInfo(GET_INSTALLED_APPS, 0); true
        } catch (e: PackageManager.NameNotFoundException) { false }
        if (known && checkSelfPermission(GET_INSTALLED_APPS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(GET_INSTALLED_APPS), REQUEST_APP_LIST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_APP_LIST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            matcher = AppMatcher(this)
        }
    }

    private fun launch(app: AppEntry) {
        try {
            startActivity(Action.launchIntent(app.component))
            finish()
        } catch (e: Exception) {
            // Typically a frozen app: Android won't start a disabled one.
            choices.removeAllViews()
            status.text = "Couldn't open ${app.label}" + if (app.frozen) ": it's frozen in Boox, unfreeze it there" else ""
        }
    }

    private fun reset() {
        main.removeCallbacks(recognizeRunnable)
        ink.clear()
        choices.removeAllViews()
        status.text = "Write an app name or draw a gesture"
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

    companion object {
        private const val GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS"
        private const val REQUEST_APP_LIST = 1
    }
}
