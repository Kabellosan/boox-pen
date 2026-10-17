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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout

/**
 * Pen Pad: a see-through writing layer over whatever app is open. Map a smart
 * button to it, write anywhere on the screen, and each word goes into the text
 * field you were using, through the Pen Keyboard helper. Button-held taps and
 * shapes work here as on the keyboard. Done closes it.
 */
class PenPadActivity : Activity(), InkView.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var recognizer: Recognizer
    private lateinit var shortcuts: Shortcuts
    private lateinit var buttonGestures: ButtonGestures
    private lateinit var ink: InkView
    private lateinit var status: TextView
    private lateinit var candidateRow: LinearLayout
    private lateinit var langKey: TextView

    private var activeLang = "da"

    /** The text we inserted last, so a candidate tap can replace it. */
    private var lastInserted: String? = null

    private val commitDelayMs = 700L
    private val commitRunnable = Runnable { recognizeAndInsert() }

    private val helper get() = PenButtonProbe.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recognizer = Recognizer()
        recognizer.downloadAll()
        shortcuts = Shortcuts(this)
        buttonGestures = ButtonGestures(10 * resources.displayMetrics.density,
            { trigger -> perform(shortcuts.action(trigger)) },
            ::onShape,
            ::erase)
        activeLang = Shortcuts.language(this)

        val dp = resources.displayMetrics.density
        status = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            val pad = (12 * dp).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        candidateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ink = InkView(this, this).apply { transparent = true }
        val keys = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
        }
        langKey = key(activeLang.uppercase()) { toggleLanguage() }
        keys.addView(langKey, weight(1f))
        keys.addView(key("space") { flushThen { insert(" ") } }, weight(2f))
        keys.addView(key("⌫") { backspace() }, weight(1f))
        keys.addView(key("Send") { flushThen { send() } }, weight(1.5f))
        keys.addView(key("Done") { flushThen { finish() } }, weight(1.5f))

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(Color.WHITE)
                addView(candidateRow)
            }, LinearLayout.LayoutParams(MATCH_PARENT, (44 * dp).toInt()))
            addView(ink, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(keys, LinearLayout.LayoutParams(MATCH_PARENT, (52 * dp).toInt()))
        })
    }

    override fun onResume() {
        super.onResume()
        lastInserted = null
        showTarget()
        ink.doOnLayout { ink.startPen() }
    }

    override fun onPause() {
        flushThen {}
        ink.stopPen()
        super.onPause()
    }

    override fun onDestroy() {
        recognizer.close()
        super.onDestroy()
    }

    private fun showTarget() {
        val h = helper
        status.text = when {
            h == null -> "Switch on Pen Keyboard helper (Pen Keyboard app → Open Accessibility settings) to write into apps."
            else -> h.targetLabel()?.let { "Writing into $it" } ?: "Tap a text field in the app first, then open Pen Pad."
        }
    }

    // ── Pen events ──────────────────────────────────────────────────

    override fun onStrokeStart() {
        buttonGestures.flush()
        main.removeCallbacks(commitRunnable)
    }

    override fun onStrokeEnd() {
        main.removeCallbacks(commitRunnable)
        main.postDelayed(commitRunnable, commitDelayMs)
    }

    /** Button held: over waiting ink a sweep rubs it out; otherwise taps and shapes are shortcuts. */
    override fun onErase(path: Stroke, durationMs: Long) {
        if (ink.strokes.isNotEmpty() && !buttonGestures.pending && !buttonGestures.isStill(path)) {
            erase(path)
            return
        }
        main.removeCallbacks(commitRunnable)
        buttonGestures.feed(path, durationMs)
    }

    private fun erase(path: Stroke) {
        main.removeCallbacks(commitRunnable)
        if (ink.strokes.isEmpty()) return
        ink.eraseAlong(path)
        if (ink.strokes.isNotEmpty()) main.postDelayed(commitRunnable, commitDelayMs)
    }

    private fun onShape(strokes: List<Stroke>) {
        val match = shortcuts.match(strokes, packageManager)
        if (match == null) status.text = "No shortcut for that shape"
        perform(match?.action ?: Action.NONE)
    }

    private fun perform(action: Action) {
        when (action) {
            Action.NONE -> {}
            Action.SEND -> flushThen { send() }
            Action.LANG -> toggleLanguage()
            Action.QUICK_PAD -> flushThen {
                startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
            Action.DELETE_WORD -> {
                main.removeCallbacks(commitRunnable)
                if (ink.strokes.isNotEmpty()) ink.clear() else deleteLastWord()
            }
            Action.BACK -> flushThen { finish() }
            else -> action.app?.let { app ->
                flushThen {
                    startActivity(Action.launchIntent(app))
                    finish()
                }
            }
        }
        if (ink.strokes.isNotEmpty()) {
            main.removeCallbacks(commitRunnable)
            main.postDelayed(commitRunnable, commitDelayMs)
        }
    }

    // ── Recognition and text ────────────────────────────────────────

    private fun recognizeAndInsert(then: (() -> Unit)? = null) {
        val strokes = ink.strokes.toList()
        if (strokes.isEmpty()) { then?.invoke(); return }
        val pre = helper?.textBeforeCursor(20).orEmpty()
        val w = ink.width.toFloat()
        val h = ink.height.toFloat()
        ink.clear()

        val results = HashMap<String, List<String>>()
        for (lang in recognizer.languages) {
            recognizer.recognize(lang, strokes, w, h, pre) { candidates ->
                results[lang] = candidates
                if (results.size == recognizer.languages.size) {
                    val best = results[activeLang]?.firstOrNull()
                        ?: results.values.firstNotNullOfOrNull { it.firstOrNull() }
                    if (best != null) insertWord(best, pre)
                    showCandidates(results)
                    then?.invoke()
                }
            }
        }
    }

    private fun flushThen(action: () -> Unit) {
        main.removeCallbacks(commitRunnable)
        if (ink.strokes.isEmpty()) action() else recognizeAndInsert(action)
    }

    /** Same spacing as the keyboard: a space after each word, punctuation sticks to the word before. */
    private fun insertWord(word: String, pre: String) {
        val needsLead = pre.isNotEmpty() && !pre.last().isWhitespace() && !word.first().isPunct()
        val dropOwnSpace = word.first().isPunct() && lastInserted?.endsWith(" ") == true && pre.endsWith(" ")
        edit(if (dropOwnSpace) 1 else 0, (if (needsLead) " " else "") + word + " ", remember = true)
    }

    private fun insert(text: String) = edit(0, text, remember = false)

    private fun edit(deleteBefore: Int, text: String, remember: Boolean) {
        val h = helper
        if (h == null || !h.edit(deleteBefore, text)) {
            status.text = "Couldn't write into the app. Read: ${text.trim()}"
            lastInserted = null
            return
        }
        lastInserted = if (remember) text else null
        showTarget()
    }

    /** Replaces the last inserted word with [word] (a tapped candidate). */
    private fun replaceLast(word: String) {
        val last = lastInserted
        if (last != null && helper?.textBeforeCursor(last.length) == last) {
            val lead = if (last.startsWith(" ")) " " else ""
            edit(last.length, "$lead$word ", remember = true)
        } else {
            edit(0, "$word ", remember = true)
        }
    }

    private fun backspace() {
        main.removeCallbacks(commitRunnable)
        if (ink.strokes.isNotEmpty()) { ink.clear(); return }
        lastInserted = null
        helper?.edit(1, "")
    }

    private fun deleteLastWord() {
        lastInserted = null
        val before = helper?.textBeforeCursor(64) ?: return
        val word = Regex("\\S+\\s*$").find(before)?.value ?: before.takeLastWhile { it.isWhitespace() }
        if (word.isNotEmpty()) helper?.edit(word.length, "")
    }

    /** Presses the field's Send; if the app doesn't offer one, closes the pad so you can tap it. */
    private fun send() {
        lastInserted = null
        if (helper?.pressEnter() != true) {
            status.text = "This field has no Send action; tap the app's send button."
            return
        }
        finish()
    }

    private fun toggleLanguage() {
        activeLang = if (activeLang == "da") "en" else "da"
        Shortcuts.setLanguage(this, activeLang)
        langKey.text = activeLang.uppercase()
    }

    // ── UI helpers ──────────────────────────────────────────────────

    private fun showCandidates(results: Map<String, List<String>>) {
        candidateRow.removeAllViews()
        val ordered = listOf(activeLang) + recognizer.languages.filter { it != activeLang }
        val seen = HashSet<String>()
        for (lang in ordered) {
            for (c in (results[lang] ?: emptyList()).take(4)) {
                if (!seen.add(c)) continue
                val label = if (lang == activeLang) c else "$c·$lang"
                candidateRow.addView(key(label) { replaceLast(c) }, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            }
        }
    }

    private fun key(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.BLACK)
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad, 0, pad, 0)
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener { onClick() }
    }

    private fun weight(w: Float) = LinearLayout.LayoutParams(0, MATCH_PARENT, w)

    private fun Char.isPunct() = this in ".,!?;:)"
}
