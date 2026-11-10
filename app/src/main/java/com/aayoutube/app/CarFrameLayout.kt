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
    /** Optional diagnostics only; the WebView receives every original event. */
    var onObservedGesture: ((CarVideoGesture) -> Unit)? = null
    private var maxDriftX = 0f
    private var maxDriftY = 0f
    private var trackingSwipe = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackingSwipe = event.pointerCount == 1
                swipeStartX = event.x
                swipeStartY = event.y
                swipeStartAt = event.eventTime
                maxDriftX = 0f
                maxDriftY = 0f
            }
            MotionEvent.ACTION_MOVE -> if (trackingSwipe && event.pointerCount == 1) {
                maxDriftX = maxOf(maxDriftX, abs(event.x - swipeStartX))
                maxDriftY = maxOf(maxDriftY, abs(event.y - swipeStartY))
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_OUTSIDE -> trackingSwipe = false
            MotionEvent.ACTION_UP -> {
                if (trackingSwipe && event.pointerCount == 1) {
                    val dx = event.x - swipeStartX
                    val dy = event.y - swipeStartY
                    val gesture = CarVideoGesturePolicy.classify(
                        dx = dx, dy = dy,
                        maxDriftX = maxOf(maxDriftX, abs(dx)),
                        maxDriftY = maxOf(maxDriftY, abs(dy)),
                        durationMs = event.eventTime - swipeStartAt,
                        density = resources.displayMetrics.density,
                        pointers = event.pointerCount,
                    )
                    when (gesture) {
                        CarVideoGesture.SWIPE_DOWN -> post { onSwipeDown?.invoke() }
                        CarVideoGesture.TAP -> post { onVideoTap?.invoke() }
                        CarVideoGesture.NONE -> Unit
                    }
                    if (gesture != CarVideoGesture.NONE) onObservedGesture?.invoke(gesture)
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
