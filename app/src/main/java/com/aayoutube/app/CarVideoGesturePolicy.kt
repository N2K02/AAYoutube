package com.aayoutube.app

import kotlin.math.abs

/** Classifies observed legacy car touches without injecting or consuming input. */
internal enum class CarVideoGesture { NONE, TAP, SWIPE_DOWN }

internal object CarVideoGesturePolicy {
    fun classify(
        dx: Float,
        dy: Float,
        maxDriftX: Float,
        maxDriftY: Float,
        durationMs: Long,
        density: Float,
        pointers: Int,
    ): CarVideoGesture {
        if (pointers != 1 || durationMs < 0 || density <= 0f ||
            !dx.isFinite() || !dy.isFinite() ||
            !maxDriftX.isFinite() || !maxDriftY.isFinite() || !density.isFinite()
        ) return CarVideoGesture.NONE
        if (dy > 72f * density && abs(dx) < dy * 0.65f && durationMs < 1_100L)
            return CarVideoGesture.SWIPE_DOWN
        // A drag that returns to its starting position is not a tap.
        if (abs(dx) < 18f * density && abs(dy) < 18f * density &&
            maxDriftX < 18f * density && maxDriftY < 18f * density &&
            durationMs < 700L) return CarVideoGesture.TAP
        return CarVideoGesture.NONE
    }
}
