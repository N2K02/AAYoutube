package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CarWindowPixelPolicyTest {
    @Test fun noPixelsIsUnavailable() {
        val result = CarWindowPixelPolicy.analyze(intArrayOf())
        assertEquals(0, result.total)
        assertTrue(result.assessment.contains("No window pixels"))
    }

    @Test fun nearBlackIsOnlyInconclusiveNotVideoFailure() {
        val stats = CarWindowPixelPolicy.analyze(IntArray(100) { 0xff000000.toInt() })
        assertEquals(100, stats.total)
        assertEquals(100, stats.darkPercent)
        assertEquals(0, stats.brightnessRange)
        assertTrue(stats.assessment.contains("inconclusive"))
        assertFalse(stats.assessment.contains("broken"))
    }

    @Test fun mostlyDarkSceneAndUniformLightSceneAreNotJudgedAsBroken() {
        val mostlyDark = CarWindowPixelPolicy.analyze(IntArray(100) {
            if (it < 96) 0xff030303.toInt() else 0xffffffff.toInt()
        })
        assertEquals(96, mostlyDark.darkPercent)
        assertTrue(mostlyDark.assessment.contains("inconclusive"))
        val light = CarWindowPixelPolicy.analyze(IntArray(128) { 0xffeeeeee.toInt() })
        assertEquals(0, light.darkPercent)
        assertEquals(0, light.brightnessRange)
        assertTrue(light.assessment.contains("inconclusive"))
    }

    @Test fun mixedWindowDataDoesNotClaimSuccessfulCarPaint() {
        val pixels = IntArray(100) {
            if (it % 2 == 0) 0xff111111.toInt() else 0xffffffff.toInt()
        }
        val stats = CarWindowPixelPolicy.analyze(pixels)
        assertEquals(0, stats.darkPercent)
        assertTrue(stats.brightnessRange > 200)
        assertTrue(stats.assessment.contains("not head-unit proof"))
    }

    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun manualReadbackNeverStoresPixelsAndDoesNotCreateAnotherPlayer() {
        val probe = source("app/src/main/java/com/aayoutube/app/CarWindowPixelProbe.kt")
        val car = source("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        val ui = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        assertTrue(probe.contains("PixelCopy.request("))
        assertTrue(probe.contains("Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)"))
        assertTrue(probe.contains("bitmap.recycle()"))
        assertTrue(probe.contains("getLocalVisibleRect(visible)"))
        assertTrue(probe.contains("CarWindowPixelPolicy.analyze(pixels)"))
        assertTrue(probe.contains("private const val TIMEOUT_MS"))
        assertTrue(probe.contains("ExperimentalCarVideoMonitor.state.value.owner"))
        assertTrue(car.contains("CarWindowPixelProbe.unregister(carToken)"))
        assertTrue(ui.contains("CarWindowPixelProbe.request()"))
        assertTrue(ui.contains("Check car-window pixels"))
        assertFalse(probe.contains("WebView("))
        assertFalse(probe.contains("writeBytes("))
        assertFalse(probe.contains("compress("))
        assertFalse(probe.contains("MediaSession"))
    }
}
