package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CarVideoPatternPolicyTest {
    private fun allowed(
        requested: String? = "active-car",
        active: String = "active-car",
        attached: Boolean = true,
        optedIn: Boolean = true,
        playing: Boolean = false,
        visible: Boolean = false,
    ) = CarVideoPatternPolicy.canShow(
        requested, active, attached, optedIn, playing, visible
    )

    @Test fun neverOverlaysNormalOrPlayingVideo() {
        assertTrue(allowed())
        assertFalse(allowed(optedIn = false))
        assertFalse(allowed(playing = true))
        assertFalse(allowed(visible = true))
        assertFalse(allowed(attached = false))
    }

    @Test fun oldOrMissingCarHostsCannotShowTestBars() {
        assertFalse(allowed(requested = null))
        assertFalse(allowed(requested = "older-car"))
        assertFalse(allowed(active = "new-car"))
        assertEquals(3_000L, CarVideoPatternPolicy.DURATION_MS)
    }

    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun viewIsNonDestructiveAndOnlyVisibleOnTheActiveLegacyCar() {
        val car = src("app/src/main/java/com/aayoutube/app/CarVideoPresentation.kt")
        val page = src("app/src/main/java/com/aayoutube/app/Screens.kt")
        val pattern = src("app/src/main/java/com/aayoutube/app/CarVideoPatternProbe.kt")
        val view = src("app/src/main/java/com/aayoutube/app/CarVideoPatternView.kt")
        val manifest = src("app/src/main/AndroidManifest.xml")

        assertTrue(car.contains("CarVideoPatternProbe.register(carToken)"))
        assertTrue(car.contains("CarVideoPatternProbe.unregister(carToken)"))
        assertTrue(car.contains("CarVideoPatternView()"))
        assertTrue(car.contains("carTestPattern.owner == carToken"))
        assertTrue(car.contains("activeHost == carToken && experimentalVideoEnabled"))
        assertTrue(page.contains("CarVideoPatternProbe.request()"))
        assertTrue(page.contains("!videoSnapshot.playing"))
        assertTrue(page.contains("CarVideoPatternProbe.reportSeen(true)"))
        assertTrue(page.contains("CarVideoPatternProbe.reportSeen(false)"))
        assertTrue(pattern.contains("CarVideoPatternPolicy.canShow("))
        assertTrue(pattern.contains("main.postDelayed(task, CarVideoPatternPolicy.DURATION_MS)"))
        assertTrue(pattern.contains("main.removeCallbacks(it)"))
        assertTrue(pattern.contains("fun stopForPlayback(owner: String)"))
        assertTrue(car.contains("CarVideoPatternProbe.stopForPlayback(carToken)"))
        assertTrue(view.contains("Color.Yellow"))
        assertTrue(view.contains("Color.Cyan"))
        assertTrue(view.contains("CAR DISPLAY TEST"))
        assertFalse(view.contains("AndroidView("))
        assertFalse(view.contains("WebView("))
        assertFalse(pattern.contains("WebHost.cmd("))
        assertFalse(pattern.contains("MediaSession("))
        assertFalse(manifest.contains("androidx.car.app.CarAppService"))
    }
}
