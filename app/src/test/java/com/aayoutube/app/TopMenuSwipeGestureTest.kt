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
        assertTrue(phone.contains("Modifier.align(ControlsSidePolicy.alignment(flags.controlsOnLeft, phoneLandscape))"))
        assertTrue(car.contains("Modifier.align(ControlsSidePolicy.alignment(flags.controlsOnLeft, carLandscape))"))
        assertTrue(phone.contains("Text(\"Video controls side\""))
        assertTrue(phone.contains("Features.update { it.copy(controlsOnLeft = left) }"))
    }

    @Test fun miniPlayerCanUseTheSpaceOutsideTheActualControlsRail() {
        val phone = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        val car = source("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        // Do not reserve a whole dead strip along the menu side.
        assertTrue(phone.contains("val minX = edge"))
        assertTrue(phone.contains("(constraints.maxWidth.toFloat() - wPx - edge)"))
        assertTrue(car.contains("val minX = 0f"))
        assertTrue(car.contains("val minY = 0f"))
        assertTrue(car.contains("(maxWidth - miniWidth).toPx()"))
        assertTrue(car.contains("(maxHeight - miniHeight).toPx()"))
    }

    @Test fun autoHideSettingsRemainConfigurable() {
        val feat = source("app/src/main/java/com/aayoutube/app/Features.kt")
        assertTrue(feat.contains("topMenuTimeoutSeconds"))
        assertTrue(feat.contains("prefs.getInt(\"topMenuTimeoutSeconds\""))
        assertTrue(feat.contains(".putInt(\"topMenuTimeoutSeconds\""))
    }
}
