package io.github.kabellosan.penkeyboard

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bare-bones pen keyboard: a writing strip, a row of candidates, a row of keys.
 *
 * Write a word, lift the pen, and after a short pause it is recognized in Danish
 * and English. The best guess in the active language is committed with a space.
 * Tapping another candidate replaces the word just committed.
 */
class PenIME : InputMethodService(), InkView.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var recognizer: Recognizer
    private lateinit var ink: InkView
    private lateinit var candidateRow: LinearLayout
    private lateinit var langKey: TextView
    private lateinit var enterKey: TextView

    /** Language whose best guess is committed automatically. */
    private var activeLang = "da"

    /** Pause after the last stroke before the ink is turned into text. */
    private val commitDelayMs = 700L

    /** The text we committed last, so a candidate tap can replace it. */
    private var lastCommitted: String? = null

    private val commitRunnable = Runnable { recognizeAndCommit() }

    /** Pen side button: double click sends, triple click switches DA/EN, a long hold opens the launcher. */
    private val penButton = StylusButton { gesture ->
        when (gesture) {
            StylusButton.Gesture.DOUBLE_CLICK -> flushThen { enter() }
            StylusButton.Gesture.TRIPLE_CLICK -> toggleLanguage()
            StylusButton.Gesture.HOLD -> openLauncher()
        }
    }

    override fun onCreate() {
        super.onCreate()
        recognizer = Recognizer()
        recognizer.downloadAll()
    }

    override fun onDestroy() {
        recognizer.close()
        super.onDestroy()
    }

    // The writing strip must never take over the whole screen in landscape.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val dp = resources.displayMetrics.density
        // The root sees every pen event over the keyboard, hovering or touching,
        // and passes it to the side-button detector before the normal handling.
        val root = object : LinearLayout(this) {
            override fun dispatchHoverEvent(e: MotionEvent): Boolean {
                penButton.feed(e)
                return super.dispatchHoverEvent(e)
            }
            override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
                penButton.feed(e)
                return super.dispatchGenericMotionEvent(e)
            }
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                penButton.feed(e)
                return super.dispatchTouchEvent(e)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        candidateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(candidateRow)
        }, LinearLayout.LayoutParams(MATCH_PARENT, (44 * dp).toInt()))

        ink = InkView(this, this)
        root.addView(ink, LinearLayout.LayoutParams(MATCH_PARENT, (200 * dp).toInt()))

        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        langKey = key("DA") { toggleLanguage() }
        keys.addView(langKey, weight(1f))
        keys.addView(key("⌨") { switchKeyboard() }, weight(1f))
        keys.addView(key("space") { flushThen { commit(" ", remember = false) } }, weight(3f))
        keys.addView(key("⌫") { backspace() }, weight(1.5f))
        enterKey = key("⏎") { flushThen { enter() } }
        enterKey.setOnLongClickListener { flushThen { commit("\n", remember = false) }; true }
        keys.addView(enterKey, weight(1.5f))
        root.addView(keys, LinearLayout.LayoutParams(MATCH_PARENT, (52 * dp).toInt()))

        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lastCommitted = null
        showCandidates(emptyMap())
        enterKey.text = if (sendsAction(info)) "Send" else "⏎"
        ink.post { ink.startPen() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        main.removeCallbacks(commitRunnable)
        ink.stopPen()
        ink.clear()
        super.onFinishInputView(finishingInput)
    }

    // ── Pen events ──────────────────────────────────────────────────

    override fun onStrokeStart() {
        penButton.penUsed()
        main.removeCallbacks(commitRunnable)
    }

    override fun onStrokeEnd() {
        main.removeCallbacks(commitRunnable)
        main.postDelayed(commitRunnable, commitDelayMs)
    }

    /**
     * Eraser: over waiting ink it rubs out the strokes it touches. Over an empty
     * strip it deletes the word before the cursor, one word per sweep.
     */
    override fun onErase(path: Stroke) {
        penButton.penUsed()
        main.removeCallbacks(commitRunnable)
        if (ink.strokes.isNotEmpty()) {
            ink.eraseAlong(path)
            if (ink.strokes.isNotEmpty()) main.postDelayed(commitRunnable, commitDelayMs)
            return
        }
        deleteLastWord()
    }

    /** Deletes the last word before the cursor, with the spaces after it. */
    private fun deleteLastWord() {
        val ic = currentInputConnection ?: return
        lastCommitted = null
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return
        val word = Regex("\\S+\\s*$").find(before)?.value ?: before.takeLastWhile { it.isWhitespace() }
        if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
    }

        private fun recognizeAndCommit(then: (() -> Unit)? = null) {
        val strokes = ink.strokes.toList()
        if (strokes.isEmpty()) { then?.invoke(); return }
        val pre = currentInputConnection?.getTextBeforeCursor(20, 0)?.toString() ?: ""
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
                    if (best != null) commitWord(best, pre)
                    showCandidates(results)
                    then?.invoke()
                }
            }
        }
    }

    /** If ink is waiting, turn it into text first, then run [action]. */
    private fun flushThen(action: () -> Unit) {
        main.removeCallbacks(commitRunnable)
        if (ink.strokes.isEmpty()) action() else recognizeAndCommit(action)
    }

    // ── Text output ─────────────────────────────────────────────────

    /** Commits a recognized word with a trailing space, adding a leading one if needed. */
    private fun commitWord(word: String, pre: String) {
        val needsLead = pre.isNotEmpty() && !pre.last().isWhitespace() && !word.first().isPunct()
        val lead = if (needsLead) " " else ""
        // Punctuation sticks to the previous word: drop our own trailing space first.
        if (word.first().isPunct() && lastCommitted?.endsWith(" ") == true) {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
        commit("$lead$word ", remember = true)
    }

    private fun commit(text: String, remember: Boolean) {
        currentInputConnection?.commitText(text, 1)
        lastCommitted = if (remember) text else null
    }

    /** Replaces the last committed word with [word] (a tapped candidate). */
    private fun replaceLast(word: String) {
        val ic = currentInputConnection ?: return
        val last = lastCommitted
        if (last != null && ic.getTextBeforeCursor(last.length, 0)?.toString() == last) {
            ic.deleteSurroundingText(last.length, 0)
            val lead = if (last.startsWith(" ")) " " else ""
            commit("$lead$word ", remember = true)
        } else {
            commit("$word ", remember = true)
        }
    }

    private fun backspace() {
        main.removeCallbacks(commitRunnable)
        if (ink.strokes.isNotEmpty()) { ink.clear(); return }
        lastCommitted = null
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun enter() {
        lastCommitted = null
        val info = currentInputEditorInfo
        if (info != null && sendsAction(info)) {
            currentInputConnection?.performEditorAction(info.imeOptions and EditorInfo.IME_MASK_ACTION)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /** True when the field wants an action (Send, Go, Search…) instead of a newline. */
    private fun sendsAction(info: EditorInfo): Boolean {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return false
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        return action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_GO ||
            action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE
    }

    private fun toggleLanguage() {
        activeLang = if (activeLang == "da") "en" else "da"
        langKey.text = activeLang.uppercase()
    }

    private fun openLauncher() {
        flushThen {
            startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun switchKeyboard() {
        if (!switchToNextInputMethod(false)) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    // ── UI helpers ──────────────────────────────────────────────────

    /** Shows the active language's guesses first, then the other language's. */
    private fun showCandidates(results: Map<String, List<String>>) {
        candidateRow.removeAllViews()
        val ordered = listOf(activeLang) + recognizer.languages.filter { it != activeLang }
        val seen = HashSet<String>()
        for (lang in ordered) {
            for ((i, c) in (results[lang] ?: emptyList()).take(4).withIndex()) {
                if (!seen.add(c)) continue
                val label = if (lang == activeLang) c else "$c·${lang}"
                candidateRow.addView(key(label, bold = lang == activeLang && i == 0) { replaceLast(c) },
                    LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            }
        }
    }

    private fun key(label: String, bold: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.BLACK)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad, 0, pad, 0)
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener { onClick() }
    }

    private fun weight(w: Float) = LinearLayout.LayoutParams(0, MATCH_PARENT, w)

    private fun Char.isPunct() = this in ".,!?;:)"
}
