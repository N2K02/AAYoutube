package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class CarViewportTest {
    @Test fun visibleAreaRespectsSplitScreenAndActionStrip() {
        val area = CarViewport.contentArea(1280, 720, CarArea(50, 25, 990, 680), CarArea(0, 0, 1240, 700))
        assertEquals(CarArea(50, 25, 990, 680), area)
        assertEquals(940, area.width)
        assertEquals(655, area.height)
        assertEquals(50f to 20f, CarViewport.localTouch(100f, 45f, area))
        assertNull(CarViewport.localTouch(1100f, 400f, area))
    }

    @Test fun invalidVisibleAreaFallsBackToStableArea() {
        assertEquals(
            CarArea(20, 0, 300, 180),
            CarViewport.contentArea(400, 200, CarArea(450, 0, 450, 200), CarArea(20, -10, 300, 180))
        )
    }

    @Test fun invalidHostAreasUseWholeSurface() {
        assertEquals(
            CarArea(0, 0, 800, 480),
            CarViewport.contentArea(800, 480, CarArea(100, 100, 100, 100), null)
        )
    }

    @Test fun clippedOversizedAreasCannotDrawOutsideSurface() {
        assertEquals(
            CarArea(0, 0, 800, 480),
            CarViewport.contentArea(800, 480, CarArea(-200, -100, 1000, 900), null)
        )
    }

    @Test fun touchOutsideWindowAndAtRightBoundaryAreIgnored() {
        val area = CarArea(30, 20, 200, 110)
        assertNull(CarViewport.localTouch(29f, 21f, area))
        assertNull(CarViewport.localTouch(200f, 30f, area))
        assertEquals(0f to 0f, CarViewport.localTouch(30f, 20f, area))
    }
}
