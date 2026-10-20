package io.github.kabellosan.penkeyboard

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bare-bones pen keyboard: a row of keys, a row of candidates, a boxed writing strip.
 *
 * Write a word, lift the pen, and after a short pause it is recognized in Danish
 * and English. The best guess in the active language is committed with a space.
 * Tapping another candidate replaces the word just committed.
 *
 * Full screen turns the whole screen above the keys into a see-through writing
 * area over the app, like the Boox keyboard's, with the keys, delete, the cursor
 * slide and the button shortcuts all still working. A finger tap goes back.
 */
class PenIME : InputMethodService(), InkView.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var recognizer: Recognizer
    private lateinit var ink: InkView
    private lateinit var candidateRow: LinearLayout
    private lateinit var langKey: TextView
    private lateinit var enterKey: TextView
    private lateinit var fullKey: TextView
    /** The keys and guesses: on top of the box, or along the bottom in full screen. */
    private lateinit var bar: LinearLayout

    /** The whole screen is the writing area, the app showing through it. */
    private var fullScreen = false

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
     * Page-button presses arrive even before the keyboard has ever been shown,
     * when its views don't exist yet.
     */
    private val hasView get() = ::ink.isInitialized

    /** Page buttons pressed on their own: counted, then run as a [ButtonPress]. */
    private var pressButton: PageButton? = null
    private var pressCount = 0
    // Boox sends each page-button press only on release (down and up 1-3 ms apart),
    // sometimes 0.7 s after the previous one, and drops one of two very fast presses.
    private val pressGapMs = 900L
    /** How far back from a page button's release tip taps count as done while holding it. */
    private val pageTapWindowMs = 1500L
    private val finishPress = Runnable {
        val button = pressButton ?: return@Runnable
        val double = pressCount >= 2
        pressButton = null
        pressCount = 0
        runPress(press(button, double))
    }

    private fun runPress(press: ButtonPress) {
        // A key press must never take the app down (the helper service shares its process).
        val action = shortcuts.action(press)
        try {
            perform(action)
            val helper = if (PenButtonProbe.instance != null) "helper on" else "helper off"
            PenButtonProbe.record(this, "Keyboard: ${press.label} → ${action.label(packageManager)} ($helper)")
        } catch (e: Exception) {
            Log.w(PenApp.TAG, "Page button action failed: ${e.message}")
            PenButtonProbe.record(this, "Keyboard: ${press.label} → ${action.label(packageManager)} failed: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        recognizer = Recognizer()
        recognizer.downloadAll()
        shortcuts = Shortcuts(this)
        activeLang = Shortcuts.language(this)
        buttonGestures = ButtonGestures(10 * resources.displayMetrics.density,
            { trigger -> perform(shortcuts.action(trigger)) },
            ::onShape,
            ::erase)
    }

    override fun onDestroy() {
        shown = false
        instance = null
        recognizer.close()
        super.onDestroy()
    }

    // The writing strip must never take over the whole screen in landscape.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val dp = resources.displayMetrics.density
        // See-through, so the app shows wherever the bar and the box aren't.
        val root = FrameLayout(this)
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // Keys on top, under a black rule: a hard edge between the app and the
        // keyboard. Then the guesses, right above the boxed writing area.
        bar.addView(View(this).apply { setBackgroundColor(Color.BLACK) },
            LinearLayout.LayoutParams(MATCH_PARENT, (2 * dp).toInt()))

        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        langKey = key(activeLang.uppercase()) { toggleLanguage() }
        keys.addView(langKey, weight(1f))
        keys.addView(key("⌨") { switchKeyboard() }, weight(1f))
        fullKey = key("Full") { setFullScreen(!fullScreen) }
        keys.addView(fullKey, weight(1.2f))
        // Comma on a tap, period on a long press: no need to write the tiny marks.
        val commaKey = key(",") { commitMark(",") }
        commaKey.textSize = 28f
        commaKey.setOnLongClickListener { commitMark("."); true }
        keys.addView(commaKey, weight(1f))
        keys.addView(key("space") { flushThen { commit(" ", remember = false) } }, weight(1.6f))
        keys.addView(deleteKey(), weight(2.5f))
        enterKey = key("⏎") { flushThen { enter() } }
        enterKey.setOnLongClickListener { flushThen { commit("\n", remember = false) }; true }
        keys.addView(enterKey, weight(1.5f))
        bar.addView(keys, LinearLayout.LayoutParams(MATCH_PARENT, (52 * dp).toInt()))

        candidateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(candidateRow)
        }, LinearLayout.LayoutParams(MATCH_PARENT, (44 * dp).toInt()))

        ink = InkView(this, this)
        root.addView(ink)
        root.addView(bar)
        fullScreen = false
        layOut()
        return root
    }

    /** Box under the keys, or the whole screen above them. */
    private fun layOut() {
        val dp = resources.displayMetrics.density
        val barHeight = (2 * dp).toInt() + (52 * dp).toInt() + (44 * dp).toInt()
        ink.transparent = fullScreen
        ink.onFingerTap = if (fullScreen) ({ setFullScreen(false) }) else null
        ink.layoutParams = if (fullScreen) {
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { bottomMargin = barHeight }
        } else {
            FrameLayout.LayoutParams(MATCH_PARENT, (280 * dp).toInt()).apply { topMargin = barHeight }
        }
        bar.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT,
            if (fullScreen) Gravity.BOTTOM else Gravity.TOP)
        fullKey.text = if (fullScreen) "Box" else "Full"
        ink.invalidate()
    }

    /** Switches full-screen writing on or off, typing any waiting ink first. */
    private fun setFullScreen(on: Boolean) {
        if (!hasView || !isInputViewShown || on == fullScreen) return
        flushThen {
            fullScreen = on
            layOut()
            // Raw drawing keeps the old place on screen until it is opened again;
            // the resize does that too, this is for when the window moves late.
            ink.postDelayed({ if (isInputViewShown) ink.startPen(force = true) }, 300)
        }
    }

    /**
     * In full screen the window covers the app. Tell Android the app only has to
     * make room for the bar, and that every touch on the window is ours.
     */
    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        if (!fullScreen || !hasView || !isInputViewShown) return
        val loc = IntArray(2).also { bar.getLocationInWindow(it) }
        outInsets.contentTopInsets = loc[1]
        outInsets.visibleTopInsets = loc[1]
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_FRAME
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lastCommitted = null
        activeLang = Shortcuts.language(this)
        langKey.text = activeLang.uppercase()
        showCandidates(emptyMap())
        enterKey.text = if (sendsAction(info)) "Send" else "⏎"
        ink.post { ink.startPen() }
        shown = true
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        shown = false
        main.removeCallbacks(commitRunnable)
        main.removeCallbacks(repeatDelete)
        ink.stopPen()
        ink.clear()
        // Next time, in whatever app, the keyboard opens as the box again.
        if (fullScreen) { fullScreen = false; layOut() }
        super.onFinishInputView(finishingInput)
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

    /** A line break, without the space we put after the last word. */
    private fun newLine() {
        val ic = currentInputConnection
        if (lastCommitted?.endsWith(" ") == true && ic?.getTextBeforeCursor(1, 0)?.toString() == " ") {
            ic?.deleteSurroundingText(1, 0)
        }
        commit("\n", remember = false)
    }

    /**
     * Page buttons, when a shortcut uses them (else they do what Boox set them to).
     * Boox sends a press only when the button is let go, down and up together.
     * After two or three tip taps a press is a [PageTap]; otherwise it is swallowed
     * (so the volume doesn't change) and counted toward a press or double press.
     * Apps that turn pages with the buttons are set to Page turning in Boox, which
     * never sends us their presses, so they are left alone either way.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val button = pageButton(keyCode)?.takeIf(shortcuts::usesPageButton) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount > 0) return true
        PenButtonProbe.record(this, "Keyboard got ${KeyEvent.keyCodeToString(keyCode)}" +
            if (isInputViewShown) " (keyboard open)" else " (keyboard hidden)")
        // A press is still being counted: wait for this one's release before deciding,
        // so the gap is measured from release to the next press, not release to release.
        main.removeCallbacks(finishPress)
        if (pressButton != null && pressButton != button) finishPress.run()
        takenKey = keyCode
        countOnUp = !pageTaps(button)
        if (isInputViewShown) flushThen {}
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val button = pageButton(keyCode)
        if (button == null || keyCode != takenKey) return super.onKeyUp(keyCode, event)
        takenKey = 0
        if (countOnUp) {
            PenButtonProbe.record(this, "  up, press ${pressCount + 1}")
            countPress(button)
        }
        return true
    }

    /** The page key whose down we swallowed; its up is ours too. */
    private var takenKey = 0
    /** That press counts toward a press or double press when it comes up. */
    private var countOnUp = false

    private fun press(button: PageButton, double: Boolean) = when (button) {
        PageButton.UP -> if (double) ButtonPress.UP_DOUBLE else ButtonPress.UP_PRESS
        PageButton.DOWN -> if (double) ButtonPress.DOWN_DOUBLE else ButtonPress.DOWN_PRESS
    }

    /**
     * Page button held while the tip tapped the screen two or three times (seen by
     * the helper anywhere outside the strip). True if that was it and it ran.
     */
    private fun pageTaps(button: PageButton): Boolean {
        val taps = PenButtonProbe.instance?.takeTipTaps(SystemClock.uptimeMillis(), pageTapWindowMs) ?: return false
        if (taps < 2) return false
        val up = button == PageButton.UP
        val tap = when {
            taps >= 3 -> if (up) PageTap.UP_TRIPLE else PageTap.DOWN_TRIPLE
            else -> if (up) PageTap.UP_DOUBLE else PageTap.DOWN_DOUBLE
        }
        val action = shortcuts.action(tap)
        // Nothing set for these taps: they were just taps, and this a plain press.
        if (action == Action.NONE) return false
        // These taps were the shortcut, not part of a press count.
        pressButton = null
        pressCount = 0
        try {
            perform(action)
            PenButtonProbe.record(this, "Keyboard: ${tap.label} → ${action.label(packageManager)}")
        } catch (e: Exception) {
            PenButtonProbe.record(this, "Keyboard: ${tap.label} → ${action.label(packageManager)} failed: ${e.message}")
        }
        return true
    }

    private fun countPress(button: PageButton) {
        main.removeCallbacks(finishPress)
        pressButton = button
        pressCount++
        main.postDelayed(finishPress, pressGapMs)
    }

    /** The Go 7's page buttons, as Boox sends them (volume keys) or as page keys. */
    private fun pageButton(keyCode: Int): PageButton? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP -> PageButton.UP
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> PageButton.DOWN
        else -> null
    }

    /**
     * Button held down. Over waiting ink a sweep rubs out what it touches; taps,
     * presses, and anything drawn on an empty strip are shortcuts.
     */
    /**
     * Button held: tap, then press again and slide sideways to move the cursor,
     * one character per [slideStepPx], to fix a typo without leaving the strip.
     */
    private var sliding = false
    private var slideX = 0f
    private val slideStepPx get() = 12 * resources.displayMetrics.density

    override fun onEraseStart() {
        if (!buttonGestures.takeTap()) return
        sliding = true
        slideX = Float.NaN
        main.removeCallbacks(commitRunnable)
        if (ink.strokes.isNotEmpty()) recognizeAndCommit()
    }

    override fun onEraseMove(path: Stroke) {
        if (!sliding || path.size == 0) return
        if (slideX.isNaN()) slideX = path.xs[0]
        val steps = ((path.xs[path.size - 1] - slideX) / slideStepPx).toInt()
        if (steps == 0) return
        slideX += steps * slideStepPx
        lastCommitted = null
        val key = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(key) }
    }

    override fun onErase(path: Stroke, durationMs: Long) {
        if (sliding) {
            // Firmware that sends no live moves gets the whole slide at the end.
            onEraseMove(path)
            sliding = false
            return
        }
        // A straight line up or down (Send, new line), even over waiting ink.
        val line = if (buttonGestures.pending) null else shortcuts.line(listOf(path))
        if (line != null) {
            val action = shortcuts.action(line)
            PenButtonProbe.record(this, "Keyboard: ${line.label} → ${action.label(packageManager)}")
            perform(action)
            return
        }
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
    private fun onShape(strokes: List<Stroke>) {
        val match = shortcuts.match(strokes, packageManager)
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
            Action.SEND -> flushThen { send() }
            Action.LANG -> toggleLanguage()
            Action.FULL_SCREEN -> setFullScreen(!fullScreen)
            Action.NEW_LINE -> flushThen { newLine() }
            Action.COMMA -> commitMark(",")
            Action.PERIOD -> commitMark(".")
            Action.QUICK_PAD -> openLauncher()
            Action.BACK -> flushThen { goBack() }
            Action.PEN_PAD -> flushThen {
                startActivity(Intent(this, PenPadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Action.DELETE_WORD -> {
                main.removeCallbacks(commitRunnable)
                if (hasView && ink.strokes.isNotEmpty()) ink.clear() else deleteLastWord()
            }
            Action.HIDE_KEYBOARD -> flushThen { requestHideSelf(0) }
            Action.SHOW_KEYBOARD -> showKeyboard()
            Action.QUICK_NOTE -> flushThen { BooxTools.openQuickNote(this) }
            in Action.FIELD -> flushThen { fieldAction(action) }
            in Action.GLOBAL -> flushThen {
                if (PenButtonProbe.instance?.globalAction(action) != true) {
                    if (action == Action.HOME) startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else showMessage("Turn on the Pen Keyboard helper for that")
                }
            }
            else -> if (BooxTools.isTool(action)) flushThen { BooxTools.run(this, action) }
                else action.app?.let { app -> flushThen { startActivity(Action.launchIntent(app)) } }
        }
        // Button taps pause the commit timer; ink still waiting gets read as usual.
        if (hasView && ink.strokes.isNotEmpty()) {
            main.removeCallbacks(commitRunnable)
            main.postDelayed(commitRunnable, commitDelayMs)
        }
    }

    /**
     * Test: can the keyboard open where no text field is focused? Asks Android to
     * show it and writes to the key log what it was attached to and what happened.
     */
    private fun showKeyboard() {
        if (isInputViewShown) { PenButtonProbe.record(this, "Show keyboard: already open"); return }
        val info = currentInputEditorInfo
        val field = when {
            !currentInputStarted || info == null -> "no field"
            info.inputType == 0 -> "no text field (${info.packageName})"
            else -> "text field in ${info.packageName}"
        }
        val window = PenButtonProbe.instance?.rootInActiveWindow?.packageName ?: "?"
        PenButtonProbe.record(this, "Show keyboard: asking, on $window, $field")
        requestShowSelf(0)
        main.postDelayed({
            val result = when {
                !isInputViewShown -> "did NOT open"
                shown -> "opened, pen on"
                else -> "opened without starting input, pen off"
            }
            PenButtonProbe.record(this, "Show keyboard: $result")
            // Opened with nothing to type into: still turn the pen on, to see if ink works.
            if (isInputViewShown && !shown && hasView) {
                ink.post { ink.startPen() }
                PenButtonProbe.record(this, "Show keyboard: pen turned on anyway")
            }
        }, 800)
    }

    /** Undo, cursor moves and the clipboard, done by the app's own text field. */
    private fun fieldAction(action: Action) {
        val ic = currentInputConnection ?: return
        lastCommitted = null
        when (action) {
            Action.UNDO -> ic.performContextMenuAction(android.R.id.undo)
            Action.SELECT_ALL -> ic.performContextMenuAction(android.R.id.selectAll)
            Action.COPY -> ic.performContextMenuAction(android.R.id.copy)
            Action.PASTE -> ic.performContextMenuAction(android.R.id.paste)
            Action.CURSOR_START -> ic.setSelection(0, 0)
            Action.CURSOR_END -> {
                val length = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.length
                if (length != null) ic.setSelection(length, length)
                else sendKeyChord(KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
            }
        }
    }

    private fun sendKeyChord(code: Int, meta: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
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
                    val read = results[activeLang]?.firstOrNull()
                        ?: results.values.firstNotNullOfOrNull { it.firstOrNull() }
                    // A lone tick, dot or slash is a comma or period, not whatever ML Kit guessed.
                    val best = Punctuation.pick(strokes, wordHeight, read)
                    // Short reads go in the key log, so a misread mark can be traced.
                    if (read != null && read.trim().length <= 2) {
                        val codes = read.map { "U+%04X".format(it.code) }.joinToString(" ")
                        PenButtonProbe.record(this, "Ink: ${strokes.size} stroke(s), read \"$read\" ($codes), typed \"$best\"")
                    }
                    if (best != null) commitWord(best, pre)
                    if (best == read && read != null && read.count { it.isLetter() } >= 2) learnWordHeight(strokes)
                    showCandidates(results, Punctuation.markChoices(best), best)
                    then?.invoke()
                }
            }
        }
    }

    /**
     * How tall the user writes a word, in strip pixels, so a comma can be told by
     * being much smaller. Follows the last few words.
     */
    private var wordHeight = 0f
        get() = if (field > 0f) field else 50 * resources.displayMetrics.density

    private fun learnWordHeight(strokes: List<Stroke>) {
        val dp = resources.displayMetrics.density
        val h = Punctuation.height(strokes).coerceIn(20 * dp, 200 * dp)
        wordHeight = 0.7f * wordHeight + 0.3f * h
    }

    /** If ink is waiting, turn it into text first, then run [action]. */
    private fun flushThen(action: () -> Unit) {
        main.removeCallbacks(commitRunnable)
        if (!hasView || ink.strokes.isEmpty()) action() else recognizeAndCommit(action)
    }

    // ── Text output ─────────────────────────────────────────────────

    /**
     * Commits a recognized word with a trailing space, adding a leading one if needed.
     * Punctuation sticks to the previous word: the space after it goes first.
     */
    private fun commitWord(word: String, pre: String) {
        val (drop, text) = Punctuation.place(pre, word)
        if (text.isEmpty()) return
        if (drop > 0) currentInputConnection?.deleteSurroundingText(drop, 0)
        commit(text, remember = true)
    }

    /** A comma or period from the key or a shortcut, after any waiting ink. */
    private fun commitMark(mark: String) = flushThen {
        commitWord(mark, currentInputConnection?.getTextBeforeCursor(20, 0)?.toString() ?: "")
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
            commitWord(word, ic.getTextBeforeCursor(20, 0)?.toString() ?: "")
        } else {
            commit("$word ", remember = true)
        }
    }

    /** Takes out the space between the word just written and the word before it. */
    private fun joinLast() {
        val ic = currentInputConnection ?: return
        val last = lastCommitted ?: return
        val before = ic.getTextBeforeCursor(last.length + 8, 0)?.toString() ?: return
        val (drop, text) = Punctuation.join(before, last) ?: return
        ic.deleteSurroundingText(drop, 0)
        commit(text, remember = true)
    }

    private fun backspace() {
        main.removeCallbacks(commitRunnable)
        if (ink.strokes.isNotEmpty()) { ink.clear(); return }
        lastCommitted = null
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    /**
     * Held delete: after a pause it repeats slowly, then switches to whole words.
     * E-ink shows each change late, so a fast character repeat would overshoot
     * before you see it; a few word steps are easy to stop in time.
     */
    private var deleteHeldAt = 0L
    private val repeatDelete: Runnable = object : Runnable {
        override fun run() {
            val held = SystemClock.uptimeMillis() - deleteHeldAt
            if (held < 1800) {
                lastCommitted = null
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                main.postDelayed(this, 180)
            } else {
                deleteLastWord()
                main.postDelayed(this, 450)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun deleteKey() = key("⌫") {}.apply {
        textSize = 28f
        setOnClickListener(null)
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    val hadInk = ink.strokes.isNotEmpty()
                    backspace()
                    if (!hadInk) {
                        deleteHeldAt = SystemClock.uptimeMillis()
                        main.postDelayed(repeatDelete, 500)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    main.removeCallbacks(repeatDelete)
                }
            }
            true
        }
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

    /**
     * Sends: the field's own Send action, or else (chat apps like Telegram, whose
     * field only makes new lines) the app's Send button, pressed by the helper.
     * A plain Enter only when neither is there.
     */
    private fun send() {
        val info = currentInputEditorInfo
        if (info == null || sendsAction(info)) { enter(); return }
        lastCommitted = null
        if (PenButtonProbe.instance?.clickSend(info.packageName) == true) return
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
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

    /**
     * Shows [marks] (a lone small mark's readings) first, then the active language's
     * guesses, then the other language's. The one typed, [best], is bold.
     */
    private fun showCandidates(results: Map<String, List<String>>, marks: List<String> = emptyList(), best: String? = null) {
        candidateRow.removeAllViews()
        val ordered = listOf(activeLang) + recognizer.languages.filter { it != activeLang }
        val seen = HashSet<String>()
        // Letters written to finish a misread word: one tap glues them onto it.
        if (marks.isEmpty() && best?.firstOrNull()?.isLetter() == true) {
            candidateRow.addView(key(Punctuation.JOIN) { joinLast() }, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        for (m in marks) {
            if (!seen.add(m)) continue
            candidateRow.addView(key(m, bold = m == best) { replaceLast(m) }.apply { textSize = 28f },
                LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        for (lang in ordered) {
            for (c in (results[lang] ?: emptyList()).take(4)) {
                if (!seen.add(c)) continue
                val label = if (lang == activeLang) c else "$c·${lang}"
                candidateRow.addView(key(label, bold = lang == activeLang && c == best) { replaceLast(c) },
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

    companion object {
        /** True while the writing strip is on screen; eraser taps on it are its own. */
        var shown = false
            private set

        /** The running keyboard, so the helper can close it before pressing Back. */
        var instance: PenIME? = null
            private set

        /** Full screen on or off, for a shortcut run by the helper. */
        fun toggleFullScreen() = instance?.let { it.setFullScreen(!it.fullScreen) }

        /** A comma or period for a shortcut run by the helper, while the keyboard is open. */
        fun typeMark(mark: String) = instance?.commitMark(mark)

        /** A text-field action for a shortcut run by the helper, while the keyboard is open. */
        fun runFieldAction(action: Action) = instance?.fieldAction(action)

        /** Show the keyboard for a shortcut run by the helper. */
        fun showSelf(context: Context) {
            val ime = instance
            if (ime == null) PenButtonProbe.record(context, "Show keyboard: Pen Keyboard isn't running (not the chosen keyboard?)")
            else ime.showKeyboard()
        }
    }
}
