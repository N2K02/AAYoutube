package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevent future regressions in privileged browser entry points. */
class AdditionalWebHardeningContractTest {
    private fun read(path: String) =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun bothWebviewsRejectMixedContentAndUnsafeDirectLoads() {
        val browse = read("app/src/main/java/com/aayoutube/app/BrowseHost.kt")
        val player = read("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(browse.contains("s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW"))
        assertTrue(player.contains("internal fun isSafePageUrl(url: String?): Boolean"))
        assertTrue(player.contains("uri.userInfo == null && uri.port == -1"))
        assertTrue(player.contains("pendingUrl ?: savedUrl()"))
        assertTrue(player.contains("if (!isSafePageUrl(url))"))
        assertTrue(browse.contains("if (!WebHost.isSafePageUrl(url)) return"))
        assertTrue(browse.contains("pendingUrl?.takeIf(WebHost::isSafePageUrl)"))
        assertTrue(browse.contains("!WebHost.isSafePageUrl(url) || !isVideo(uri)"))
    }

    @Test fun browserRendererRestartsAreBounded() {
        val browse = read("app/src/main/java/com/aayoutube/app/BrowseHost.kt")
        assertTrue(browse.contains("rendererCrashCount <= 2"))
        assertTrue(browse.contains("15_000L"))
        assertTrue(browse.contains("pendingUrl = if (retry)"))
    }

    @Test fun capturedFramesAreRecycledEvenOnCancelledAnimations() {
        val screens = read("app/src/main/java/com/aayoutube/app/Screens.kt")
        assertTrue(screens.contains("ghost = null"))
        assertTrue(screens.contains("if (!frame.isRecycled) frame.recycle()"))
    }

    @Test fun bridgeCallbacksRequirePerRendererTokens() {
        val player = read("app/src/main/java/com/aayoutube/app/WebHost.kt")
        val browse = read("app/src/main/java/com/aayoutube/app/BrowseHost.kt")
        val js = read("app/src/main/assets/aay.js")
        val eq = read("app/src/main/assets/aay_eq.js")
        assertTrue(player.contains("private fun authorized(token: String?): Boolean = token == bridgeToken"))
        assertTrue(player.contains("if (!authorized(token)) return false"))
        assertTrue(player.contains("window.top===window.self"))
        assertTrue(browse.contains("if (providedToken == token) BrowseHost.openInPlayer(url)"))
        assertTrue(js.contains("N.otherMediaHold(BRIDGE_TOKEN)"))
        assertTrue(js.contains("window.__AAY_BROWSE_TOKEN"))
        assertTrue(eq.contains("window.__AAY_BRIDGE_TOKEN"))
        assertFalse(browse.contains("Ignored a request to open a non-video link: \$url"))
    }
}
