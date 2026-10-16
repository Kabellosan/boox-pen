package io.github.kabellosan.penkeyboard

import android.os.Handler
import android.os.Looper

/**
 * Shortcuts for the pen's side button. On the Go 7 the button only reaches apps
 * while the pen touches the screen, where it turns the pen into an eraser, so
 * the shortcuts are eraser taps: hold the button and tap the screen.
 *
 * A sweep erases as usual. A tap that stays alone is also passed on as an erase,
 * so rubbing out a single dot still works. Two taps, three taps, or one long
 * press in place become gestures.
 */
class EraserTaps(
    /** How far the pen may wander, in pixels, and still count as a tap. */
    private val slopPx: Float,
    private val onGesture: (Gesture) -> Unit,
    private val onErase: (Stroke) -> Unit,
) {
    enum class Gesture { DOUBLE_TAP, TRIPLE_TAP, LONG_PRESS }

    private val main = Handler(Looper.getMainLooper())
    private val tapMaxMs = 350L
    private val tapGapMs = 350L
    private val pressMinMs = 700L

    private var taps = ArrayList<Stroke>()

    private val finish = Runnable {
        when {
            taps.size == 1 -> onErase(taps[0])
            taps.size == 2 -> onGesture(Gesture.DOUBLE_TAP)
            taps.size >= 3 -> onGesture(Gesture.TRIPLE_TAP)
        }
        taps = ArrayList()
    }

    /** One eraser contact, from touch-down to lift, that lasted [durationMs]. */
    fun feed(path: Stroke, durationMs: Long) {
        val still = path.size == 0 ||
            (path.xs.max() - path.xs.min() <= slopPx && path.ys.max() - path.ys.min() <= slopPx)
        when {
            still && durationMs <= tapMaxMs -> {
                main.removeCallbacks(finish)
                taps.add(path)
                main.postDelayed(finish, tapGapMs)
            }
            still && durationMs >= pressMinMs -> { flush(); onGesture(Gesture.LONG_PRESS) }
            else -> { flush(); onErase(path) }
        }
    }

    /** Settles any taps still waiting, before something else happens. */
    fun flush() {
        if (taps.isEmpty()) return
        main.removeCallbacks(finish)
        finish.run()
    }
}
