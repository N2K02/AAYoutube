package com.aayoutube.app

import android.content.Context
import android.view.View
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/** AABrowser's root: transient car-surface invisibility must not pause video decoding. */
class CarFrameLayout(context: Context) : FrameLayout(context) {
    /** Observe, never consume, car touches. Chromium must still receive seek/settings taps. */
    var onSwipeDown: (() -> Unit)? = null
    /** Called after a quick tap, without consuming the original YouTube event. */
    var onVideoTap: (() -> Unit)? = null
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeStartAt = 0L
    private var trackingSwipe = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackingSwipe = event.pointerCount == 1
                swipeStartX = event.x
                swipeStartY = event.y
                swipeStartAt = event.eventTime
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL ->
                trackingSwipe = false
            MotionEvent.ACTION_UP -> {
                if (trackingSwipe && event.pointerCount == 1) {
                    val dx = event.x - swipeStartX
                    val dy = event.y - swipeStartY
                    val threshold = 72f * resources.displayMetrics.density
                    if (dy > threshold && abs(dx) < dy * 0.65f &&
                        event.eventTime - swipeStartAt < 1_100L) {
                        // Changing Compose/WebView parents in dispatchTouchEvent can lose UP events.
                        post { onSwipeDown?.invoke() }
                    } else if (abs(dx) < 18f * resources.displayMetrics.density &&
                        abs(dy) < 18f * resources.displayMetrics.density &&
                        event.eventTime - swipeStartAt < 700L) {
                        // Let the WebView handle the same tap and expose native player controls.
                        post { onVideoTap?.invoke() }
                    }
                }
                trackingSwipe = false
            }
        }
        // Never intercept taps or drag events from YouTube, including quality/settings.
        return super.dispatchTouchEvent(event)
    }
    override fun dispatchWindowVisibilityChanged(visibility: Int) {
        if (visibility == View.VISIBLE) super.dispatchWindowVisibilityChanged(visibility)
    }
    override fun dispatchVisibilityChanged(changedView: View, visibility: Int) {
        if (visibility == View.VISIBLE) super.dispatchVisibilityChanged(changedView, visibility)
    }
}
