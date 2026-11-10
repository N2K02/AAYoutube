package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeFillPolicyTest {
    private fun on(
        landscape: Boolean = true, watchTab: Boolean = true, watchPage: Boolean = true,
        fullPlayer: Boolean = true, inPip: Boolean = false, fullscreen: Boolean = false,
        car: Boolean = false, dismissed: Boolean = false,
    ) = LandscapeFillPolicy.active(landscape, watchTab, watchPage, fullPlayer, inPip, fullscreen, car, dismissed)

    @Test fun rotatingSidewaysOnAWatchPageFillsTheScreen() {
        assertTrue(on())
    }

    @Test fun neverWhenAnotherOwnerHasThePlayer() {
        assertFalse(on(landscape = false))
        assertFalse(on(watchTab = false))
        assertFalse(on(watchPage = false))
        assertFalse(on(fullPlayer = false))
        assertFalse(on(inPip = true))
        assertFalse(on(fullscreen = true))
        assertFalse(on(car = true))
    }

    @Test fun swipeOrBackShowsTheWatchPageThenTheMiniPlayer() {
        assertFalse("dismissed landscape video shows the page and playlist", on(dismissed = true))
        val host = src("app/src/main/java/com/aayoutube/app/WebHost.kt")
        val ui = src("app/src/main/java/com/aayoutube/app/Screens.kt")
        assertTrue(host.contains("if (landscapeFill.value) dismissLandscapeFill() else enterMini()"))
        assertTrue(ui.contains("WebHost.landscapeFill.value -> WebHost.dismissLandscapeFill()"))
        assertTrue(ui.indexOf("WebHost.landscapeFill.value -> WebHost.dismissLandscapeFill()") <
            ui.indexOf("WebHost.isOnWatchPage() -> WebHost.enterMini()"))
        assertTrue(ui.contains("WebHost.landscapeDismissed.value = false"))
        // Closing YouTube's own fullscreen while sideways must not fall back into the fill layout.
        assertTrue(host.contains("private fun onFullscreenClosed()"))
        assertTrue(host.split("onFullscreenClosed()").size >= 5)
    }

    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun screenHidesBarsAndPageUsesFillLayout() {
        val ui = src("app/src/main/java/com/aayoutube/app/Screens.kt")
        val host = src("app/src/main/java/com/aayoutube/app/WebHost.kt")
        val js = src("app/src/main/assets/aay.js")
        assertTrue(ui.contains("LaunchedEffect(landFill) { WebHost.setLandscapeFill(landFill) }"))
        assertTrue(ui.contains("controller.hide(WindowInsetsCompat.Type.systemBars())"))
        assertTrue(ui.contains("!inPip && !landFill &&"))
        assertTrue(host.contains("window.__aay.land(\$on)"))
        assertTrue(host.contains("if (landscapeFill.value) view.evaluateJavascript("))
        assertTrue(js.contains("A.land = function (on)"))
        assertTrue(js.contains("!!(A.isCar || A.isLand)"))
    }
}
