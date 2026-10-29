package io.github.kabellosan.penkeyboard

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
 *
 * Summoned where there is no text field, it opens in command mode: write an app
 * name or an action ("back", "home"…) and it runs.
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
    /** Keys and guesses: stacked in portrait, side by side in one row in landscape. */
    private lateinit var keyRows: LinearLayout
    private lateinit var keys: LinearLayout
    private lateinit var guesses: HorizontalScrollView

    /**
     * Turning the screen restarts most apps, which closes the keyboard. What was
     * open is kept here and brought back when the keyboard reopens after a turn.
     */
    private var wasFullScreen = false
    private var wasCommandMode = false
    private var restorePending = false
    private var orientation = Configuration.ORIENTATION_UNDEFINED
    private var turnedAt = 0L
    private var hiddenAt = 0L
    /** A turn and the keyboard closing this close together count as one. */
    private val turnGraceMs = 2000L
    /** The app whose field the keyboard was typing into when it closed. */
    private var turnPackage: String? = null
    private var reopenTries = 0
    private val reopen = Runnable { reopenAfterTurn() }
    private val forgetTurn = Runnable { restorePending = false }

    /** The whole screen is the writing area, the app showing through it. */
    private var fullScreen = false

    /**
     * Command mode: summoned with no text field (or asked for), so a written word
     * opens an app or runs an action instead of being typed. See [Commands].
     */
    private var commandMode = false
    /** Set when we ask to be shown: null = not summoned, else command mode wanted even with a field. */
    private var summoned: Boolean? = null
    private var apps: AppMatcher? = null

    /** Language whose best guess is committed automatically. */
    private var activeLang = "da"

    /** Pause after the last stroke before the ink is turned into text. */
    private val commitDelayMs = 700L

    /** The text we committed last, so a candidate tap can replace it. */
    private var lastCommitted: String? = null

    private val commitRunnable = Runnable { recognizeAndCommit() }

    /** Guesses for words already in the field: their own handwriting guesses, and spellings. */
    private lateinit var words: WordMemory
    private lateinit var spell: SpellCheck
    /** The word or selection the guesses are about now; null while they are for fresh ink. */
    private var target: FieldText? = null
    private var targetAsk = 0
    private val lookAtCursor = Runnable { suggestForCursor() }

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
    // When a single press turns the page, a shorter wait for a second press, so
    // page turns don't lag (doubles came about 0.2 s apart in the key log). Set
    // in Pen shortcuts.
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
        words = WordMemory(this)
        spell = SpellCheck(this, recognizer.languages)
        shortcuts = Shortcuts(this)
        orientation = resources.configuration.orientation
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
        spell.close()
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

        keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
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
        enterKey = key("⏎") { if (commandMode) requestHideSelf(0) else flushThen { enter() } }
        enterKey.setOnLongClickListener { flushThen { commit("\n", remember = false) }; true }
        keys.addView(enterKey, weight(1.5f))

        candidateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        guesses = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(candidateRow)
        }
        keyRows = LinearLayout(this)
        bar.addView(keyRows, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        ink = InkView(this, this)
        root.addView(ink)
        root.addView(bar)
        fullScreen = false
        layOut()
        root.post { restoreAfterTurn() }
        return root
    }

    /**
     * The screen turned. Android throws the keyboard's views away and builds new
     * ones, and most apps restart, which closes the keyboard (the two can come in
     * either order). Type any waiting ink and shut the old ink layer down (left
     * open, its raw drawing kept the old place on screen), reopen the keyboard if
     * it closed, and bring back full screen and command mode.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        val turned = newConfig.orientation != orientation
        orientation = newConfig.orientation
        val open = hasView && isInputViewShown
        if (turned && open) {
            main.removeCallbacks(commitRunnable)
            if (!commandMode && ink.strokes.isNotEmpty()) recognizeAndCommit() else ink.clear()
            remember(fullScreen, commandMode)
            ink.stopPen()
        }
        super.onConfigurationChanged(newConfig)
        if (!turned) return
        turnedAt = SystemClock.uptimeMillis()
        if (open) {
            main.post { restoreAfterTurn() }
        } else if (turnedAt - hiddenAt < turnGraceMs) {
            remember(wasFullScreen, wasCommandMode)
            reopenSoon()
        }
    }

    private fun remember(full: Boolean, commands: Boolean) {
        wasFullScreen = full
        wasCommandMode = commands
        restorePending = true
        main.removeCallbacks(forgetTurn)
        main.postDelayed(forgetTurn, 5000)
    }

    /** After the app has rebuilt its screen. */
    private fun reopenSoon() {
        reopenTries = 0
        main.removeCallbacks(reopen)
        main.postDelayed(reopen, 700)
    }

    /**
     * Apps rebuilt for a turn rarely put the cursor back in their field, so the
     * helper taps into it and the app opens the keyboard as usual. Command mode
     * needs no field. With no field found (yet), try again, then stay closed.
     */
    private fun reopenAfterTurn() {
        if (isInputViewShown) return
        if (wasCommandMode) { showKeyboard(true); return }
        val tapped = PenButtonProbe.instance?.refocusField(turnPackage) == true
        if (tapped) {
            PenButtonProbe.record(this, "Screen turned: tapped back into the text field")
            main.postDelayed({ if (!isInputViewShown && hasField()) requestShowSelf(0) }, 400)
        } else if (++reopenTries < 3) {
            main.postDelayed(reopen, 500)
        } else {
            val why = if (PenButtonProbe.instance == null) "helper off" else "no text field found"
            PenButtonProbe.record(this, "Screen turned: keyboard not reopened ($why)")
        }
    }

    private fun restoreAfterTurn() {
        if (!restorePending || !hasView || !isInputViewShown) return
        restorePending = false
        // The new views show the box's keys; command mode needs its Close key back.
        if (wasCommandMode) setCommandMode(true)
        if (wasFullScreen != fullScreen) {
            fullScreen = wasFullScreen
            layOut()
        }
        ink.postDelayed({ if (isInputViewShown) ink.startPen(force = true) }, 300)
    }

    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * Box under the keys, or the whole screen above them. In landscape the screen
     * is wide and short: the guesses sit beside the keys in one row and the box is
     * lower and wider, so the app keeps more than half the screen.
     */
    private fun layOut() {
        val dp = resources.displayMetrics.density
        val rowHeight = (52 * dp).toInt()
        val guessHeight = (44 * dp).toInt()
        keyRows.removeAllViews()
        val barHeight = if (landscape) {
            keyRows.orientation = LinearLayout.HORIZONTAL
            keyRows.addView(guesses, LinearLayout.LayoutParams(0, rowHeight, 4f))
            keyRows.addView(keys, LinearLayout.LayoutParams(0, rowHeight, 6f))
            (2 * dp).toInt() + rowHeight
        } else {
            keyRows.orientation = LinearLayout.VERTICAL
            keyRows.addView(keys, LinearLayout.LayoutParams(MATCH_PARENT, rowHeight))
            keyRows.addView(guesses, LinearLayout.LayoutParams(MATCH_PARENT, guessHeight))
            (2 * dp).toInt() + rowHeight + guessHeight
        }
        val boxHeight = if (landscape) {
            (resources.displayMetrics.heightPixels * 0.4f).toInt().coerceIn((150 * dp).toInt(), (280 * dp).toInt())
        } else {
            (280 * dp).toInt()
        }
        ink.transparent = fullScreen
        ink.onFingerTap = if (fullScreen) ({ setFullScreen(false) }) else null
        ink.layoutParams = if (fullScreen) {
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { bottomMargin = barHeight }
        } else {
            FrameLayout.LayoutParams(MATCH_PARENT, boxHeight).apply { topMargin = barHeight }
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
        if (commandMode && info.inputType != EditorInfo.TYPE_NULL && summoned == null) setCommandMode(false)
        lastCommitted = null
        activeLang = Shortcuts.language(this)
        langKey.text = activeLang.uppercase()
        showCandidates(emptyMap())
        enterKey.text = if (sendsAction(info)) "Send" else "⏎"
        ink.post { ink.startPen() }
        shown = true
        main.post { restoreAfterTurn() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        shown = false
        main.removeCallbacks(commitRunnable)
        main.removeCallbacks(repeatDelete)
        ink.stopPen()
        ink.clear()
        if (!restorePending) wasFullScreen = fullScreen
        // Next time, in whatever app, the keyboard opens as the box again.
        if (fullScreen) { fullScreen = false; layOut() }
        super.onFinishInputView(finishingInput)
    }

    /**
     * After we asked to be shown: with no text field to type into (or when asked
     * for), the keyboard opens in command mode. Without a field Android shows the
     * window but never starts input, so the pen is turned on here.
     */
    override fun onWindowShown() {
        super.onWindowShown()
        val wantCommands = summoned ?: return
        summoned = null
        val field = hasField()
        PenButtonProbe.record(this, "Show keyboard: opened" + if (field) ", text field" else ", no text field")
        if (!hasView) return
        if (wantCommands || !field) setCommandMode(true)
        if (!shown) {
            ink.post { ink.startPen() }
            shown = true
        }
        main.post { restoreAfterTurn() }
    }

    override fun onWindowHidden() {
        summoned = null
        if (!restorePending) wasCommandMode = commandMode
        turnPackage = currentInputEditorInfo?.packageName
        hiddenAt = SystemClock.uptimeMillis()
        // Closed by the app restarting for a screen turn: open again.
        if (hiddenAt - turnedAt < turnGraceMs) {
            remember(wasFullScreen, wasCommandMode)
            reopenSoon()
        }
        if (commandMode) setCommandMode(false)
        if (shown && hasView) {
            ink.stopPen()
            ink.clear()
        }
        shown = false
        super.onWindowHidden()
    }

    /** A field the keyboard can type into is focused. */
    private fun hasField(): Boolean =
        currentInputStarted && (currentInputEditorInfo?.inputType ?: EditorInfo.TYPE_NULL) != EditorInfo.TYPE_NULL

    private fun setCommandMode(on: Boolean) {
        commandMode = on
        if (!hasView) return
        if (on) {
            main.removeCallbacks(commitRunnable)
            ink.clear()
            // The app list takes a moment: after the keyboard is on screen.
            main.post { if (commandMode && apps == null) apps = AppMatcher(this) }
            enterKey.text = "Close"
            showMessage("Write an app or a command")
        } else {
            apps = null
            enterKey.text = currentInputEditorInfo?.let { if (sendsAction(it)) "Send" else "⏎" } ?: "⏎"
            showCandidates(emptyMap())
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
     * A single press set to turn the page waits only briefly for a second press,
     * then sends the app a page key (see [turnPage]).
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
        val turns = shortcuts.action(press(button, double = false)) in Action.PAGE_TURNS
        main.postDelayed(finishPress, if (turns) Shortcuts.turnWaitMs(this) else pressGapMs)
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

    /** Runs a shortcut's action; in command mode most close the keyboard first. */
    private fun perform(action: Action) {
        if (commandMode && action !in keyboardActions) runCommand(Command(action.label(packageManager), action))
        else performNow(action)
    }

    /** Actions about the keyboard itself, run in place even in command mode. */
    private val keyboardActions = setOf(Action.NONE, Action.LANG, Action.FULL_SCREEN, Action.DELETE_WORD,
        Action.QUICK_PAD, Action.SHOW_KEYBOARD, Action.HIDE_KEYBOARD)

    private fun performNow(action: Action) {
        when (action) {
            Action.NONE -> {}
            Action.SEND -> flushThen { send() }
            Action.LANG -> toggleLanguage()
            Action.FULL_SCREEN -> setFullScreen(!fullScreen)
            Action.NEW_LINE -> flushThen { newLine() }
            Action.COMMA -> commitMark(",")
            Action.PERIOD -> commitMark(".")
            Action.QUICK_PAD -> showKeyboard(commands = true)
            Action.BACK -> flushThen { goBack() }
            Action.PEN_PAD -> flushThen {
                startActivity(Intent(this, PenPadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Action.DELETE_WORD -> {
                main.removeCallbacks(commitRunnable)
                if (hasView && ink.strokes.isNotEmpty()) ink.clear() else deleteLastWord()
            }
            Action.HIDE_KEYBOARD -> flushThen { requestHideSelf(0) }
            Action.SHOW_KEYBOARD -> showKeyboard(commands = false)
            Action.QUICK_NOTE -> flushThen { BooxTools.openQuickNote(this) }
            in Action.PAGE_TURNS -> turnPage(action)
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
     * Opens the keyboard wherever you are, even with no text field (Android shows
     * it; [onWindowShown] takes over). [commands]: command mode even over a field.
     */
    private fun showKeyboard(commands: Boolean) {
        if (isInputViewShown) {
            if (commands && !commandMode) flushThen { setCommandMode(true) }
            return
        }
        summoned = commands
        val window = PenButtonProbe.instance?.rootInActiveWindow?.packageName ?: "?"
        PenButtonProbe.record(this, "Show keyboard: asking, on $window")
        requestShowSelf(0)
    }

    /** Command mode: read the ink, then open the app or run the action it names. */
    private fun recognizeCommand() {
        val strokes = ink.strokes.toList()
        if (strokes.isEmpty()) return
        // A recorded gesture drawn with the tip, as Pad Launch did.
        shortcuts.matchGesture(strokes)?.first?.action?.let { ink.clear(); runCommand(Command(it.label(packageManager), it)); return }
        val matcher = apps ?: AppMatcher(this).also { apps = it }
        val w = ink.width.toFloat()
        val h = ink.height.toFloat()
        ink.clear()
        val results = HashMap<String, List<String>>()
        for (lang in recognizer.languages) {
            recognizer.recognize(lang, strokes, w, h, "") { candidates ->
                results[lang] = candidates
                if (results.size == recognizer.languages.size && commandMode) showCommands(results, matcher)
            }
        }
    }

    private fun showCommands(results: Map<String, List<String>>, matcher: AppMatcher) {
        // Interleave the languages' guesses, best first, the active language leading.
        val lists = (listOf(activeLang) + recognizer.languages.filter { it != activeLang }).mapNotNull { results[it] }
        val texts = (0 until (lists.maxOfOrNull { it.size } ?: 0))
            .flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
            .distinct()
        val read = texts.firstOrNull().orEmpty()
        val matches = Commands.match(texts, matcher, packageManager)
        PenButtonProbe.record(this, "Command: read \"$read\", " +
            (matches.firstOrNull()?.let { "best ${it.first.label} (${it.second})" } ?: "nothing matched"))
        if (matches.isEmpty()) {
            showMessage(if (read.isEmpty()) "Couldn't read that, try again" else "\"$read\": no app or command")
            return
        }
        // Run straight away when exactly one thing's name starts with what was written.
        val sure = matches.filter { it.second >= AppMatcher.PREFIX }.map { it.first }.distinctBy { it.action }
        if (sure.size == 1) { runCommand(sure[0]); return }
        candidateRow.removeAllViews()
        for ((command, _) in matches.distinctBy { it.first.action }.take(5)) {
            candidateRow.addView(key(command.label) { runCommand(command) },
                LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
    }

    /**
     * Opens the app (the keyboard then closes with the app it was over), or closes
     * the keyboard and runs the action, so Back or Home act on the app, not on us.
     */
    private fun runCommand(command: Command) {
        PenButtonProbe.record(this, "Command: ${command.label}")
        val app = command.app ?: command.action.app?.let { AppEntry(command.label, it) }
        if (app != null) {
            val helper = PenButtonProbe.instance
            if (FrozenApps.isFrozen(packageManager, app.component.packageName)) {
                if (helper == null) { showMessage("${app.label} is frozen in Boox: turn on the helper to open it from here"); return }
                requestHideSelf(0)
                helper.unfreezeAndOpen(app.component, app.label)
                return
            }
            try {
                startActivity(Action.launchIntent(app.component))
                requestHideSelf(0)
            } catch (e: Exception) {
                showMessage("Couldn't open ${app.label}")
            }
            return
        }
        if (command.action == Action.BACK) { goBack(); return }
        requestHideSelf(0)
        main.postDelayed({ performNow(command.action) }, 150)
    }

    /**
     * Sends the app a page key through the keyboard's connection to it, as Boox
     * would have; with no connection the helper swipes the page instead.
     */
    private fun turnPage(action: Action) {
        val code = if (action == Action.NEXT_PAGE) KeyEvent.KEYCODE_PAGE_DOWN else KeyEvent.KEYCODE_PAGE_UP
        val ic = currentInputConnection
        val now = SystemClock.uptimeMillis()
        val sent = ic != null && ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0)) &&
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        if (sent) { PenButtonProbe.record(this, "  page key sent to the app"); return }
        val swiped = PenButtonProbe.instance?.globalAction(action) == true
        PenButtonProbe.record(this, if (swiped) "  no connection, helper swiped" else "  couldn't turn the page")
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
        if (commandMode) { recognizeCommand(); then?.invoke(); return }
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
                    if (best != null && best == read && best.any { it.isLetter() }) words.remember(results)
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

    // ── Guesses for text already in the field ───────────────────────

    /** The cursor moved or text was selected: once it settles, offer guesses for what it touches. */
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!hasView || !shown || commandMode) return
        main.removeCallbacks(lookAtCursor)
        main.postDelayed(lookAtCursor, 300)
    }

    /** What the cursor touches or what is selected, if anything. */
    private fun findTarget(): FieldText? {
        val ic = currentInputConnection ?: return null
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) return if (selected.length <= 300) FieldText.ofSelection(selected) else null
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return null
        val after = ic.getTextAfterCursor(48, 0)?.toString() ?: ""
        return FieldText.atCursor(before, after)
    }

    private fun suggestForCursor() {
        if (!hasView || !shown || commandMode || ink.strokes.isNotEmpty()) return
        val ic = currentInputConnection ?: return
        // Still right after the word just written: its own guesses stay.
        val last = lastCommitted
        if (target == null && last != null && ic.getSelectedText(0).isNullOrEmpty() &&
            ic.getTextBeforeCursor(last.length, 0)?.toString() == last) return
        val t = findTarget()
        if (t == target) return
        target = t
        val ask = ++targetAsk
        if (t == null) { showCandidates(emptyMap()); return }
        // Handwriting guesses kept from when it was written come first, then spellings.
        val remembered = words.lookup(t.core)
        val spelled = HashMap<String, List<String>>()
        for (lang in recognizer.languages) {
            spell.ask(lang, t.core) { found ->
                if (ask != targetAsk || commandMode || ink.strokes.isNotEmpty()) return@ask
                spelled[lang] = found
                if (spelled.size == recognizer.languages.size) showTargetGuesses(t, remembered, spelled)
            }
        }
    }

    /** The target in grey, then its guesses: remembered, spelled, other capitals. */
    private fun showTargetGuesses(t: FieldText, remembered: Map<String, List<String>>, spelled: Map<String, List<String>>) {
        candidateRow.removeAllViews()
        val label = if (t.core.length > 18) t.core.take(16) + "…" else t.core
        candidateRow.addView(key("$label ›") {}.apply { setTextColor(Color.GRAY); isClickable = false },
            LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        val ordered = listOf(activeLang) + recognizer.languages.filter { it != activeLang }
        val seen = hashSetOf(t.core)
        fun add(c: String, lang: String?) {
            if (!seen.add(c)) return
            val text = if (lang == null || lang == activeLang) c else "$c·$lang"
            candidateRow.addView(key(text) { replaceTarget(t, c) }, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        for (lang in ordered) remembered[lang].orEmpty().take(4).forEach { add(it, lang) }
        for (lang in ordered) spelled[lang].orEmpty().take(4).forEach { add(it, lang) }
        FieldText.caseVariants(t.core).forEach { add(it, null) }
        if (candidateRow.childCount == 1) {
            candidateRow.addView(key("no guesses") {}.apply { setTextColor(Color.GRAY); isClickable = false },
                LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
    }

    /** Puts [word] in place of the target, keeping the marks around it. */
    private fun replaceTarget(t: FieldText, word: String) {
        val ic = currentInputConnection ?: return
        // The text moved on since the guesses were shown: show guesses for what is there now.
        if (findTarget() != t) { target = null; suggestForCursor(); return }
        lastCommitted = null
        ic.beginBatchEdit()
        if (!t.selection) ic.deleteSurroundingText(t.before, t.after)
        ic.commitText(t.prefix + word + t.suffix, 1)
        ic.endBatchEdit()
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
        target = null
        targetAsk++
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
        target = null
        targetAsk++
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

        /**
         * Shows the keyboard for a shortcut run by the helper; [commands] opens it in
         * command mode. False if Pen Keyboard isn't the keyboard in use.
         */
        fun showSelf(context: Context, commands: Boolean): Boolean {
            val ime = instance
            if (ime == null) PenButtonProbe.record(context, "Show keyboard: Pen Keyboard isn't running (not the chosen keyboard?)")
            ime?.showKeyboard(commands)
            return ime != null
        }
    }
}
