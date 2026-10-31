package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Floating side rail shared by phone and Android Auto video. */
class TopMenuSwipeGestureTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun anyTouchShowsControlsWithoutStealingIt() {
        val gesture = source("app/src/main/java/com/aayoutube/app/TopMenuSwipeGesture.kt")
        assertTrue(gesture.contains("awaitPointerEvent(PointerEventPass.Initial)"))
        assertTrue(gesture.contains("if (event.type == PointerEventType.Press) onTouch()"))
        assertFalse("the touch must still reach YouTube", gesture.contains("consume()"))
        val phone = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        val car = source("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        assertTrue(phone.contains("Modifier.revealControlsOnTouch {"))
        assertTrue(car.contains(".revealControlsOnTouch { controlsVisible = true; controlsVersion++ }"))
        assertFalse(phone.contains("revealTopMenuOnSwipeDown"))
        assertFalse(car.contains("revealTopMenuOnSwipeDown"))
    }

    @Test fun oneSideSettingForPhoneAndCarDefaultsRight() {
        assertFalse(FeatureFlags().controlsOnLeft)
        assertEquals("Right", ControlsSidePolicy.label(false))
        assertEquals("Left", ControlsSidePolicy.label(true))
        val features = source("app/src/main/java/com/aayoutube/app/Features.kt")
        assertTrue(features.contains("prefs.getBoolean(\"controlsOnLeft\", d.controlsOnLeft)"))
        assertTrue(features.contains(".putBoolean(\"controlsOnLeft\", n.controlsOnLeft)"))
        val phone = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        val car = source("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        assertTrue(phone.contains("Modifier.align(ControlsSidePolicy.alignment(flags.controlsOnLeft))"))
        assertTrue(car.contains("Modifier.align(ControlsSidePolicy.alignment(flags.controlsOnLeft))"))
        assertTrue(phone.contains("Text(\"Video controls side\""))
        assertTrue(phone.contains("Features.update { it.copy(controlsOnLeft = left) }"))
    }

    @Test fun miniPlayerNeverEntersTheRailStrip() {
        val edge = 12f; val rail = 64f; val width = 400f; val mini = 200f
        // Rail on the right (default): mini player stops before the rail strip.
        assertEquals(edge, MiniPlayerBounds.minX(edge, rail, railOnLeft = false))
        val maxRight = MiniPlayerBounds.maxX(width, mini, edge, rail, railOnLeft = false)
        assertEquals(width - mini - edge - rail, maxRight)
        assertTrue(maxRight + mini <= width - rail)
        // Rail on the left: mini player starts after the rail strip.
        assertEquals(edge + rail, MiniPlayerBounds.minX(edge, rail, railOnLeft = true))
        assertEquals(width - mini - edge, MiniPlayerBounds.maxX(width, mini, edge, rail, railOnLeft = true))
        // Too narrow: never inverted bounds.
        assertTrue(MiniPlayerBounds.maxX(100f, 90f, edge, rail, false) >= MiniPlayerBounds.minX(edge, rail, false))
        val phone = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        assertTrue(phone.contains("val minX = MiniPlayerBounds.minX(edge, rail, railOnLeft)"))
        assertTrue(phone.contains("MiniPlayerBounds.maxX(constraints.maxWidth.toFloat(), wPx, edge, rail, railOnLeft)"))
    }

    @Test fun autoHideSettingsRemainConfigurable() {
        val feat = source("app/src/main/java/com/aayoutube/app/Features.kt")
        assertTrue(feat.contains("topMenuTimeoutSeconds"))
        assertTrue(feat.contains("prefs.getInt(\"topMenuTimeoutSeconds\""))
        assertTrue(feat.contains(".putInt(\"topMenuTimeoutSeconds\""))
    }
}
