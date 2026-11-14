package com.aayoutube.app

import androidx.compose.ui.Alignment
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Landscape (phone or Android Auto video): controls always top centre, side setting disabled. */
class ControlsLandscapePolicyTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun portraitHonoursSideSetting() {
        assertEquals(Alignment.CenterStart, ControlsSidePolicy.alignment(left = true, landscape = false))
        assertEquals(Alignment.CenterEnd, ControlsSidePolicy.alignment(left = false, landscape = false))
        assertTrue(ControlsSidePolicy.vertical(landscape = false))
        assertTrue(ControlsSidePolicy.sideSettingEnabled(landscape = false))
    }

    @Test fun landscapeIsAlwaysTopCentreHorizontal() {
        assertEquals(Alignment.TopCenter, ControlsSidePolicy.alignment(left = true, landscape = true))
        assertEquals(Alignment.TopCenter, ControlsSidePolicy.alignment(left = false, landscape = true))
        assertFalse(ControlsSidePolicy.vertical(landscape = true))
        assertFalse(ControlsSidePolicy.sideSettingEnabled(landscape = true))
    }

    @Test fun defaultAlignmentStaysPortrait() {
        assertEquals(Alignment.CenterEnd, ControlsSidePolicy.alignment(false))
    }

    @Test fun landscapeDetectionFromScreenShape() {
        assertTrue(ControlsSidePolicy.isLandscape(800f, 480f))
        assertFalse(ControlsSidePolicy.isLandscape(480f, 800f))
        assertFalse(ControlsSidePolicy.isLandscape(600f, 600f))
    }

    @Test fun phoneAndCarWireTheLandscapePolicy() {
        val phone = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        val car = source("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        assertTrue(phone.contains("vertical = ControlsSidePolicy.vertical(phoneLandscape)"))
        assertTrue(car.contains("vertical = ControlsSidePolicy.vertical(carLandscape)"))
        assertTrue(car.contains("ControlsSidePolicy.isLandscape(maxWidth.value, maxHeight.value)"))
        assertTrue(phone.contains("enabled = sideEnabled,"))
        // The saved side is never overwritten by rotating.
        assertEquals(1, Regex("copy\\(controlsOnLeft =").findAll(phone).count())
    }
}
