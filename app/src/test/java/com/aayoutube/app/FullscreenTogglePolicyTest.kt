package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Fullscreen button in landscape / Android Auto: filled video <-> watch page, no second fullscreen. */
class FullscreenTogglePolicyTest {
    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun interceptOnlyWhereOurLayoutFills() {
        assertTrue(FullscreenTogglePolicy.intercept(car = true, landscape = false, fullPlayer = true))
        assertTrue(FullscreenTogglePolicy.intercept(car = false, landscape = true, fullPlayer = true))
        assertFalse("portrait phone keeps normal fullscreen", FullscreenTogglePolicy.intercept(false, false, true))
        assertFalse("mini player", FullscreenTogglePolicy.intercept(true, true, false))
    }

    @Test fun echoWindow() {
        assertTrue(FullscreenTogglePolicy.isEcho(200L))
        assertFalse(FullscreenTogglePolicy.isEcho(FullscreenTogglePolicy.ECHO_MS + 1))
        assertFalse(FullscreenTogglePolicy.isEcho(-5L))
    }

    @Test fun hostTogglesAndPageHonoursCarPage() {
        val host = src("app/src/main/java/com/aayoutube/app/WebHost.kt")
        val show = host.substringAfter("override fun onShowCustomView(").substringBefore("override fun onHideCustomView")
        assertTrue(show.contains("if (carShown) setCarPage(!carPage.value)"))
        assertTrue(show.contains("else landscapeDismissed.value = !landscapeDismissed.value"))
        assertTrue(show.indexOf("return") < show.indexOf("fullscreenView.value = view"))
        assertTrue("car page resets when the car leaves", host.contains("if (!carShown) setCarPage(false)"))
        val js = src("app/src/main/assets/aay.js")
        assertTrue(js.contains("var on = !!((A.isCar && !A.isCarPage) || A.isLand) && !A.isMini"))
        assertTrue(js.contains("A.carPage = function (on)"))
    }
}
