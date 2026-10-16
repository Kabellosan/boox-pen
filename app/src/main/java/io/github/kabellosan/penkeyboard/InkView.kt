package io.github.kabellosan.penkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
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
        /** The pen's eraser (end or side button) swept along [path]. */
        fun onErase(path: Stroke)
    }

    /** Strokes written since the last clear, in this view's pixel coordinates. */
    val strokes = ArrayList<Stroke>()

    private val main = Handler(Looper.getMainLooper())
    private var touchHelper: TouchHelper? = null
    private var current: Stroke? = null
    private var eraserPath: Stroke? = null

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

    // ── Onyx raw drawing ────────────────────────────────────────────

    private val callback = object : RawInputCallback() {
        // Some firmware reports the eraser as a drawing stroke with eraser = true
        // instead of using the erasing callbacks, so both routes lead to erasing.
        override fun onBeginRawDrawing(eraser: Boolean, p: TouchPoint) {
            main.post { if (eraser) beginErase(p) else beginStroke(p.x, p.y, p.timestamp) }
        }

        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) {
            main.post { eraserPath?.add(p.x, p.y, p.timestamp) ?: current?.add(p.x, p.y, p.timestamp) }
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
            main.post { eraserPath?.add(p.x, p.y, p.timestamp) }
        }

        override fun onRawErasingTouchPointListReceived(list: TouchPointList) {
            // Fill in the path if the firmware sent no move events.
            val pts = (0 until list.size()).mapNotNull { list.get(it) }
            main.post { pts.forEach { eraserPath?.add(it.x, it.y, it.timestamp) } }
        }

        override fun onEndRawErasing(eraser: Boolean, p: TouchPoint) {
            main.post { endErase(p.x, p.y, p.timestamp) }
        }
    }

    /** Starts pen capture. Call when the keyboard is shown and laid out. */
    fun startPen() {
        if (!isBoox || width == 0) return
        try {
            val th = touchHelper ?: TouchHelper.create(this, callback).also { touchHelper = it }
            th.openRawDrawing()
            val limit = Rect().also { getLocalVisibleRect(it) }
            th.setLimitRect(limit, emptyList())
            th.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
            th.setStrokeWidth(paint.strokeWidth)
            th.setStrokeColor(Color.BLACK)
            th.enableFingerTouch(false)
            enableSideButtonErase(th)
            th.setRawDrawingRenderEnabled(true)
            th.setRawDrawingEnabled(true)
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

    /** Stops pen capture. Call when the keyboard is hidden. */
    fun stopPen() {
        try {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.closeRawDrawing()
        } catch (e: Throwable) {
            Log.w(PenApp.TAG, "stopPen: ${e.message}")
        }
    }

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
    }

    private fun endErase(x: Float, y: Float, t: Long) {
        val path = eraserPath ?: return
        path.add(x, y, t)
        eraserPath = null
        listener.onErase(path)
    }

    // ── Fallback for non-Boox devices (emulator, phone) ─────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (usingOnyx) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                if (isEraser(e)) beginErase(e.x, e.y, e.eventTime) else beginStroke(e.x, e.y, e.eventTime)
            MotionEvent.ACTION_MOVE -> eraserPath?.add(e.x, e.y, e.eventTime) ?: current?.add(e.x, e.y, e.eventTime)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (eraserPath != null) endErase(e.x, e.y, e.eventTime) else endStroke(e.x, e.y, e.eventTime)
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val y = height * 0.7f
        canvas.drawLine(0f, y, width.toFloat(), y, baseline)
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

    /** The eraser end of the pen, or the side button held down. */
    private fun isEraser(e: MotionEvent) =
        e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
            e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (touchHelper != null) post { startPen() }
    }
}
