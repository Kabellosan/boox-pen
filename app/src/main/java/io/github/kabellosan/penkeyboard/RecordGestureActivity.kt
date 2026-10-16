package io.github.kabellosan.penkeyboard

import android.app.Activity
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
 * Records a new gesture: draw the shape a few times (tip or button, either works),
 * then choose what it does. More samples make it match more reliably.
 */
class RecordGestureActivity : Activity(), InkView.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var shortcuts: Shortcuts
    private lateinit var status: TextView
    private lateinit var ink: InkView
    private lateinit var doneButton: TextView
    private val samples = ArrayList<List<Stroke>>()

    /** Strokes drawn with the button held, kept alongside the tip's. */
    private val buttonStrokes = ArrayList<Stroke>()

    private val pauseMs = 800L
    private val takeRunnable = Runnable { takeSample() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shortcuts = Shortcuts(this)
        val dp = resources.displayMetrics.density
        status = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.BLACK)
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad / 2)
        }
        ink = InkView(this, this)
        doneButton = button("Next: choose what it does") { chooseAction() }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(button("Undo last") { undo() })
            addView(doneButton)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ink, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, (56 * dp).toInt()))
        })
        showStatus(null)
    }

    override fun onResume() {
        super.onResume()
        ink.doOnLayout { ink.startPen() }
    }

    override fun onPause() {
        main.removeCallbacks(takeRunnable)
        ink.stopPen()
        super.onPause()
    }

    override fun onStrokeStart() {
        main.removeCallbacks(takeRunnable)
    }

    override fun onStrokeEnd() {
        main.removeCallbacks(takeRunnable)
        main.postDelayed(takeRunnable, pauseMs)
    }

    override fun onErase(path: Stroke, durationMs: Long) {
        buttonStrokes += path
        main.removeCallbacks(takeRunnable)
        main.postDelayed(takeRunnable, pauseMs)
    }

    /** The pen rested: what was drawn becomes one sample. */
    private fun takeSample() {
        val strokes = ink.strokes.toList() + buttonStrokes
        ink.clear()
        buttonStrokes.clear()
        val cloud = PointCloud.of(strokes)
        if (cloud == null) {
            showStatus("That was just a dot, draw the shape.")
            return
        }
        // How close this drawing is to the earlier samples, and to other gestures.
        val toOwn = samples.mapNotNull { PointCloud.of(it) }.minOfOrNull { PointCloud.distance(cloud, it) }
        val clash = shortcuts.rank(strokes).firstOrNull()
        samples += strokes
        val notes = buildList {
            if (toOwn != null) add(
                if (toOwn <= PointCloud.MATCH_MAX) "Matches your earlier ones (%.2f).".format(toOwn)
                else "Quite different from your earlier ones (%.2f); Undo if that was a slip.".format(toOwn)
            )
            if (clash != null && clash.second <= PointCloud.MATCH_MAX) add(
                "Looks like your gesture for \"${clash.first.action.label(packageManager)}\" (%.2f). A more different shape works better."
                    .format(clash.second)
            )
        }
        showStatus(notes.joinToString(" ").ifEmpty { null })
    }

    private fun undo() {
        main.removeCallbacks(takeRunnable)
        if (ink.strokes.isNotEmpty() || buttonStrokes.isNotEmpty()) {
            ink.clear()
            buttonStrokes.clear()
        } else if (samples.isNotEmpty()) {
            samples.removeAt(samples.size - 1)
        }
        showStatus(null)
    }

    private fun chooseAction() {
        if (samples.isEmpty()) return
        // The pen overlay would draw over the dialog.
        ink.stopPen()
        ShortcutsActivity.pickAction(this, Action.NONE, onCancel = { ink.startPen() }) { action ->
            val g = Gesture(System.currentTimeMillis().toString(), samples.toList(), action)
            shortcuts.saveGestures(shortcuts.gestures() + g)
            finish()
        }
    }

    private fun showStatus(note: String?) {
        val head = when (samples.size) {
            0 -> "Draw your gesture, then lift the pen."
            1 -> "Got it once. Draw it once or twice more so it's recognized reliably."
            else -> "Got it ${samples.size} times."
        }
        status.text = if (note == null) head else "$head\n$note"
        doneButton.isEnabled = samples.isNotEmpty()
        doneButton.alpha = if (samples.isEmpty()) 0.4f else 1f
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.BLACK)
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, 0, pad, 0)
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener { onClick() }
    }
}
