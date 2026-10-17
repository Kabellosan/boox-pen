package io.github.kabellosan.penkeyboard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
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

    /** Side button held + taps, a press, or a drawn shape: actions set in Pen shortcuts. */
    private lateinit var buttonGestures: ButtonGestures
    private lateinit var shortcuts: Shortcuts

    /**
     * Page Up/Down held + tip strokes: shortcuts of that page button's layer.
     * Boox sends the page buttons as volume keys when an app's buttons are set
     * to Volume. The ink shows while drawing and is wiped once it is read.
     */
    private lateinit var pageGestures: ButtonGestures

    /**
     * Page-button presses arrive even before the keyboard has ever been shown,
     * when its views don't exist yet.
     */
    private val hasView get() = ::ink.isInitialized
    private var heldLayer: Layer? = null
    private var strokeLayer: Layer? = null
    private var pageLayer = Layer.PAGE_UP
    private var strokeStartedAt = 0L

    /** Page buttons pressed on their own: counted, then run as a [ButtonPress]. */
    private var keyDownAt = 0L
    private var strokeDuringHold = false
    private var pressLayer: Layer? = null
    private var pressCount = 0
    private val pressGapMs = 350L
    private val finishPress = Runnable {
        val up = pressLayer == Layer.PAGE_UP
        val press = when {
            pressCount >= 2 -> if (up) ButtonPress.UP_DOUBLE else ButtonPress.DOWN_DOUBLE
            else -> if (up) ButtonPress.UP_PRESS else ButtonPress.DOWN_PRESS
        }
        pressLayer = null
        pressCount = 0
        // A key press must never take the app down (the helper service shares its process).
        val action = shortcuts.action(press)
        try {
            perform(action)
            PenButtonProbe.record(this, "Keyboard: ${press.label} → ${action.label(packageManager)}")
        } catch (e: Exception) {
            Log.w(PenApp.TAG, "Page button action failed: ${e.message}")
            PenButtonProbe.record(this, "Keyboard: ${press.label} → ${action.label(packageManager)} failed: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        recognizer = Recognizer()
        recognizer.downloadAll()
        shortcuts = Shortcuts(this)
        activeLang = Shortcuts.language(this)
        buttonGestures = ButtonGestures(10 * resources.displayMetrics.density,
            { trigger -> perform(shortcuts.action(trigger)) },
            { strokes -> onShape(strokes, Layer.PEN) },
            ::erase)
        pageGestures = ButtonGestures(10 * resources.displayMetrics.density,
            { trigger -> endPageGesture(); perform(shortcuts.action(trigger, pageLayer)) },
            { strokes -> endPageGesture(); onShape(strokes, pageLayer) },
            { endPageGesture() })
    }

    override fun onDestroy() {
        recognizer.close()
        super.onDestroy()
    }

    // The writing strip must never take over the whole screen in landscape.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
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
        langKey = key(activeLang.uppercase()) { toggleLanguage() }
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
        activeLang = Shortcuts.language(this)
        langKey.text = activeLang.uppercase()
        showCandidates(emptyMap())
        enterKey.text = if (sendsAction(info)) "Send" else "⏎"
        ink.post { ink.startPen() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        main.removeCallbacks(commitRunnable)
        heldLayer = null
        ink.stopPen()
        ink.clear()
        super.onFinishInputView(finishingInput)
    }

    // ── Pen events ──────────────────────────────────────────────────

    override fun onStrokeStart() {
        buttonGestures.flush()
        main.removeCallbacks(commitRunnable)
        strokeLayer = heldLayer
        if (strokeLayer != null) {
            strokeStartedAt = SystemClock.uptimeMillis()
            strokeDuringHold = true
        } else {
            pageGestures.flush()
        }
    }

    override fun onStrokeEnd() {
        main.removeCallbacks(commitRunnable)
        val layer = strokeLayer
        if (layer == null) {
            main.postDelayed(commitRunnable, commitDelayMs)
            return
        }
        // A page-button stroke is a shortcut, not writing: take it out of the ink.
        strokeLayer = null
        val stroke = ink.strokes.removeLastOrNull() ?: return
        if (layer != pageLayer) pageGestures.flush()
        pageLayer = layer
        pageGestures.feed(stroke, SystemClock.uptimeMillis() - strokeStartedAt)
    }

    /** Wipes the page-button strokes off the screen once they are read. */
    private fun endPageGesture() {
        if (!ink.drawing) ink.repaint()
    }

    /**
     * Page buttons. Every press is swallowed so the volume doesn't change. With the
     * keyboard open, holding one makes pen strokes its shortcuts; a quick press with
     * no strokes (or any press while the keyboard is hidden) counts toward a
     * press or double press.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val layer = pageButton(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount > 0) return true
        keyDownAt = SystemClock.uptimeMillis()
        strokeDuringHold = false
        if (isInputViewShown) {
            flushThen {}
            heldLayer = layer
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val layer = pageButton(keyCode) ?: return super.onKeyUp(keyCode, event)
        if (heldLayer == layer) heldLayer = null
        val quick = SystemClock.uptimeMillis() - keyDownAt < 500
        if (!strokeDuringHold && quick) countPress(layer)
        return true
    }

    private fun countPress(layer: Layer) {
        main.removeCallbacks(finishPress)
        if (pressLayer != null && pressLayer != layer) finishPress.run()
        pressLayer = layer
        pressCount++
        main.postDelayed(finishPress, pressGapMs)
    }

    /** The Go 7's page buttons, as Boox sends them (volume keys) or as page keys. */
    private fun pageButton(keyCode: Int): Layer? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP -> Layer.PAGE_UP
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> Layer.PAGE_DOWN
        else -> null
    }

    /**
     * Button held down. Over waiting ink a sweep rubs out what it touches; taps,
     * presses, and anything drawn on an empty strip are shortcuts.
     */
    override fun onErase(path: Stroke, durationMs: Long) {
        if (ink.strokes.isNotEmpty() && !buttonGestures.pending && !buttonGestures.isStill(path)) {
            erase(path)
            return
        }
        main.removeCallbacks(commitRunnable)
        buttonGestures.feed(path, durationMs)
    }

    /** Rubs out the waiting strokes that [path] touches. */
    private fun erase(path: Stroke) {
        main.removeCallbacks(commitRunnable)
        if (ink.strokes.isEmpty()) return
        ink.eraseAlong(path)
        if (ink.strokes.isNotEmpty()) main.postDelayed(commitRunnable, commitDelayMs)
    }

    /** A shape drawn with a button held: run the shortcut it matches, if any. */
    private fun onShape(strokes: List<Stroke>, layer: Layer) {
        val match = shortcuts.match(strokes, packageManager, layer)
        if (match == null) {
            showMessage("No shortcut for that shape")
            perform(Action.NONE)
            return
        }
        perform(match.action)
    }

    /** Runs a shortcut's action. */
    private fun perform(action: Action) {
        when (action) {
            Action.NONE -> {}
            Action.SEND -> flushThen { enter() }
            Action.LANG -> toggleLanguage()
            Action.QUICK_PAD -> openLauncher()
            Action.BACK -> flushThen { goBack() }
            Action.PEN_PAD -> flushThen {
                startActivity(Intent(this, PenPadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Action.DELETE_WORD -> {
                main.removeCallbacks(commitRunnable)
                if (hasView && ink.strokes.isNotEmpty()) ink.clear() else deleteLastWord()
            }
            else -> action.app?.let { app -> flushThen { startActivity(Action.launchIntent(app)) } }
        }
        // Button taps pause the commit timer; ink still waiting gets read as usual.
        if (hasView && ink.strokes.isNotEmpty()) {
            main.removeCallbacks(commitRunnable)
            main.postDelayed(commitRunnable, commitDelayMs)
        }
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
        if (!hasView || ink.strokes.isEmpty()) action() else recognizeAndCommit(action)
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
        Shortcuts.setLanguage(this, activeLang)
        if (hasView) langKey.text = activeLang.uppercase()
    }

    private fun openLauncher() {
        flushThen {
            startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Android's Back. The helper service presses the real Back button; the keyboard
     * hides first, or that press would only close the keyboard. Without the helper,
     * a Back key goes to the app, which most apps treat the same.
     */
    private fun goBack() {
        if (PenButtonProbe.instance != null) {
            requestHideSelf(0)
            main.postDelayed({ PenButtonProbe.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }, 150)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_BACK)
        }
    }

    private fun switchKeyboard() {
        if (!switchToNextInputMethod(false)) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    // ── UI helpers ──────────────────────────────────────────────────

    /** Shows a short note in the candidate row. */
    private fun showMessage(text: String) {
        if (!hasView) return
        candidateRow.removeAllViews()
        candidateRow.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.DKGRAY)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
    }

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
