package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CarVirtualDisplayPixelPolicyTest {
    @Test fun expectedSyntheticFrameColorHasSaneTolerance() {
        assertTrue(CarVirtualDisplayPixelPolicy.isExpected(26, 143, 224))
        assertTrue(CarVirtualDisplayPixelPolicy.isExpected(32, 139, 220))
        assertFalse(CarVirtualDisplayPixelPolicy.isExpected(0, 0, 0))
        assertFalse(CarVirtualDisplayPixelPolicy.isExpected(255, 255, 255))
        assertFalse(CarVirtualDisplayPixelPolicy.isExpected(26, 30, 224))
    }

    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun testDoesNotAddSecondWebViewOrMediaSession() {
        val probe = src("app/src/main/java/com/aayoutube/app/CarVirtualDisplayProbe.kt")
        val page = src("app/src/main/java/com/aayoutube/app/Screens.kt")
        val manifest = src("app/src/main/AndroidManifest.xml")
        assertTrue(probe.contains("createVirtualDisplay("))
        assertTrue(probe.contains("ImageReader.newInstance("))
        assertTrue(probe.contains("CarVirtualDisplayPixelPolicy.isExpected("))
        assertTrue(probe.contains("probe.display.release()"))
        assertTrue(probe.contains("probe.reader.close()"))
        assertTrue(probe.contains("probe.presentation.dismiss()"))
        assertTrue(page.contains("CarVirtualDisplayProbe.start(context)"))
        assertTrue(page.contains("flags.experimentalVideo && !diagnostics.active"))
        assertTrue(page.contains("CarVirtualDisplayProbe.cancel()"))
        assertFalse(probe.contains("WebView("))
        assertFalse(probe.contains("WebHost."))
        assertFalse(probe.contains("MediaSession"))
        assertFalse(probe.contains("Shizuku"))
        assertFalse(manifest.contains("androidx.car.app.CarAppService"))
    }
}
