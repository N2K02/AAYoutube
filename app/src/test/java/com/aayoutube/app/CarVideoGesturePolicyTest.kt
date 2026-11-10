package com.aayoutube.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CarVideoGesturePolicyTest {
    private fun gesture(
        dx: Float = 0f, dy: Float = 0f,
        maxX: Float = kotlin.math.abs(dx), maxY: Float = kotlin.math.abs(dy),
        ms: Long = 120L, density: Float = 1f, fingers: Int = 1,
    ) = CarVideoGesturePolicy.classify(dx, dy, maxX, maxY, ms, density, fingers)

    @Test fun recognizesShortTapsAndDeliberateDownwardSwipes() {
        assertEquals(CarVideoGesture.TAP, gesture(dx = 4f, dy = 3f))
        assertEquals(CarVideoGesture.SWIPE_DOWN, gesture(dy = 120f, ms = 450))
        assertEquals(CarVideoGesture.NONE, gesture(dy = -120f))
        assertEquals(CarVideoGesture.NONE, gesture(dx = 120f, dy = 120f))
    }

    @Test fun cancelsReturnDragsAndLongHolds() {
        assertEquals(CarVideoGesture.NONE, gesture(dx = 0f, dy = 0f, maxX = 60f))
        assertEquals(CarVideoGesture.NONE, gesture(dx = 0f, dy = 0f, maxY = 60f))
        assertEquals(CarVideoGesture.NONE, gesture(ms = 900))
        assertEquals(CarVideoGesture.NONE, gesture(dy = 120f, ms = 1_200))
    }

    @Test fun handlesDisplayDensityAndMultiPointerSafely() {
        assertEquals(CarVideoGesture.NONE, gesture(dy = 80f, density = 2f))
        assertEquals(CarVideoGesture.SWIPE_DOWN, gesture(dy = 150f, density = 2f))
        assertEquals(CarVideoGesture.NONE, gesture(fingers = 2))
        assertEquals(CarVideoGesture.NONE, gesture(ms = -1))
        assertEquals(CarVideoGesture.NONE, gesture(density = 0f))
        assertEquals(CarVideoGesture.NONE, gesture(dx = Float.NaN))
    }
}
