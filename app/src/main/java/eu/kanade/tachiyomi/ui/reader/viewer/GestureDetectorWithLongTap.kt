package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/** Reader-only tap detector. A stationary hold is consumed without an action. */
open class GestureDetectorWithLongTap(
    context: Context,
    listener: Listener,
) : GestureDetector(context, listener) {

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longTapTime = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var lastDownEvent: MotionEvent? = null
    private var held = false
    private var holdCandidate = false

    private val longTapFn = Runnable {
        held = true
        cancelTapDetection()
    }

    // Keep Android's long-press detector from competing with double-tap quick scaling.
    init { setIsLongpressEnabled(false) }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(longTapFn)
                // Do not cancel a pending short tap here: Android still needs it for double tap.
                if (lastDownEvent != null) cancel()
                held = false
                holdCandidate = true
                lastDownEvent = MotionEvent.obtain(ev)
                downX = ev.x
                downY = ev.y
                handler.postDelayed(longTapFn, longTapTime)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) {
                    holdCandidate = false
                    handler.removeCallbacks(longTapFn)
                }
            }
            MotionEvent.ACTION_UP -> {
                // Also cover a delayed main-loop timer: elapsed hold must never become a tap.
                if (holdCandidate && ev.eventTime - ev.downTime >= longTapTime) {
                    held = true
                    cancelTapDetection()
                }
                handler.removeCallbacks(longTapFn)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                holdCandidate = false
                handler.removeCallbacks(longTapFn)
            }
        }
        // Cancel only tap recognition. The parent still passes every event to scrolling/zoom.
        val result = if (held) true else super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            lastDownEvent?.recycle()
            lastDownEvent = null
        }
        return result
    }

    private fun cancelTapDetection() {
        val now = SystemClock.uptimeMillis()
        val event = lastDownEvent?.let(MotionEvent::obtain)
            ?: MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        event.action = MotionEvent.ACTION_CANCEL
        super.onTouchEvent(event)
        event.recycle()
    }

    fun cancel() {
        holdCandidate = false
        handler.removeCallbacks(longTapFn)
        cancelTapDetection()
        lastDownEvent?.recycle()
        lastDownEvent = null
    }

    open class Listener : SimpleOnGestureListener()
}
