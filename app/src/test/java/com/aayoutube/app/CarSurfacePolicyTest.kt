package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CarSurfacePolicyTest {
    @Test fun acceptsHeadUnitGeometryAndDefaultsMissingDensity() {
        assertEquals(CarSurfacePolicy.Geometry(1920, 720, 240), CarSurfacePolicy.geometry(1920, 720, 240))
        assertEquals(160, CarSurfacePolicy.geometry(800, 480, 0)?.dpi)
        assertEquals(160, CarSurfacePolicy.geometry(800, 480, Int.MAX_VALUE)?.dpi)
    }
    @Test fun rejectsInvalidOrExcessiveAllocationsWithoutIntegerOverflow() {
        for ((w, h) in listOf(0 to 480, 800 to -1, 8193 to 1,
                Int.MAX_VALUE to Int.MAX_VALUE, 8192 to 8192)) {
            assertNull(CarSurfacePolicy.geometry(w, h, 160))
        }
        assertNotNull(CarSurfacePolicy.geometry(4096, 4096, 160))
    }
    @Test fun clampsInputAndRejectsEventsWithoutUsableSurface() {
        assertEquals(0f to 479f, CarSurfacePolicy.point(-10f, 500f, 800, 480))
        assertEquals(10f to 20f, CarSurfacePolicy.point(10f, 20f, 800, 480))
        assertNull(CarSurfacePolicy.point(Float.NaN, 0f, 800, 480))
        assertNull(CarSurfacePolicy.point(0f, Float.POSITIVE_INFINITY, 800, 480))
        assertNull(CarSurfacePolicy.point(0f, 0f, 0, 480))
    }
    @Test fun serviceOwnsOneDisplayAndDisposesCallbacksAndPresentation() {
        val code = listOf(File("src/main/java/com/aayoutube/app/CarVideoService.kt"),
            File("app/src/main/java/com/aayoutube/app/CarVideoService.kt")).first { it.isFile }.readText()
        assertTrue(code.contains("appManager.setSurfaceCallback(null)"))
        assertTrue(code.contains("presentation?.release()"))
        assertTrue(code.contains("display?.release()"))
        assertTrue(code.contains("surface?.takeUnless { it === keep }?.release()"))
        assertTrue(code.contains("if (container.surface === surface)"))
        assertTrue(code.contains("event.recycle()"))
        assertFalse(code.contains("ALLOW_ALL_HOSTS_VALIDATOR"))
        assertFalse(code.contains("MediaProjection"))
        assertFalse(code.contains("Bitmap"))
    }
}
