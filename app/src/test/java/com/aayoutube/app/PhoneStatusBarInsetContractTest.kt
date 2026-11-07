package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protect the phone YouTube header from system-bar overlap. */
class PhoneStatusBarInsetContractTest {
    private val screen by lazy {
        listOf(
            File("app/src/main/java/com/aayoutube/app/Screens.kt"),
            File("../app/src/main/java/com/aayoutube/app/Screens.kt"),
        ).first { it.isFile }.readText()
    }

    @Test fun phoneWebViewUsesMRadioStatusBarSafeArea() {
        assertTrue(screen.contains("val phoneSafeArea = !onCar && !projectedToCar && !inPip &&"))
        assertTrue(screen.contains("Box(modifier.then(if (phoneSafeArea) Modifier.statusBarsPadding() else Modifier))"))
    }

    @Test fun nativeFullscreenAndCarRemainEdgeToEdge() {
        assertTrue(screen.contains("val phoneSafeArea = !onCar && !projectedToCar && !inPip &&"))
        assertTrue(screen.contains("if (full != null)"))
        assertTrue(screen.contains("modifier = Modifier.fillMaxSize()"))
    }
}
