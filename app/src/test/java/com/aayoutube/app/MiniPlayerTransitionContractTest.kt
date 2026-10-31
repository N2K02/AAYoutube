package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Protect the real decoder viewport used on Android and its PiP handoff. */
class MiniPlayerTransitionContractTest {
    private fun read(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun miniUsesRealWebViewSizeAndNoGraphicsLayerVideoScaling() {
        val pane = read("app/src/main/java/com/aayoutube/app/Screens.kt")
            .substringAfter("fun WebPane(").substringBefore("// ---------------------------------------------------------------------------- settings")
        assertTrue(pane.contains("small -> Modifier.offset {"))
        assertTrue(pane.contains("}.size(miniW, miniH).clip(RoundedCornerShape(12.dp))"))
        assertTrue(pane.contains("inPip -> Modifier.fillMaxSize()"))
        assertTrue(pane.contains("key(version)"))
        assertFalse(pane.contains("scaleX = 1f + (miniScaleX"))
        assertFalse(pane.contains("scaleY = 1f + (miniScaleY"))
        assertFalse(pane.contains("animateFloatAsState("))
        assertTrue(pane.contains("if (browseOn && !inPip)"))
        assertTrue(pane.contains("if (mode == WebHost.PlayerMode.FULL || inPip) BrowseHost.pause()"))
    }

    @Test fun pipUsesNativeVideoSourceAndDisablesWebViewUnsafeSeamlessResize() {
        val activity = read("app/src/main/java/com/aayoutube/app/MainActivity.kt")
        assertTrue(activity.contains("builder.setSeamlessResizeEnabled(false)"))
        assertTrue(activity.contains("builder.setSourceRectHint(bounds)"))
        assertTrue(activity.contains("setAspectRatio(Rational(16, 9))"))
        assertTrue(activity.contains("watchTabVisible"))
        assertTrue(activity.contains("if (!entered) WebHost.pip(false)"))
        assertTrue(activity.contains("WebHost.pip(false) // Clear an interrupted PiP entry"))
        val web = read("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("w.getGlobalVisibleRect(out)"))
        assertTrue(web.contains("!shown && !inPip.value"))
    }

    @Test fun pageDisplaysVideoOnlyWithoutHidingItsAcceleratedAncestors() {
        val js = read("app/src/main/assets/aay.js")
        assertTrue(js.contains("html.aay-video-only video.html5-main-video"))
        assertTrue(js.contains("object-fit:contain!important"))
        assertTrue(js.contains("html.aay-video-only ytm-player"))
        assertFalse(js.contains("html.aay-pip body *{visibility:hidden!important}"))
        assertTrue(js.contains("!A.inPip && !A.isMini && CFG.lowQuality"))
        assertTrue(js.contains("CSS_UNMUTE"))
        val unmute = js.substringAfter("/* ---------- 1d.").substringBefore("/* ---------- 1f.")
        assertTrue(unmute.contains("CSS. Never click it"))
        assertFalse(unmute.contains("b.click()"))
        // SponsorBlock/ad skip buttons are unrelated and remain functional.
        assertTrue(js.contains("if (b) { try { b.click(); } catch (e) {} }"))
    }
}
