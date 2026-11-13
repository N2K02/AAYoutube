package com.aayoutube.app

import com.aayoutube.app.PlayerNavigationPolicy.Back
import com.aayoutube.app.PlayerNavigationPolicy.Home
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * One place that states how the player must behave, following the YouTube app,
 * on the phone and on Android Auto. Each test is one rule.
 */
class YouTubeAppBehaviourTest {
    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()
    private val ui = src("app/src/main/java/com/aayoutube/app/Screens.kt")
    private val host = src("app/src/main/java/com/aayoutube/app/WebHost.kt")
    private val car = src("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
    private val js = src("app/src/main/assets/aay.js")

    private fun back(
        equalizer: Boolean = false, fullscreen: Boolean = false, landFill: Boolean = false,
        settingsTab: Boolean = false, watchTab: Boolean = true, full: Boolean = true, watchPage: Boolean = true,
    ) = PlayerNavigationPolicy.back(equalizer, fullscreen, landFill, settingsTab, watchTab, full, watchPage)

    // ---- Home ----------------------------------------------------------------

    @Test fun homeFromAVideoShrinksItToTheMiniPlayer() {
        assertEquals(Home.Mini, PlayerNavigationPolicy.home(fullPlayer = true, miniPlayer = false, onWatchPage = true, fullscreen = false))
    }

    @Test fun homeFromFullscreenLeavesFullscreenThenOpensTheMiniPlayer() {
        assertEquals(Home.ExitFullscreenThenMini,
            PlayerNavigationPolicy.home(fullPlayer = true, miniPlayer = false, onWatchPage = true, fullscreen = true))
        val home = ui.substringAfter("val goYouTubeHome: () -> Unit = {").substringBefore("val goBack")
        assertTrue(home.indexOf("WebHost.exitFullscreen()") < home.indexOf("homeHandler.post { WebHost.enterMini() }"))
    }

    @Test fun homeWithTheMiniPlayerOpenBrowsesHomeAndKeepsPlaying() {
        assertEquals(Home.BrowseHome, PlayerNavigationPolicy.home(false, miniPlayer = true, onWatchPage = true, fullscreen = false))
    }

    @Test fun homeWithoutAVideoJustOpensHome() {
        assertEquals(Home.OpenHome, PlayerNavigationPolicy.home(true, false, onWatchPage = false, fullscreen = false))
        assertEquals(Home.OpenHome, PlayerNavigationPolicy.home(false, false, onWatchPage = false, fullscreen = false))
    }

    // ---- Back ----------------------------------------------------------------

    @Test fun backLeavesOneLayerAtATime() {
        assertEquals(Back.CloseEqualizer, back(equalizer = true, fullscreen = true, landFill = true))
        assertEquals(Back.ExitFullscreen, back(fullscreen = true, landFill = true))
        assertEquals(Back.ShowWatchPage, back(landFill = true))
        assertEquals(Back.Mini, back())
    }

    @Test fun backFromAVideoNeverStopsIt_playingOrPaused() {
        assertEquals(Back.Mini, back(full = true, watchPage = true))
        assertTrue(ui.contains("PlayerNavigationPolicy.Back.Mini -> WebHost.enterMini()"))
    }

    @Test fun backUnderTheMiniPlayerMovesThroughBrowsing() {
        assertEquals(Back.BrowseBack, back(full = false))
        assertEquals(Back.BrowseBack, back(full = false, watchPage = false))
    }

    @Test fun backOnAndroidAutoNeverLeavesTheCarScreen() {
        assertTrue(ui.contains("if (!BrowseHost.goBack() && !WebHost.carDisplay) activity.moveTaskToBack(true)"))
        assertTrue(ui.contains("if (!WebHost.goBack() && !WebHost.carDisplay) activity.moveTaskToBack(true)"))
    }

    @Test fun backFromSettingsReturnsToTheVideoTab() {
        assertEquals(Back.ToWatchTab, back(settingsTab = true, watchTab = false))
        assertEquals(Back.ToSettingsTab, back(settingsTab = false, watchTab = false))
    }

    // ---- Mini player -----------------------------------------------------------

    @Test fun swipeDownOnTheVideoOpensTheMiniPlayer_fullscreenFirstShowsThePage() {
        assertTrue(js.contains("console.log('[aay] player swipe down -> mini');"))
        assertTrue(host.contains("if (landscapeFill.value) dismissLandscapeFill() else enterMini()"))
    }

    @Test fun miniPlayerHasPlayPauseCloseProgressAndExpandsOnTap() {
        val mini = ui.substringAfter("// Mini player controls (like the YouTube app)").substringBefore("if (progress in 0..99")
        assertTrue(mini.contains("detectTapGestures(onTap = { WebHost.expandPlayer() })"))
        assertTrue(mini.contains("WebHost.cmd(if (snap.playing) \"pause\" else \"play\")"))
        assertTrue(mini.contains("onClick = { WebHost.closePlayer() }"))
        assertTrue(mini.contains("fillMaxWidth(fraction)"))
        assertTrue("draggable", mini.contains("detectDragGestures("))
    }

    @Test fun miniPlayerKeepsTheSameVideoPageAlive() {
        val enter = host.substringAfter("fun enterMini() {").substringBefore("/** Undo the phone mini-player")
        assertTrue(enter.contains("mode.value = PlayerMode.MINI"))
        assertFalse("never reloads the video", enter.contains("loadUrl") || enter.contains("openUrl"))
        assertTrue(enter.contains("BrowseHost.showFrom(home())"))
    }

    @Test fun closingTheMiniPlayerPausesTheVideo() {
        val close = host.substringAfter("fun closePlayer() {").substringBefore("\n    }")
        assertTrue(close.contains("cmd(\"pause\")"))
        assertTrue(close.contains("mode.value = PlayerMode.HIDDEN"))
    }

    // ---- Fullscreen / landscape ------------------------------------------------

    @Test fun sidewaysPhoneFillsTheScreenWithTheVideo() {
        assertTrue(LandscapeFillPolicy.active(landscape = true, watchTab = true, watchPage = true,
            fullPlayer = true, inPip = false, fullscreen = false, car = false))
        assertFalse("portrait shows the watch page", LandscapeFillPolicy.active(false, true, true, true, false, false, false))
        assertFalse("not over the mini player", LandscapeFillPolicy.active(true, true, true, fullPlayer = false, inPip = false, fullscreen = false, car = false))
    }

    @Test fun fullscreenButtonInLandscapeAndOnTheCarTogglesVideoAndWatchPage() {
        assertTrue(FullscreenTogglePolicy.intercept(car = false, landscape = true, fullPlayer = true))
        assertTrue(FullscreenTogglePolicy.intercept(car = true, landscape = false, fullPlayer = true))
        assertFalse("portrait keeps normal fullscreen", FullscreenTogglePolicy.intercept(false, false, true))
    }

    @Test fun expandingTheMiniPlayerSidewaysShowsTheWatchPage() {
        val expand = host.substringAfter("fun expandPlayer() {").substringBefore("fun closePlayer()")
        assertTrue(expand.indexOf("onFullscreenClosed()") < expand.indexOf("mode.value = PlayerMode.FULL"))
    }

    // ---- Android Auto ------------------------------------------------------------

    @Test fun carHomeAndBackShrinkTheVideoAndShowHome() {
        assertTrue(car.contains("onBack = { carBackToMini(); controlsVersion++ }"))
        assertTrue(car.contains("onHome = { carHomeToMini(); controlsVersion++ }"))
        val toMini = car.substringAfter("private fun carVideoToMini(action: String): Boolean {").substringBefore("private fun finishCarMiniTransition")
        assertTrue("fullscreen is left first", toMini.indexOf("WebHost.exitFullscreen()") < toMini.indexOf("finishCarMiniTransition(action)"))
    }

    @Test fun carWatchPageResetsWhenLeavingTheVideo() {
        val enter = host.substringAfter("fun enterMini() {").substringBefore("/** Undo the phone mini-player")
        assertTrue(enter.contains("setCarPage(false)"))
        assertTrue(host.contains("if (!carShown) setCarPage(false)"))
    }

    // ---- Picture in picture / background ----------------------------------------

    @Test fun pictureInPictureShowsOnlyTheVideoFullSize() {
        assertTrue(ui.contains("inPip -> Modifier.fillMaxSize()"))
        assertTrue(ui.contains("val small = mode == WebHost.PlayerMode.MINI && !inPip"))
        assertTrue(js.contains("document.documentElement.classList.toggle('aay-pip', !!A.inPip);"))
    }

    @Test fun videoElementIsNeverHiddenToRefreshIt() {
        // Build 726 regression: hiding the <video> blacked out mini player and PiP.
        assertFalse(js.contains("rebuildVideoLayer"))
        assertFalse(js.contains("setProperty('display', 'none', 'important');\n    if (typeof requestAnimationFrame"))
    }
}
