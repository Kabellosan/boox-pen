package io.github.kabellosan.penkeyboard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent

/**
 * Turns the pen's side button into shortcuts: double click, triple click, and a
 * long hold. On the Go 7 the held button makes the pen report as an eraser
 * (TOOL_TYPE_ERASER), so "pressed" means eraser tool type or a stylus button bit.
 *
 * Clicks only count while the pen hovers: if the pen touched the screen while
 * the button was down, that was erasing and nothing fires. A hold fires on
 * release, so holding the button to erase never triggers it.
 *
 * Feed it every hover, generic and touch event the view sees; call [penUsed]
 * when the pen writes or erases through another route (the Onyx overlay).
 */
class StylusButton(private val onGesture: (Gesture) -> Unit) {

    enum class Gesture { DOUBLE_CLICK, TRIPLE_CLICK, HOLD }

    private val main = Handler(Looper.getMainLooper())
    private val clickMaxMs = 400L
    private val clickGapMs = 350L
    private val holdMinMs = 800L

    private var pressed = false
    private var pressedAt = 0L
    private var used = false
    private var clicks = 0

    private val finish = Runnable {
        when {
            clicks == 2 -> onGesture(Gesture.DOUBLE_CLICK)
            clicks >= 3 -> onGesture(Gesture.TRIPLE_CLICK)
        }
        clicks = 0
    }

    fun feed(e: MotionEvent) {
        if (e.pointerCount == 0) return
        val tool = e.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return
        val down = tool == MotionEvent.TOOL_TYPE_ERASER ||
            e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> penUsed()
        }
        if (down && !pressed) press()
        else if (!down && pressed && e.actionMasked != MotionEvent.ACTION_HOVER_EXIT) release()
    }

    /** The pen touched the screen; whatever the button is doing is not a click. */
    fun penUsed() {
        if (pressed) used = true
        if (clicks > 0) { main.removeCallbacks(finish); clicks = 0 }
    }

    private fun press() {
        pressed = true
        used = false
        pressedAt = SystemClock.uptimeMillis()
        main.removeCallbacks(finish)
    }

    private fun release() {
        pressed = false
        if (used) return
        val held = SystemClock.uptimeMillis() - pressedAt
        when {
            held >= holdMinMs -> { clicks = 0; onGesture(Gesture.HOLD) }
            held <= clickMaxMs -> { clicks++; main.postDelayed(finish, clickGapMs) }
            else -> clicks = 0
        }
    }
}
