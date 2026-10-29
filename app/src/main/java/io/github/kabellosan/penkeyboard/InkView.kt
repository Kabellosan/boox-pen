package io.github.kabellosan.penkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * The writing area. On a Boox, the Onyx TouchHelper draws ink straight into the
 * e-ink overlay (that is what makes it fast) and hands us the points. Anywhere
 * else it falls back to normal touch events and draws the ink itself.
 *
 * The TouchHelper call order is taken from Miroir-IME, which works on the Go 7:
 * openRawDrawing() first (it resets state), then limit rect, style, enable.
 */
class InkView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onStrokeStart()
        fun onStrokeEnd()
        /** The pen's eraser (end or side button) touched along [path] for [durationMs]. */
        fun onErase(path: Stroke, durationMs: Long)
        /** The eraser touched down; [onEraseMove] follows with the path so far. */
        fun onEraseStart() {}
        /** The eraser moved; [path] holds every point since it touched down. */
        fun onEraseMove(path: Stroke) {}
    }

    /** See-through: no white page or baseline, so the app underneath shows (Pen Pad). */
    var transparent = false

    /** A white page with a dot grid, edge to edge (full screen's paper look). */
    var dotted = false

    /**
     * Set while the ink covers the whole screen: a finger tap anywhere on it runs
     * this (leaves full screen). The pen still writes.
     */
    var onFingerTap: (() -> Unit)? = null
    private var fingerDownX = 0f
    private var fingerDownY = 0f
    private var fingerDownAt = 0L

    /** Strokes written since the last clear, in this view's pixel coordinates. */
    val strokes = ArrayList<Stroke>()

    private val main = Handler(Looper.getMainLooper())
    private var touchHelper: TouchHelper? = null
    private var current: Stroke? = null
    private var eraserPath: Stroke? = null
    private var eraseStartedAt = 0L

    private val isBoox = Build.MANUFACTURER.equals("ONYX", ignoreCase = true)
    val usingOnyx get() = touchHelper != null

    private val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val baseline = Paint().apply {
        color = Color.GRAY
        strokeWidth = resources.displayMetrics.density
    }

    /**
     * The box around the writing area, so it is plain where ink can go. Solid black:
     * gray borders all but vanish on e-ink. Ink is held inside it by the limit rect.
     */
    private val frame = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        isAntiAlias = true
    }
    private val dots = Paint().apply {
        color = Color.DKGRAY
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    /** About 5 mm on the Go 7, like a dotted notebook. */
    private val dotGap = 32f * resources.displayMetrics.density
    private val frameInset = 6f * resources.displayMetrics.density
    private val frameRadius = 8f * resources.displayMetrics.density

    // ── Onyx raw drawing ────────────────────────────────────────────

    private val callback = object : RawInputCallback() {
        // Some firmware reports the eraser as a drawing stroke with eraser = true
        // instead of using the erasing callbacks, so both routes lead to erasing.
        override fun onBeginRawDrawing(eraser: Boolean, p: TouchPoint) {
            main.post { if (eraser) beginErase(p) else beginStroke(p.x, p.y, p.timestamp) }
        }

        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) {
            main.post { if (eraserPath != null) moveErase(p.x, p.y, p.timestamp) else current?.add(p.x, p.y, p.timestamp) }
        }

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
            // Some firmware sends only the full list, after the stroke ended.
            // If the stroke we built from move events is too thin, use the list.
            val pts = (0 until list.size()).mapNotNull { list.get(it) }
            main.post {
                if (eraserPath != null) return@post
                val last = strokes.lastOrNull() ?: return@post
                if (last.size < 3 && pts.size > last.size) {
                    strokes[strokes.size - 1] = Stroke().apply { pts.forEach { add(it.x, it.y, it.timestamp) } }
                }
            }
        }

        override fun onEndRawDrawing(eraser: Boolean, p: TouchPoint) {
            main.post { if (eraserPath != null) endErase(p.x, p.y, p.timestamp) else endStroke(p.x, p.y, p.timestamp) }
        }

        override fun onBeginRawErasing(eraser: Boolean, p: TouchPoint) {
            main.post { beginErase(p) }
        }

        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) {
            main.post { moveErase(p.x, p.y, p.timestamp) }
        }

        override fun onRawErasingTouchPointListReceived(list: TouchPointList) {
            // Fill in the path if the firmware sent no move events. With moves already
            // there, adding the list again would draw the path twice.
            val pts = (0 until list.size()).mapNotNull { list.get(it) }
            main.post {
                val path = eraserPath ?: return@post
                if (path.size <= 2) pts.forEach { path.add(it.x, it.y, it.timestamp) }
            }
        }

        override fun onEndRawErasing(eraser: Boolean, p: TouchPoint) {
            main.post { endErase(p.x, p.y, p.timestamp) }
        }
    }

    /** Starts pen capture. Call when the keyboard is shown and laid out. */
    fun startPen(force: Boolean = false) {
        // Hidden (the keyboard pulled down to slim): raw drawing would ink over the app.
        if (!isBoox || width == 0 || visibility != VISIBLE) return
        // Re-opening raw drawing that is already ours resets the SDK mid-word and
        // joins strokes with straight lines, so only do it when asked to.
        if (!force && owner === this && penOn) return
        try {
            val th = touchHelper ?: TouchHelper.create(this, callback).also { touchHelper = it }
            owner = this
            th.openRawDrawing()
            val limit = writingRect()
            th.setLimitRect(limit, emptyList())
            th.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
            th.setStrokeWidth(paint.strokeWidth)
            th.setStrokeColor(Color.BLACK)
            th.enableFingerTouch(false)
            enableSideButtonErase(th)
            th.setRawDrawingRenderEnabled(true)
            th.setRawDrawingEnabled(true)
            penOn = true
        } catch (e: Throwable) {
            Log.w(PenApp.TAG, "Onyx TouchHelper unavailable, using plain touch: ${e.message}")
            touchHelper = null
        }
    }

    /**
     * Asks the SDK to treat the pen's side button as an eraser. Not every SDK
     * version has this call, so it is looked up by name and skipped if missing.
     */
    private fun enableSideButtonErase(th: TouchHelper) {
        try {
            th.javaClass.getMethod("enableSideBtnErase", Boolean::class.javaPrimitiveType).invoke(th, true)
        } catch (e: Throwable) {
            Log.i(PenApp.TAG, "enableSideBtnErase not available: ${e.message}")
        }
    }

    private var penOn = false

    /**
     * Where the pen may ink: the visible view, or inside the box when there is one,
     * so a stroke can't spill over the border onto the keys or the app.
     */
    private fun writingRect(): Rect {
        val r = Rect().also { getLocalVisibleRect(it) }
        if (!transparent && !dotted) {
            val i = (frameInset + frame.strokeWidth + 2 * resources.displayMetrics.density).toInt()
            r.inset(i, i)
        }
        return r
    }

    /** Stops pen capture. Call when the keyboard is hidden. */
    fun stopPen() {
        // Raw drawing is one switch for the whole app: only the view that turned it
        // on last may turn it off, so the keyboard hiding late can't kill Pad Launch's.
        if (owner !== this) return
        owner = null
        penOn = false
        try {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.closeRawDrawing()
        } catch (e: Throwable) {
            Log.w(PenApp.TAG, "stopPen: ${e.message}")
        }
    }

    /** True while a stroke is being drawn. */
    val drawing get() = current != null

    /** Wipes ink that is no longer in [strokes] off the screen. */
    fun repaint() = redraw()

    /** Forgets the strokes and wipes the ink off the screen. */
    fun clear() {
        strokes.clear()
        current = null
        eraserPath = null
        redraw()
    }

    /**
     * Removes the strokes that came within reach of [path] and redraws the rest.
     * Returns true if anything was removed.
     */
    fun eraseAlong(path: Stroke): Boolean {
        val reach = 14f * resources.displayMetrics.density
        val reach2 = reach * reach
        val hit = strokes.filter { s ->
            (0 until s.size).any { i ->
                (0 until path.size).any { j ->
                    val dx = s.xs[i] - path.xs[j]
                    val dy = s.ys[i] - path.ys[j]
                    dx * dx + dy * dy < reach2
                }
            }
        }
        if (hit.isEmpty()) return false
        strokes.removeAll(hit.toSet())
        redraw()
        return true
    }

    /**
     * Wipes the overlay and repaints the remaining strokes from [onDraw].
     * Turning raw drawing off and refreshing the view clears the overlay ink;
     * then it is turned back on for the next stroke.
     */
    private fun redraw() {
        val th = touchHelper
        if (th == null) {
            invalidate()
            return
        }
        try {
            th.setRawDrawingEnabled(false)
            invalidate()
            EpdController.refreshScreen(this, UpdateMode.GU)
            th.setRawDrawingEnabled(true)
        } catch (e: Throwable) {
            Log.w(PenApp.TAG, "redraw: ${e.message}")
        }
    }

    // ── Shared stroke bookkeeping ───────────────────────────────────

    private fun beginStroke(x: Float, y: Float, t: Long) {
        current = Stroke().also { it.add(x, y, t); strokes += it }
        listener.onStrokeStart()
    }

    private fun endStroke(x: Float, y: Float, t: Long) {
        current?.add(x, y, t)
        current = null
        listener.onStrokeEnd()
    }

    private fun beginErase(p: TouchPoint) = beginErase(p.x, p.y, p.timestamp)

    private fun beginErase(x: Float, y: Float, t: Long) {
        current = null
        eraserPath = Stroke().also { it.add(x, y, t) }
        eraseStartedAt = SystemClock.uptimeMillis()
        listener.onEraseStart()
    }

    private fun moveErase(x: Float, y: Float, t: Long) {
        val path = eraserPath ?: return
        path.add(x, y, t)
        listener.onEraseMove(path)
    }

    private fun endErase(x: Float, y: Float, t: Long) {
        val path = eraserPath ?: return
        path.add(x, y, t)
        eraserPath = null
        listener.onErase(path, SystemClock.uptimeMillis() - eraseStartedAt)
    }

    // ── Fallback for non-Boox devices (emulator, phone) ─────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val tap = onFingerTap
        if (tap != null && e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { fingerDownX = e.x; fingerDownY = e.y; fingerDownAt = e.eventTime }
                MotionEvent.ACTION_UP -> {
                    val slop = 24 * resources.displayMetrics.density
                    if (kotlin.math.hypot(e.x - fingerDownX, e.y - fingerDownY) < slop &&
                        e.eventTime - fingerDownAt < 500) tap()
                }
            }
            return true
        }
        if (usingOnyx) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                if (isEraser(e)) beginErase(e.x, e.y, e.eventTime) else beginStroke(e.x, e.y, e.eventTime)
            MotionEvent.ACTION_MOVE ->
                if (eraserPath != null) moveErase(e.x, e.y, e.eventTime) else current?.add(e.x, e.y, e.eventTime)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (eraserPath != null) endErase(e.x, e.y, e.eventTime) else endStroke(e.x, e.y, e.eventTime)
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (dotted) {
            canvas.drawColor(Color.WHITE)
            canvas.drawPoints(dotGrid(), dots)
        } else if (!transparent) {
            canvas.drawColor(Color.WHITE)
            val half = frame.strokeWidth / 2
            val box = RectF(frameInset + half, frameInset + half, width - frameInset - half, height - frameInset - half)
            canvas.drawRoundRect(box, frameRadius, frameRadius, frame)
            val y = height * 0.7f
            val margin = frameInset + 12 * resources.displayMetrics.density
            canvas.drawLine(margin, y, width - margin, y, baseline)
        }
        // With the Onyx overlay this only matters after redraw(), which wipes the
        // overlay and leaves this view's own drawing on screen.
        for (s in strokes) {
            if (s.size == 0) continue
            val path = Path().apply {
                moveTo(s.xs[0], s.ys[0])
                for (i in 1 until s.size) lineTo(s.xs[i], s.ys[i])
            }
            canvas.drawPath(path, paint)
        }
    }

    private var grid = FloatArray(0)
    private var gridSize = 0L

    /** The dot centres for this size, centred so the margins match. */
    private fun dotGrid(): FloatArray {
        val size = (width.toLong() shl 32) or height.toLong()
        if (size == gridSize) return grid
        val cols = (width / dotGap).toInt()
        val rows = (height / dotGap).toInt()
        val left = (width - (cols - 1) * dotGap) / 2
        val top = (height - (rows - 1) * dotGap) / 2
        grid = FloatArray(2 * cols * rows)
        var i = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            grid[i++] = left + c * dotGap
            grid[i++] = top + r * dotGap
        }
        gridSize = size
        return grid
    }

    /** The eraser end of the pen, or the side button held down. */
    private fun isEraser(e: MotionEvent) =
        e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
            e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0

    /**
     * A screen opened from the keyboard (Pad Launch, Pen Pad) comes up before the
     * keyboard has finished hiding, and the keyboard's stopPen() then switches raw
     * drawing off for the whole app. Opening it again once this window has the
     * focus wins that race.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && touchHelper != null) {
            main.postDelayed({ if (hasWindowFocus()) startPen() }, 300)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (touchHelper != null) post { startPen(force = true) }
    }

    companion object {
        /** The InkView that last started raw drawing. */
        private var owner: InkView? = null
    }
}
