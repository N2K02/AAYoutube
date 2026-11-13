package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard Android Auto navigation without changing the phone player's behavior. */
class CarVideoNavigationContractTest {
    private val source: String by lazy {
        listOf(
            File("app/src/main/java/com/aayoutube/app/CarVideoPresentation.kt"),
            File("../app/src/main/java/com/aayoutube/app/CarVideoPresentation.kt"),
        ).first { it.isFile }.readText()
    }

    @Test fun carHomeBackAndSwipeShareOneMiniTransition() {
        assertTrue(source.contains("private fun carVideoToMini(action: String): Boolean"))
        assertTrue(source.contains("carVideoToMini(\"Home\")"))
        assertTrue(source.contains("carVideoToMini(\"Back\")"))
        assertTrue(source.contains("carVideoToMini(\"Swipe\")"))
    }

    @Test fun nativeFullscreenExitsBeforeMiniAndKeepsWebview() {
        assertTrue(source.contains("WebHost.exitFullscreen()"))
        assertTrue(source.contains("handler.post {"))
        assertTrue(source.contains("finishCarMiniTransition(action)"))
        assertTrue(source.contains("WebHost.enterMini()"))
        assertTrue(source.contains("WebHost.mode.value == WebHost.PlayerMode.MINI"))
    }

    @Test fun homeNavigatesBrowseWebviewWithoutReplacingPlayingVideo() {
        assertTrue(source.contains("BrowseHost.resume()"))
        assertTrue(source.contains("BrowseHost.navigate(WebHost.home())"))
        assertTrue(source.contains("if (!WebHost.isActiveCarHost(carToken))"))
    }
}
