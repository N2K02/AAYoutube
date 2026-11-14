package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Landscape: no audio gap entering fullscreen; collapsed playlist expanded on later videos. */
class LandscapeTransitionContractTest {
    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()
    private val js = src("app/src/main/assets/aay.js")
    private val host = src("app/src/main/java/com/aayoutube/app/WebHost.kt")

    @Test fun transitWindowDropsInvoluntaryPausesOnly() {
        assertTrue(js.contains("function holding() { return inTransit() ||"))
        assertTrue(js.contains("A.transit = function (ms)"))
        // A real Pause tap during the window is still honoured.
        assertTrue(js.contains("var manual = !allowPause && Date.now() <= userPauseEligibleUntil;"))
        assertTrue(js.contains("if (!allowPause && !manual && holding() && !this.ended)"))
    }

    @Test fun everyLayoutTransitionArmsTransit() {
        val show = host.substringAfter("override fun onShowCustomView(").substringBefore("override fun onHideCustomView")
        val hide = host.substringAfter("override fun onHideCustomView()").substringBefore("// No grey play-button")
        val fill = host.substringAfter("fun setLandscapeFill(on: Boolean) {").substringBefore("fun enterMini()")
        val dismiss = host.substringAfter("fun dismissLandscapeFill() {").substringBefore("fun transit()")
        val expand = host.substringAfter("fun expandPlayer() {").substringBefore("fun closePlayer()")
        for ((name, body) in listOf("show" to show, "hide" to hide, "fill" to fill, "dismiss" to dismiss, "expand" to expand))
            assertTrue("transit() armed on $name", body.contains("transit()"))
        assertTrue(show.indexOf("transit()") < show.indexOf("fullscreenView.value = view"))
        assertTrue(host.contains("window.__aay.transit(1500)"))
    }

    @Test fun collapsedPlaylistExpandedOnlyOnLandscapeWatchPage() {
        val f = js.substringAfter("function landPlaylist() {").substringBefore("\n  }\n")
        assertTrue(f.contains("if (BROWSE || A.isLand || A.isMini || A.inPip || A.isCar) return;"))
        assertTrue(f.contains("innerWidth <= innerHeight) return;"))
        assertTrue("playlists / Mix only", f.contains("/[?&]list=/.test(location.search)"))
        assertTrue("bounded retries per video", f.contains("if (landPlaylistTries >= 4) return;"))
        assertTrue("an already open playlist is left alone", f.contains("if (!collapsed) { landPlaylistTries = 4; return; }"))
        assertTrue(js.contains("      landPlaylist();\n"))
    }
}
