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
        assertTrue(screen.contains("if (!onCar && !projectedToCar && !inPip && fullscreen == null)"))
        assertTrue(screen.contains("Modifier.statusBarsPadding()"))
    }

    @Test fun nativeFullscreenAndCarRemainEdgeToEdge() {
        assertTrue(screen.contains("if (!onCar && !projectedToCar && !inPip && fullscreen == null)"))
        assertTrue(screen.contains("if (full != null)"))
        assertTrue(screen.contains("modifier = Modifier.fillMaxSize()"))
    }
}
