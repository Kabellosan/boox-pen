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

    private val reach = 28 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var dropping = false

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = e.x
            downY = e.y
            dropping = e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && ignoreFingers()
        }
        if (dropping) return true
        return super.dispatchTouchEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean =
        e.actionMasked == MotionEvent.ACTION_MOVE && pulled(e)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && pulled(e)) onPull(e.y < downY)
        return true
    }

    private fun pulled(e: MotionEvent): Boolean {
        val dy = abs(e.y - downY)
        return dy > reach && dy > 1.5f * abs(e.x - downX)
    }
}
