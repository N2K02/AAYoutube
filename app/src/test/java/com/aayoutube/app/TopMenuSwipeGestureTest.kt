package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TopMenuSwipeGestureTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun onlyDownwardThresholdGestureRestoresHiddenMenu() {
        assertFalse(TopMenuSwipePolicy.shouldReveal(0f, 24f))
        assertFalse(TopMenuSwipePolicy.shouldReveal(12f, 24f))
        assertFalse(TopMenuSwipePolicy.shouldReveal(-40f, 24f))
        assertFalse(TopMenuSwipePolicy.shouldReveal(Float.NaN, 24f))
        assertFalse(TopMenuSwipePolicy.shouldReveal(24f, 0f))
        assertTrue(TopMenuSwipePolicy.shouldReveal(24f, 24f))
        assertTrue(TopMenuSwipePolicy.shouldReveal(80f, 24f))
    }

    @Test fun gestureOnlyAttachesToHiddenTopBorderOnBothScreens() {
        val gesture = source("app/src/main/java/com/aayoutube/app/TopMenuSwipeGesture.kt")
        val phone = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        val car = source("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        assertTrue(gesture.contains("detectVerticalDragGestures("))
        assertTrue(gesture.contains("TopMenuSwipePolicy.shouldReveal(down, 24.dp.toPx())"))
        assertTrue(gesture.contains("change.consume()"))
        assertTrue(phone.contains("if (!topMenuVisible) {"))
        assertTrue(car.contains("if (!controlsVisible) {"))
        assertTrue(phone.contains(".revealTopMenuOnSwipeDown {"))
        assertTrue(car.contains(".revealTopMenuOnSwipeDown {"))
        assertFalse(phone.contains(".clickable {\n                            topMenuVisible = true"))
        assertFalse(car.contains(".clickable { controlsVisible = true"))
    }

    @Test fun autoHideSettingsRemainConfigurable() {
        val feat = source("app/src/main/java/com/aayoutube/app/Features.kt")
        assertTrue(feat.contains("topMenuTimeoutSeconds"))
        assertTrue(feat.contains("prefs.getInt(\"topMenuTimeoutSeconds\""))
        assertTrue(feat.contains(".putInt(\"topMenuTimeoutSeconds\""))
        assertEquals(3000L, TopMenuPolicy.timeoutMillis(3))
        assertNull(TopMenuPolicy.timeoutMillis(0))
    }
}
