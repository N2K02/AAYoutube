package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class CarSurfacePolicyTest {
    @Test fun fullFrameFallback() {
        assertEquals(CarSurfaceBounds(0, 0, 800, 480),
            CarSurfacePolicy.viewport(800, 480, null, null))
    }
    @Test fun splitScreenAndStableAreaIntersection() {
        val area = CarSurfacePolicy.viewport(800, 480,
            CarSurfaceBounds(90, 15, 800, 455), CarSurfaceBounds(70, 40, 750, 480))
        assertEquals(CarSurfaceBounds(90, 40, 750, 455), area)
        assertTrue(area.contains(350f, 300f))
        assertFalse(area.contains(50f, 300f))
        assertFalse(area.contains(Float.NaN, 20f))
    }
    @Test fun invalidBoundsDoNotHidePlayer() {
        val invalid = CarSurfaceBounds(-100, 0, -20, 500)
        assertEquals(CarSurfaceBounds(0, 0, 800, 480),
            CarSurfacePolicy.viewport(800, 480, invalid, invalid))
        assertEquals(CarSurfaceBounds(100, 0, 800, 480),
            CarSurfacePolicy.viewport(800, 480, CarSurfaceBounds(100, 0, 1000, 1000), invalid))
    }
    @Test fun surfaceResizingAlwaysClamps() {
        assertEquals(CarSurfaceBounds(0, 0, 1, 1), CarSurfacePolicy.viewport(0, 0, null, null))
    }
}
