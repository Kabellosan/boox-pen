package io.github.kabellosan.penkeyboard

import android.os.Handler
import android.os.Looper

/**
 * Shortcuts for the pen's side button. On the Go 7 the button only reaches apps
 * while the pen touches the screen, where it turns the pen into an eraser, so
 * the shortcuts are eraser contacts: hold the button and tap, press, or draw.
 *
 * Contacts are gathered until the pen rests for a moment, like letters of a word:
 * - only quick taps: two or three of them are a [Trigger];
 * - one still press: [Trigger.LONG_PRESS], straight away;
 * - anything drawn: the whole drawing (a T is two strokes) goes to [onShape];
 * - a single tap alone goes to [onTap], so it can still rub out a dot;
 * - a tap, then a press that slides: [takeTap] lets the caller claim that tap.
 */
class ButtonGestures(
    /** How far the pen may wander, in pixels, and still count as a tap. */
    private val slopPx: Float,
    private val onTrigger: (Trigger) -> Unit,
    private val onShape: (List<Stroke>) -> Unit,
    private val onTap: (Stroke) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val tapMaxMs = 350L
    private val tapGapMs = 350L
    private val shapeGapMs = 600L
    private val pressMinMs = 700L

    private var contacts = ArrayList<Stroke>()
    private var drew = false

    /** True while contacts are waiting to be settled. */
    val pending get() = contacts.isNotEmpty()

    private val finish = Runnable {
        val c = contacts
        val wasDrawing = drew
        contacts = ArrayList()
        drew = false
        when {
            wasDrawing -> onShape(c)
            c.size == 1 -> onTap(c[0])
            c.size == 2 -> onTrigger(Trigger.DOUBLE_TAP)
            c.size >= 3 -> onTrigger(Trigger.TRIPLE_TAP)
        }
    }

    fun isStill(path: Stroke) = path.size == 0 ||
        (path.xs.max() - path.xs.min() <= slopPx && path.ys.max() - path.ys.min() <= slopPx)

    /** One eraser contact, from touch-down to lift, that lasted [durationMs]. */
    fun feed(path: Stroke, durationMs: Long) {
        val still = isStill(path)
        main.removeCallbacks(finish)
        if (still && durationMs >= pressMinMs && contacts.isEmpty()) {
            onTrigger(Trigger.LONG_PRESS)
            return
        }
        contacts.add(path)
        if (!still || durationMs > tapMaxMs) drew = true
        main.postDelayed(finish, if (drew) shapeGapMs else tapGapMs)
    }

    /** If exactly one quick tap is waiting, forgets it and returns true (tap, then slide). */
    fun takeTap(): Boolean {
        if (contacts.size != 1 || drew) return false
        main.removeCallbacks(finish)
        contacts = ArrayList()
        return true
    }

    /** Settles any contacts still waiting, before something else happens. */
    fun flush() {
        if (contacts.isEmpty()) return
        main.removeCallbacks(finish)
        finish.run()
    }
}
