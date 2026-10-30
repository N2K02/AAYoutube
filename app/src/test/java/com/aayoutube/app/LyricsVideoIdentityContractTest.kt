package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LyricsVideoIdentityContractTest {
    private fun read(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun metadataUpdatesDoNotInvalidateLyricsForTheSameVideo() {
        assertEquals("video123", LyricsCache.key(Snap(id = "video123", title = "Song - YouTube")))
        assertEquals("video123", LyricsCache.key(Snap(id = "video123", title = "Song", artist = "Artist")))
        assertNotEquals(LyricsCache.key(Snap(id = "first")), LyricsCache.key(Snap(id = "second")))
    }

    @Test fun routeAndTimeEventsRefreshPlaybackSnapshots() {
        val js = read("app/src/main/assets/aay.js")
        assertTrue(js.contains("v.addEventListener('timeupdate', function () {"))
        assertTrue(js.contains("if (now - lastProgressReportAt > 1400)"))
        assertTrue(js.contains("window.addEventListener('yt-navigate-finish', function () { schedReport(0); })"))
    }

    @Test fun carHostDoesNotDetachUnchangedWebViewOnRender() {
        val service = read("app/src/main/java/com/aayoutube/app/CarVideoService.kt")
        assertTrue(service.contains("frame.getChildAt(0) is android.webkit.WebView) return"))
    }
}
