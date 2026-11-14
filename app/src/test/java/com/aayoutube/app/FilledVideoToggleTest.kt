package com.aayoutube.app

import com.aayoutube.app.FilledVideoToggle.Target
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Fullscreen / minimise button sideways and on Android Auto: filled video <-> watch page. */
class FilledVideoToggleTest {
    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()
    private val js = src("app/src/main/assets/aay.js")
    private val host = src("app/src/main/java/com/aayoutube/app/WebHost.kt")

    @Test fun phoneSidewaysTogglesBetweenFilledVideoAndWatchPage() {
        assertEquals(Target.PhoneWatchPage, FilledVideoToggle.target(car = false, landscape = true, carPage = false, landscapeFill = true))
        assertEquals(Target.PhoneFilled, FilledVideoToggle.target(car = false, landscape = true, carPage = false, landscapeFill = false))
    }

    @Test fun androidAutoTogglesBetweenFilledVideoAndWatchPage() {
        assertEquals(Target.CarWatchPage, FilledVideoToggle.target(car = true, landscape = true, carPage = false, landscapeFill = false))
        assertEquals(Target.CarFilled, FilledVideoToggle.target(car = true, landscape = false, carPage = true, landscapeFill = false))
    }

    @Test fun portraitPhoneKeepsYouTubeFullscreen() {
        assertEquals(Target.None, FilledVideoToggle.target(car = false, landscape = false, carPage = false, landscapeFill = false))
    }

    @Test fun pageTakesTheButtonOnlyWhereTheAppFillsTheScreen() {
        val guard = js.substringAfter("YouTube's fullscreen / minimise button while sideways or on Android Auto.")
            .substringBefore("A.land = function (on)")
        assertTrue(guard.contains("if (A.isMini || A.inPip || !/^\\/(watch|live\\/)/.test(location.pathname)) return false;"))
        assertTrue("real Chromium fullscreen keeps YouTube's minimise",
            guard.contains("if (document.fullscreenElement || document.webkitFullscreenElement) return false;"))
        assertTrue(guard.contains("return !!A.isCar || window.innerWidth > window.innerHeight;"))
        assertTrue("YouTube never sees the press", guard.contains("e.stopImmediatePropagation();"))
        assertTrue(guard.contains("N.fsToggle()"))
        assertTrue(host.contains("fun fsToggle() {"))
        assertTrue(js.contains("var on = !!((A.isCar && !A.isCarPage) || A.isLand) && !A.isMini"))
    }

    @Test fun carWatchPageResetsWhenLeavingTheVideoOrTheCar() {
        val enter = host.substringAfter("fun enterMini() {").substringBefore("/** Undo the phone mini-player")
        assertTrue(enter.contains("setCarPage(false)"))
        assertTrue(host.contains("if (!carShown) setCarPage(false)"))
    }
}
