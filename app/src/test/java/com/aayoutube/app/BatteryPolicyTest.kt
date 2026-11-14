package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Battery: 60 Hz while video plays on screen; 144p saver when the app is off screen. */
class BatteryPolicyTest {
    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun refreshCappedOnlyForPlayingFullPlayerOnWatchTab() {
        assertTrue(VideoRefreshPolicy.capped(true, true, WebHost.PlayerMode.FULL))
        assertFalse("paused", VideoRefreshPolicy.capped(true, false, WebHost.PlayerMode.FULL))
        assertFalse("settings tab", VideoRefreshPolicy.capped(false, true, WebHost.PlayerMode.FULL))
        assertFalse("mini over browsing keeps 120 Hz", VideoRefreshPolicy.capped(true, true, WebHost.PlayerMode.MINI))
        assertFalse(VideoRefreshPolicy.capped(true, true, WebHost.PlayerMode.HIDDEN))
        assertEquals(60f, VideoRefreshPolicy.preferredRate(true))
        assertEquals("0 hands the rate back to Android", 0f, VideoRefreshPolicy.preferredRate(false))
    }

    @Test fun refreshCapIsWired() {
        val main = src("app/src/main/java/com/aayoutube/app/MainActivity.kt")
        assertTrue(main.contains("lp.preferredRefreshRate = rate"))
        assertTrue(src("app/src/main/java/com/aayoutube/app/Screens.kt")
            .contains("LaunchedEffect(videoOnScreen) { activity.setVideoRefreshCap(videoOnScreen) }"))
    }

    @Test fun backgroundSaverNoLongerNeedsChromiumHiddenFlag() {
        val js = src("app/src/main/assets/aay.js")
        val f = js.substringAfter("function bgSaverActive() {").substringBefore("\n  }\n")
        assertTrue(f.contains("if (!A.isBg || A.isCar || A.inPip || A.isMini || !CFG.lowQuality"))
        assertFalse(f.contains("!realHidden()"))
        assertTrue(f.contains("if (isAd()) return false;"))
        assertTrue("live streams untouched", f.contains("if (!v || !isFinite(v.duration)) return false;"))
        assertTrue(js.contains("bgSaver.broken = true;"))
    }
}
