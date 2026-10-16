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
    }

    /** Strokes written since the last clear, in this view's pixel coordinates. */
    val strokes = ArrayList<Stroke>()

    private val main = Handler(Looper.getMainLooper())
    private var touchHelper: TouchHelper? = null
    private var current: Stroke? = null

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
        override fun onBeginRawDrawing(eraser: Boolean, p: TouchPoint) {
            main.post { beginStroke(p.x, p.y, p.timestamp) }
        }

        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) {
            main.post { current?.add(p.x, p.y, p.timestamp) }
        }

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
            // Some firmware sends only the full list, after the stroke ended.
            // If the stroke we built from move events is too thin, use the list.
            val pts = (0 until list.size()).mapNotNull { list.get(it) }
            main.post {
                val last = strokes.lastOrNull() ?: return@post
                if (last.size < 3 && pts.size > last.size) {
                    strokes[strokes.size - 1] = Stroke().apply { pts.forEach { add(it.x, it.y, it.timestamp) } }
                }
            }
        }

        override fun onEndRawDrawing(eraser: Boolean, p: TouchPoint) {
            main.post { endStroke(p.x, p.y, p.timestamp) }
        }

        override fun onBeginRawErasing(eraser: Boolean, p: TouchPoint) {}
        override fun onEndRawErasing(eraser: Boolean, p: TouchPoint) {}
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(list: TouchPointList) {}
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
            th.setRawDrawingRenderEnabled(true)
            th.setRawDrawingEnabled(true)
        } catch (e: Throwable) {
            Log.w(PenApp.TAG, "Onyx TouchHelper unavailable, using plain touch: ${e.message}")
            touchHelper = null
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
        val th = touchHelper
        if (th == null) {
            invalidate()
            return
        }
        // Turning raw drawing off and refreshing the view clears the overlay ink;
        // then turn it back on for the next word.
        try {
            th.setRawDrawingEnabled(false)
            invalidate()
            EpdController.refreshScreen(this, UpdateMode.GU)
            th.setRawDrawingEnabled(true)
        } catch (e: Throwable) {
            Log.w(PenApp.TAG, "clear: ${e.message}")
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

    // ── Fallback for non-Boox devices (emulator, phone) ─────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (usingOnyx) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginStroke(e.x, e.y, e.eventTime)
            MotionEvent.ACTION_MOVE -> current?.add(e.x, e.y, e.eventTime)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endStroke(e.x, e.y, e.eventTime)
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val y = height * 0.7f
        canvas.drawLine(0f, y, width.toFloat(), y, baseline)
        if (usingOnyx) return // the overlay shows the ink
        for (s in strokes) {
            if (s.size == 0) continue
            val path = Path().apply {
                moveTo(s.xs[0], s.ys[0])
                for (i in 1 until s.size) lineTo(s.xs[i], s.ys[i])
            }
            canvas.drawPath(path, paint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (touchHelper != null) post { startPen() }
    }
}
