package io.github.kabellosan.penkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * The keyboard's bar of keys and guesses, which can be pulled up or down with a
 * finger or the pen: a mostly vertical drag anywhere on it, keys included (a drag
 * that starts on a key takes the touch away from the key, so the key doesn't fire).
 *
 * While [ignoreFingers] says so, finger touches (a palm resting on the bar
 * while writing) are dropped whole; the pen always gets through.
 */
class PullBar(
    context: Context,
    private val onPull: (up: Boolean) -> Unit,
    private val ignoreFingers: () -> Boolean,
) : LinearLayout(context) {

    // Small: from the slim bar, an upward pull leaves the keyboard's window at once.
    private val reach = 20 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var dropping = false
    private var pulledOnce = false

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = e.x
            downY = e.y
            dropping = e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && ignoreFingers()
            pulledOnce = false
        }
        if (dropping) return true
        return super.dispatchTouchEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean =
        e.actionMasked == MotionEvent.ACTION_MOVE && pull(e)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) pull(e)
        return true
    }

    /**
     * Pulls one step as soon as the drag is far enough, not on lifting: a drag
     * out of the keyboard's window may never send its lift here. One step per drag.
     */
    private fun pull(e: MotionEvent): Boolean {
        if (!pulled(e)) return false
        if (!pulledOnce) {
            pulledOnce = true
            // After this touch is done with the keys the bar is laid out anew.
            val up = e.y < downY
            post { onPull(up) }
        }
        return true
    }

    private fun pulled(e: MotionEvent): Boolean {
        val dy = abs(e.y - downY)
        return dy > reach && dy > 1.5f * abs(e.x - downX)
    }
}
