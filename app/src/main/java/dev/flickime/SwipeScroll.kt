package dev.flickime

import android.content.Context
import android.view.MotionEvent
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import kotlin.math.abs

/**
 * Detects a vertical swipe across a scrolling view without breaking its scrolling.
 * The gesture origin is taken in dispatchTouchEvent, so it is right even when the touch
 * starts on a child (a candidate), which an OnTouchListener never sees.
 * A callback returns true if it handled the swipe; otherwise scrolling continues.
 */
private class SwipeTracker(private val threshold: Float) {
    var onUp: (() -> Boolean)? = null
    var onDown: (() -> Boolean)? = null
    private var x0 = 0f
    private var y0 = 0f
    var fired = false
        private set

    /** Returns true when the event starts (or belongs to) a handled swipe. */
    fun track(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; fired = false }
            MotionEvent.ACTION_MOVE -> if (!fired) {
                val dx = e.rawX - x0; val dy = e.rawY - y0
                if (abs(dy) > threshold && abs(dy) > abs(dx) * 1.5f) {
                    val cb = if (dy < 0) onUp else onDown
                    if (cb != null && cb()) { fired = true; return true }
                }
            }
        }
        return fired
    }
}

private fun cancelOf(e: MotionEvent) = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }

class SwipeHorizontalScrollView(context: Context) : HorizontalScrollView(context) {
    private val t = SwipeTracker(28 * resources.displayMetrics.density)
    var onSwipeUp: (() -> Boolean)? by t::onUp
    var onSwipeDown: (() -> Boolean)? by t::onDown

    /** While true (a candidate is being dragged to the bin) swipes are not detected. */
    var suppress: () -> Boolean = { false }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (suppress()) return super.dispatchTouchEvent(e)
        val was = t.fired
        if (t.track(e)) {
            if (!was) { val c = cancelOf(e); super.dispatchTouchEvent(c); c.recycle() }
            return true
        }
        return super.dispatchTouchEvent(e)
    }
}

class SwipeScrollView(context: Context) : ScrollView(context) {
    private val t = SwipeTracker(28 * resources.displayMetrics.density)
    var onSwipeUp: (() -> Boolean)? by t::onUp
    var onSwipeDown: (() -> Boolean)? by t::onDown

    /** While true (a candidate is being dragged to the bin) swipes are not detected. */
    var suppress: () -> Boolean = { false }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (suppress()) return super.dispatchTouchEvent(e)
        val was = t.fired
        if (t.track(e)) {
            if (!was) { val c = cancelOf(e); super.dispatchTouchEvent(c); c.recycle() }
            return true
        }
        return super.dispatchTouchEvent(e)
    }
}
