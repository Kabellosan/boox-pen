package io.github.kabellosan.penkeyboard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Pen scroll: the pen moves the app underneath as if two fingers dragged it, for
 * infinite canvases (Boox notes, Miro) and zoomed pages (manga).
 *
 * Android gives no app another app's pen strokes, so while it is on, a see-through
 * layer over the whole screen takes them. When the pen lifts, the helper lets go
 * of the screen for a moment and replays the stroke as a two-finger drag, which
 * apps read as "move the page", never as drawing or selecting.
 * - pen tip drag: moves the page with the pen;
 * - drag with the button held (eraser): up zooms in, down zooms out;
 * - a tap without moving: turns pen scroll off.
 * Fingers are blocked too while it is on.
 */
class PenScroll(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var layer: Layer? = null
    private var params: WindowManager.LayoutParams? = null

    val on get() = layer != null

    fun toggle() = if (on) stop() else start()

    fun start() {
        if (on) return
        val view = Layer(service)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        try {
            wm.addView(view, p)
            layer = view
            params = p
            PenButtonProbe.record(service, "Pen scroll: on")
        } catch (e: Exception) {
            PenButtonProbe.record(service, "Pen scroll: can't cover the screen (${e.message})")
        }
    }

    fun stop() {
        val view = layer ?: return
        layer = null
        params = null
        runCatching { wm.removeView(view) }
        PenButtonProbe.record(service, "Pen scroll: off")
    }

    /**
     * Scrolls the page by [dx], [dy] pixels (fingers moving that way), for the
     * Scroll up/down shortcuts. Works with pen scroll on or off.
     */
    fun scrollBy(dx: Float, dy: Float) {
        val m = service.resources.displayMetrics
        val from = PointF(m.widthPixels / 2f - dx / 2, m.heightPixels / 2f - dy / 2)
        drag(listOf(from, PointF(from.x + dx, from.y + dy)), 300)
    }

    // ── Replaying a stroke as two fingers ──────────────────────────

    /** Two fingers side by side, both following [points]. */
    private fun drag(points: List<PointF>, durationMs: Long) {
        val w = service.resources.displayMetrics.widthPixels.toFloat()
        val h = service.resources.displayMetrics.heightPixels.toFloat()
        // Keep both fingers on the screen for the whole stroke.
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val shift = when {
            minX - FINGER_GAP / 2 < EDGE -> EDGE - (minX - FINGER_GAP / 2)
            maxX + FINGER_GAP / 2 > w - EDGE -> (w - EDGE) - (maxX + FINGER_GAP / 2)
            else -> 0f
        }
        val fingers = listOf(-FINGER_GAP / 2, FINGER_GAP / 2).map { off ->
            points.map { PointF((it.x + off + shift).coerceIn(0f, w - 1), it.y.coerceIn(0f, h - 1)) }
        }
        dispatch(fingers, durationMs)
    }

    /** Two fingers pinching around [at]: [scale] > 1 spreads them (zoom in). */
    private fun pinch(at: PointF, scale: Float) {
        val w = service.resources.displayMetrics.widthPixels.toFloat()
        val h = service.resources.displayMetrics.heightPixels.toFloat()
        val start = PINCH_GAP / 2
        val end = (start * scale).coerceIn(20f, w / 2)
        val steps = 12
        val fingers = listOf(-1f, 1f).map { side ->
            (0..steps).map { i ->
                val r = start + (end - start) * i / steps
                PointF((at.x + side * r).coerceIn(0f, w - 1), at.y.coerceIn(0f, h - 1))
            }
        }
        dispatch(fingers, 400)
    }

    /**
     * Sends the fingers' paths, then holds them still for a moment before lifting,
     * so apps don't fling the page on past where the pen stopped.
     */
    private fun dispatch(fingers: List<List<PointF>>, durationMs: Long) {
        val strokes = fingers.map { pts ->
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (p in pts.drop(1)) lineTo(p.x, p.y)
                // A path that goes nowhere is a tap; nudge it so it stays a drag.
                if (pts.size == 1) lineTo(pts[0].x + 1, pts[0].y)
            }
            GestureDescription.StrokeDescription(path, 0, durationMs, true)
        }
        letGo(true)
        // Give the window a moment to stop taking touches before the fingers land.
        main.postDelayed({
            val sent = service.dispatchGesture(build(strokes), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = hold(strokes, fingers)
                override fun onCancelled(gestureDescription: GestureDescription?) = letGo(false)
            }, main)
            if (!sent) {
                PenButtonProbe.record(service, "Pen scroll: Android refused the drag")
                letGo(false)
            }
        }, LET_GO_MS)
    }

    private fun hold(strokes: List<GestureDescription.StrokeDescription>, fingers: List<List<PointF>>) {
        val still = strokes.mapIndexed { i, s ->
            val end = fingers[i].last()
            val path = Path().apply { moveTo(end.x, end.y) }
            try {
                s.continueStroke(path, 0, HOLD_MS, false)
            } catch (e: Exception) {
                null
            }
        }
        if (still.any { it == null }) {
            letGo(false)
            return
        }
        val sent = service.dispatchGesture(build(still.filterNotNull()), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = letGo(false)
            override fun onCancelled(gestureDescription: GestureDescription?) = letGo(false)
        }, main)
        if (!sent) letGo(false)
    }

    private fun build(strokes: List<GestureDescription.StrokeDescription>) =
        GestureDescription.Builder().apply { strokes.forEach { addStroke(it) } }.build()

    /** While replaying, the layer must not catch the fingers it sends. */
    private fun letGo(passThrough: Boolean) {
        val view = layer ?: return
        val p = params ?: return
        p.flags = if (passThrough) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(view, p) }
    }

    // ── The layer over the screen ───────────────────────────────────

    @SuppressLint("ViewConstructor")
    private inner class Layer(context: Context) : View(context) {
        private val points = ArrayList<PointF>()
        private var downAt = 0L
        private var eraser = false
        private val density = context.resources.displayMetrics.density
        private val frame = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
        }
        private val pill = Paint().apply { color = Color.BLACK }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 15 * density
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            // A frame and a label, so it's clear the pen moves the page now.
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), frame)
            val label = "Pen scroll · tap to stop"
            val tw = text.measureText(label)
            val top = 28 * density
            canvas.drawRoundRect(width / 2f - tw / 2 - 14 * density, top,
                width / 2f + tw / 2 + 14 * density, top + 30 * density, 15 * density, 15 * density, pill)
            canvas.drawText(label, width / 2f, top + 20 * density, text)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    points.clear()
                    downAt = e.eventTime
                    eraser = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                    points.add(PointF(e.rawX, e.rawY))
                }
                MotionEvent.ACTION_MOVE -> {
                    // Raw positions are screen positions, which is what the fingers need.
                    val dx = e.rawX - e.x
                    val dy = e.rawY - e.y
                    for (i in 0 until e.historySize) points.add(PointF(e.getHistoricalX(i) + dx, e.getHistoricalY(i) + dy))
                    points.add(PointF(e.rawX, e.rawY))
                }
                MotionEvent.ACTION_UP -> finish(e.eventTime - downAt)
                MotionEvent.ACTION_CANCEL -> points.clear()
            }
            return true
        }

        private fun finish(heldMs: Long) {
            if (points.isEmpty()) return
            val first = points.first()
            val last = points.last()
            val moved = points.maxOf { hypot(it.x - first.x, it.y - first.y) }
            when {
                moved < TAP_SLOP * density -> stop()
                eraser -> pinch(first, exp((first.y - last.y) / (ZOOM_PER_E * density)))
                else -> drag(thin(points), heldMs.coerceIn(MIN_DRAG_MS, MAX_DRAG_MS))
            }
            points.clear()
        }
    }

    /** Every few pixels of the stroke is plenty for a drag. */
    private fun thin(pts: List<PointF>): List<PointF> {
        val out = arrayListOf(pts.first())
        for (p in pts) if (hypot(p.x - out.last().x, p.y - out.last().y) >= 6f) out.add(p)
        if (out.last() !== pts.last()) out.add(pts.last())
        return out
    }

    companion object {
        /** Pixels between the two replayed fingers. */
        private const val FINGER_GAP = 160f
        /** Starting gap of a pinch, in pixels. */
        private const val PINCH_GAP = 240f
        /** Keeps replayed fingers this far from the screen's edges. */
        private const val EDGE = 8f
        /** dp of button-held drag that zooms by a factor of e (about 2.7x). */
        private const val ZOOM_PER_E = 160f
        /** A pen touch moving less than this (dp) is a tap. */
        private const val TAP_SLOP = 10f
        private const val MIN_DRAG_MS = 150L
        private const val MAX_DRAG_MS = 900L
        private const val LET_GO_MS = 60L
        private const val HOLD_MS = 150L
    }
}
