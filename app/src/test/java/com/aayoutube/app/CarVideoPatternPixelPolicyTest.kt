package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CarVideoPatternPixelPolicyTest {
    private val bars = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(),
        0xFF00FF00.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt(),
        0xFF0000FF.toInt(),
    )

    private fun image(
        width: Int = 96,
        height: Int = 54,
        override: ((Int, Int) -> Int)? = null,
    ): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        override?.invoke(x, y) ?: bars[(x * 7 / width).coerceIn(0, 6)]
    }

    @Test fun recognizesSyntheticComposeColorBars() {
        val result = CarVideoPatternPixelPolicy.analyze(image(), 96, 54)
        assertEquals(21, result.samples)
        assertEquals(21, result.matched)
        assertEquals(100, result.matchPercent)
        assertTrue(result.assessment.contains("detected"))
        assertTrue(result.assessment.contains("not head-unit proof"))
    }

    @Test fun nearCorrectColorsPassButBlackAndNeutralWindowsAreInconclusive() {
        val tinted = image(override = { x, _ ->
            val color = bars[(x * 7 / 96).coerceIn(0, 6)]
            // Small compositor/color conversion differences are expected.
            val red = ((color ushr 16) and 255).coerceIn(18, 237)
            val green = ((color ushr 8) and 255).coerceIn(18, 237)
            val blue = (color and 255).coerceIn(18, 237)
            (255 shl 24) or (red shl 16) or (green shl 8) or blue
        })
        assertEquals(21, CarVideoPatternPixelPolicy.analyze(tinted, 96, 54).matched)
        val dark = CarVideoPatternPixelPolicy.analyze(
            IntArray(96 * 54) { 0xff000000.toInt() }, 96, 54)
        assertTrue(dark.matched <= 3)
        assertTrue(dark.assessment.contains("inconclusive"))
        val white = CarVideoPatternPixelPolicy.analyze(
            IntArray(96 * 54) { 0xffffffff.toInt() }, 96, 54)
        assertTrue(white.assessment.contains("inconclusive"))
    }

    @Test fun partiallyBlockedBarsNeverImplyPhysicalHeadUnitIsWorking() {
        val obstructed = image(override = { x, y ->
            if (x < 70) 0xff000000.toInt()
            else bars[(x * 7 / 96).coerceIn(0, 6)]
        })
        val result = CarVideoPatternPixelPolicy.analyze(obstructed, 96, 54)
        assertTrue(result.matched < 17)
        assertTrue(result.assessment.contains("inconclusive"))
    }

    @Test fun refusesInvalidSamples() {
        assertEquals(0, CarVideoPatternPixelPolicy.analyze(intArrayOf(), 96, 54).samples)
        assertEquals(0, CarVideoPatternPixelPolicy.analyze(intArrayOf(), 0, 0).samples)
        assertEquals(0, CarVideoPatternPixelPolicy.analyze(image(), 12, 54).samples)
    }

    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun readbackOnlyChecksSyntheticPatternAndDiscardsImage() {
        val probe = src("app/src/main/java/com/aayoutube/app/CarVideoPatternReadback.kt")
        val pattern = src("app/src/main/java/com/aayoutube/app/CarVideoPatternProbe.kt")
        val car = src("app/src/main/java/com/aayoutube/app/CarVideoPresentation.kt")
        val ui = src("app/src/main/java/com/aayoutube/app/Screens.kt")
        assertTrue(probe.contains("PixelCopy.request("))
        assertTrue(probe.contains("Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)"))
        assertTrue(probe.contains("bitmap.recycle()"))
        assertTrue(probe.contains("private var pending: Request? = null"))
        assertTrue(probe.contains("CarVideoPatternPixelPolicy.analyze("))
        assertTrue(pattern.contains("current.testId != testId"))
        assertTrue(car.contains("CarVideoPatternReadback.capture("))
        assertTrue(car.contains("delay(450L)"))
        assertTrue(ui.contains("carPattern.windowReadback"))
        assertTrue(ui.contains("Visible"))
        assertTrue(ui.contains("Not visible"))
        assertFalse(probe.contains("WebView("))
        assertFalse(probe.contains("WebHost.cmd("))
        assertFalse(probe.contains("writeBytes("))
        assertFalse(probe.contains("compress("))
    }
}
