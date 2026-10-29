package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keep both AABrowser-inspired Android Auto entries and car lifecycle hardening in place. */
class AndroidAutoIntegrationContractTest {
    private fun source(relative: String): String =
        listOf(File(relative), File("../$relative"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")

    @Test fun mediaBrowserAndCarVideoEntriesCoexist() {
        val xml = source("app/src/main/AndroidManifest.xml")
        assertTrue(xml.contains("android.media.browse.MediaBrowserService"))
        assertTrue(xml.contains("androidx.media3.session.MediaLibraryService"))
        assertTrue(xml.contains("android.intent.category.CAR_LAUNCHER"))
        assertTrue(xml.contains("androidx.car.app.CarAppService"))
        assertTrue(xml.contains("MEDIA_PLAY_FROM_SEARCH"))
        val legacy = source("app/src/main/res/values/bools.xml")
        val modern = source("app/src/main/res/values-v35/bools.xml")
        assertTrue(legacy.contains(">true</bool>"))
        assertTrue(modern.contains(">false</bool>"))
    }

    @Test fun carScreenKeepsSurfaceAndRestrictsUntrustedHosts() {
        val kotlin = source("app/src/main/java/com/aayoutube/app/CarVideoService.kt")
        assertTrue(kotlin.contains("if (BuildConfig.DEBUG) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR"))
        assertTrue(kotlin.contains("hosts_allowlist_sample"))
        assertTrue(kotlin.contains("override fun onVisibleAreaChanged"))
        assertTrue(kotlin.contains("override fun onStableAreaChanged"))
        assertTrue(kotlin.contains("existing.surface = surface"))
        assertTrue(kotlin.contains("Surface detached; player preserved"))
        assertTrue(kotlin.contains("connectMedia()"))
        val surfaceLoss = kotlin.substringAfter("override fun onSurfaceDestroyed")
            .substringBefore("override fun onVisibleAreaChanged")
        assertFalse("AA surface replacement must never destroy the player", surfaceLoss.contains("stop()"))
    }

    @Test fun carPageHasMediaSessionFallbackAndTouchSeeking() {
        val js = source("app/src/main/assets/aay.js")
        assertTrue(js.contains("Object.defineProperty(navigator, 'mediaSession'"))
        assertTrue(js.contains("window.MediaMetadata = function"))
        assertTrue(js.contains("AABrowser-style tap-to-seek"))
        assertTrue(js.contains("A.isCar &&"))
        assertTrue(js.contains("v.currentTime = fraction * v.duration"))
    }

    @Test fun serviceDisconnectNeverRestartsUserPausedVideo() {
        val kotlin = source("app/src/main/java/com/aayoutube/app/HostLink.kt")
        assertTrue(kotlin.contains("val active = player.playingNow()"))
        assertTrue(kotlin.contains("if (active && before.id.isNotBlank()"))
        assertTrue(kotlin.contains("reconnectAttempts < 4"))
        assertTrue(kotlin.contains("main.removeCallbacks(reconnect)"))
    }
}
