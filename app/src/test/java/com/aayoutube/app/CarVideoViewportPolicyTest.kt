package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class CarVideoViewportPolicyTest {
    @Test fun fitsCommonHeadUnitSizesWithoutStretching() {
        assertEquals(CarVideoBounds(0, 0, 1280, 720),
            CarVideoViewportPolicy.fit16by9(1280, 720))
        assertEquals(CarVideoBounds(0, 15, 800, 450),
            CarVideoViewportPolicy.fit16by9(800, 480))
        assertEquals(CarVideoBounds(0, 12, 1024, 576),
            CarVideoViewportPolicy.fit16by9(1024, 600))
    }

    @Test fun respectsCarViewportSafeMargins() {
        assertEquals(CarVideoBounds(36, 20, 1208, 680),
            CarVideoViewportPolicy.fit16by9(1280, 720, 10, 20, 10, 20))
        val result = CarVideoViewportPolicy.fit16by9(1920, 1080, 40, 25, 50, 35)
        assertTrue(result.left >= 40)
        assertTrue(result.right <= 1870)
        assertTrue(result.top >= 25)
        assertTrue(result.bottom <= 1045)
    }

    @Test fun invalidSizesNeverProduceNegativeOrOverflowingRectangles() {
        assertEquals(CarVideoBounds(0, 0, 0, 0),
            CarVideoViewportPolicy.fit16by9(-5, 100))
        assertEquals(CarVideoBounds(10, 0, 0, 0),
            CarVideoViewportPolicy.fit16by9(5, 10, insetLeft = 10))
        assertEquals(0, CarVideoViewportPolicy.fit16by9(Int.MAX_VALUE, 0).width)
    }

    @Test fun previewProfilesAreAllLandscape() {
        assertEquals(4, CarVideoViewportPolicy.presets.size)
        assertTrue(CarVideoViewportPolicy.presets.all { it.width > it.height && it.height > 0 })
    }
}
