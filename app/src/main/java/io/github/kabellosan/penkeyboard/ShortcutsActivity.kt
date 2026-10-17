package io.github.kabellosan.penkeyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Pen shortcuts: pick what each button gesture does, and record your own shapes
 * (a T for Telegram, a circle for…) to draw with the button held or in Pad Launch.
 */
class ShortcutsActivity : Activity() {

    private lateinit var shortcuts: Shortcuts
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shortcuts = Shortcuts(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(list)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        list.addView(text("Pen shortcuts", 28f))
        for (layer in Layer.values()) {
            list.addView(text((if (layer == Layer.PEN) "" else "\n") + layer.label, 18f))
            for (t in Trigger.values()) {
                list.addView(row("${t.label}:  ${shortcuts.action(t, layer).label(packageManager)}") {
                    pickAction(this, shortcuts.action(t, layer)) { a -> shortcuts.setAction(t, a, layer); refresh() }
                })
            }
        }
        list.addView(text("\nPage buttons on their own", 18f))
        for (b in ButtonPress.values()) {
            list.addView(row("${b.label}:  ${shortcuts.action(b).label(packageManager)}") {
                pickAction(this, shortcuts.action(b)) { a -> shortcuts.setAction(b, a); refresh() }
            })
        }
        list.addView(text("Page buttons work wherever Pen Keyboard is your keyboard (presses also with " +
            "the keyboard hidden; Pen Pad and apps open only with Pen Keyboard helper on). In Boox settings set " +
            "Customize Buttons → Volume for the apps you write in, and the smart button's long press → None.", 16f))

        list.addView(text("\nHold the pen's button and tap anywhere…", 18f))
        for (t in AnywhereTap.values()) {
            list.addView(row("${t.label}:  ${shortcuts.action(t).label(packageManager)}") {
                pickAction(this, shortcuts.action(t)) { a -> shortcuts.setAction(t, a); refresh() }
            })
        }
        list.addView(text("Works on any screen, with Pen Keyboard helper on. On the keyboard's strip, its own button shortcuts apply instead. " +
            "The app underneath feels the taps too, so tap somewhere empty, like the status bar.", 16f))

        list.addView(text("\nYour gestures", 22f))
        list.addView(text("Draw one with the button held on the keyboard's empty strip, " +
            "with a page button held, or with the tip in Pad Launch.", 16f))
        val gestures = shortcuts.gestures()
        if (gestures.isEmpty()) list.addView(text("(none yet)", 18f))
        for (g in gestures) {
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val dp = resources.displayMetrics.density
                addView(GestureThumb(context, g.samples.first()), LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()))
                addView(row("→  ${g.action.label(packageManager)}") { editGesture(g) },
                    LinearLayout.LayoutParams(0, (56 * dp).toInt(), 1f))
            })
        }
        list.addView(row("+  Add a gesture") {
            startActivity(Intent(this, RecordGestureActivity::class.java))
        })
    }

    private fun editGesture(g: Gesture) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Change what it does", "Delete")) { _, which ->
                val others = shortcuts.gestures().filter { it.id != g.id }
                when (which) {
                    0 -> pickAction(this, g.action) { a ->
                        shortcuts.saveGestures(shortcuts.gestures().map { if (it.id == g.id) Gesture(g.id, g.samples, a) else it })
                        refresh()
                    }
                    1 -> { shortcuts.saveGestures(others); refresh() }
                }
            }
            .show()
    }

    private fun text(s: String, size: Float) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Color.BLACK)
    }

    private fun row(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(Color.BLACK)
        minHeight = (56 * resources.displayMetrics.density).toInt()
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad, 0, pad, 0)
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener { onClick() }
    }

    companion object {
        /** Lets the user choose an action: a built-in one, or "Open an app…" then the app. */
        fun pickAction(context: Context, current: Action, onCancel: () -> Unit = {}, onPick: (Action) -> Unit) {
            val pm = context.packageManager
            val labels = Action.BUILT_IN.map { (if (it == current) "✓ " else "") + it.label(pm) } + "Open an app…"
            AlertDialog.Builder(context)
                .setTitle("What should it do?")
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < Action.BUILT_IN.size) onPick(Action.BUILT_IN[which]) else pickApp(context, onCancel, onPick)
                }
                .setOnCancelListener { onCancel() }
                .show()
        }

        private fun pickApp(context: Context, onCancel: () -> Unit, onPick: (Action) -> Unit) {
            val apps = AppMatcher(context).apps.sortedBy { it.label.lowercase() }
            AlertDialog.Builder(context)
                .setTitle("Open which app?")
                .setItems(apps.map { it.label }.toTypedArray()) { _, which -> onPick(Action.openApp(apps[which].component)) }
                .setOnCancelListener { onCancel() }
                .show()
        }
    }
}

/** A small picture of a recorded gesture. */
class GestureThumb(context: Context, private val strokes: List<Stroke>) : View(context) {

    private val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        val pts = strokes.filter { it.size > 0 }
        if (pts.isEmpty()) return
        val minX = pts.minOf { it.xs.min() }
        val minY = pts.minOf { it.ys.min() }
        val size = maxOf(pts.maxOf { it.xs.max() } - minX, pts.maxOf { it.ys.max() } - minY, 1f)
        val margin = 6f * resources.displayMetrics.density
        val scale = (minOf(width, height) - 2 * margin) / size
        for (s in pts) {
            for (i in 1 until s.size) {
                canvas.drawLine(margin + (s.xs[i - 1] - minX) * scale, margin + (s.ys[i - 1] - minY) * scale,
                    margin + (s.xs[i] - minX) * scale, margin + (s.ys[i] - minY) * scale, paint)
            }
        }
    }
}
