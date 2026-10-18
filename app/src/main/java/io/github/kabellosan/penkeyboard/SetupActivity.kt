package io.github.kabellosan.penkeyboard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Launcher screen: turn the keyboard on, pick it, fetch the models, try it out. */
class SetupActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private lateinit var probeLog: TextView
    private lateinit var testBuilds: LinearLayout
    private var release: Updater.Release? = null
    private var updating = false
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
        updateStatus = TextView(this).apply { textSize = 18f }
        root.addView(updateStatus)
        updateButton = button("Check for update") { onUpdateTapped() }
        root.addView(updateButton)
        testBuilds = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(testBuilds)
        root.addView(button("1. Turn on Pen Keyboard") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        root.addView(button("2. Switch to Pen Keyboard") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        root.addView(button("3. Download Danish + English models") { download() })
        root.addView(TextView(this).apply {
            text = "\n4. Switch on \"Pen Keyboard helper\" in Accessibility. Go back, Pen Pad, apps, " +
                "pen taps on any screen and page-button shortcuts need it. The log below shows what it saw."
            textSize = 18f
        })
        root.addView(TextView(this).apply {
            text = "If Android says \"Restricted setting\": tap App info below, then ⋮ (top right) → " +
                "Allow restricted settings, then try again."
            textSize = 16f
        })
        root.addView(button("App info (to allow restricted settings)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        })
        root.addView(button("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("Pen shortcuts: what the pen and page buttons do") {
            startActivity(Intent(this, ShortcutsActivity::class.java))
        })
        probeLog = TextView(this).apply { textSize = 16f }
        root.addView(probeLog)
        status = TextView(this).apply { textSize = 18f }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "\nTry it here:"
            textSize = 18f
        })
        root.addView(EditText(this).apply { hint = "Write with the pen…" })
        root.addView(TextView(this).apply {
            text = "\nPen test: touch the box with the tip, then hold the button and tap it " +
                "twice, three times, press and hold, or draw a shape (like a T)."
            textSize = 18f
        })
        root.addView(penTestBox(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (160 * resources.displayMetrics.density).toInt()))

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
        checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        val helper = if (PenButtonProbe.instance != null) "Helper: on" else "Helper: off (shortcuts outside the keyboard need it)"
        probeLog.text = helper + "\n" + PenButtonProbe.log(this).ifEmpty { "No keys seen yet." }
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

    private fun checkForUpdate() {
        val installed = Updater.installedBuild(this)
        updateStatus.text = "Build $installed installed. Checking GitHub…"
        background({ Updater.latest() }) { latest, error ->
            release = latest
            when {
                latest == null -> {
                    updateStatus.text = "Build $installed installed. Couldn't check: ${error?.message}"
                    updateButton.text = "Check for update"
                }
                latest.build > installed -> {
                    updateStatus.text = "Build $installed installed. Build ${latest.build} is out" +
                        (if (latest.notes.isNotBlank()) ":\n${latest.notes.lines().first()}" else ".")
                    updateButton.text = "Update to build ${latest.build}"
                }
                else -> {
                    updateStatus.text = "Build $installed installed. Up to date."
                    updateButton.text = "Check for update"
                }
            }
        }
        // Test builds of open PRs, to try a change before it is merged. Only newer
        // ones: Android refuses to install a lower versionCode over a higher one.
        background({ Updater.testBuilds() }) { builds, _ ->
            testBuilds.removeAllViews()
            for (b in builds.orEmpty().filter { it.build > installed }.take(3)) {
                testBuilds.addView(button("Try ${b.title.ifBlank { "test build ${b.build}" }}: ${b.notes}") {
                    install(b)
                })
            }
        }
    }

    private fun onUpdateTapped() {
        val latest = release
        if (latest == null || latest.build <= Updater.installedBuild(this)) return checkForUpdate()
        install(latest)
    }

    private fun install(latest: Updater.Release) {
        if (updating) return
        if (!Updater.canInstall(this)) {
            // Android asks once per app: allow Pen Keyboard to install apps.
            updateStatus.text = "Allow Pen Keyboard to install apps (switch it on), then come back and tap the button again."
            startActivity(Updater.allowInstallsIntent(this))
            return
        }
        updating = true
        updateStatus.text = "Downloading build ${latest.build}…"
        background({
            Updater.download(this, latest) { pct ->
                if (pct >= 0) runOnUiThread { updateStatus.text = "Downloading build ${latest.build}… $pct%" }
            }
        }) { apk, error ->
            updating = false
            if (apk == null) {
                updateStatus.text = "Download failed: ${error?.message}"
            } else {
                updateStatus.text = "Build ${latest.build} downloaded. Tap Install when Android asks."
                startActivity(Updater.installIntent(this, apk))
            }
        }
    }

    /** Runs [work] on a background thread, then [done] on the UI thread if the screen is still open. */
    private fun <T> background(work: () -> T, done: (T?, Throwable?) -> Unit) {
        Thread {
            val result = runCatching(work)
            runOnUiThread {
                if (!isDestroyed) done(result.getOrNull(), result.exceptionOrNull())
            }
        }.start()
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
        val shortcuts = Shortcuts(this)
        val gestures = ButtonGestures(10 * resources.displayMetrics.density,
            { lastGesture = "${it.label} → ${shortcuts.action(it).label(packageManager)}"; refresh() },
            { strokes -> lastGesture = describeShape(shortcuts, strokes); refresh() },
            { lastGesture = "one tap (erases a dot)"; refresh() })
        var path: Stroke? = null
        var downAt = 0L
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
            // Keep the page from scrolling while drawing in the box.
            if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent.requestDisallowInterceptTouchEvent(true)
            if (e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { path = Stroke().apply { add(e.x, e.y, e.eventTime) }; downAt = e.eventTime }
                MotionEvent.ACTION_MOVE -> path?.let { p ->
                    for (h in 0 until e.historySize) p.add(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalEventTime(h))
                    p.add(e.x, e.y, e.eventTime)
                }
                MotionEvent.ACTION_UP -> path?.let { gestures.feed(it, e.eventTime - downAt); path = null }
            }
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

    /** What a shape drawn with the button held was recorded as, and what it matched. */
    private fun describeShape(shortcuts: Shortcuts, strokes: List<Stroke>): String {
        val points = strokes.sumOf { it.size }
        val drawn = "drew ${strokes.size} stroke${if (strokes.size == 1) "" else "s"}, $points points"
        val match = shortcuts.match(strokes, packageManager)
        val closest = shortcuts.rank(strokes).firstOrNull()
        return when {
            match != null -> "$drawn → ${match.label}" + (match.score?.let { " (%.2f)".format(it) } ?: "")
            closest != null -> "$drawn → no match (closest %.2f, needs under %.2f)".format(closest.second, PointCloud.MATCH_MAX)
            else -> "$drawn → no match"
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}
