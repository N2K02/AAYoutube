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

    @Test fun lyricsCacheRetriggersAfterArtistOrTitleIsCorrected() {
        LyricsCache.clear()
        val first = Snap(id = "v1", title = "Song", artist = "")
        val better = first.copy(artist = "Artist")
        val result = emptyList<LyricLine>() to "Plain lyrics"
        LyricsCache.put("v1", first, result)
        assertEquals(result, LyricsCache.get("v1", first))
        assertNull(LyricsCache.get("v1", better))
        LyricsCache.put("v1", better, listOf(LyricLine(0L, "Timed")) to null)
        assertEquals("Timed", LyricsCache.get("v1", better)?.first?.single()?.text)
        assertNull(LyricsCache.get("v1", better.copy(title = "Corrected title")))
        LyricsCache.clear()
    }

    @Test fun routeAndTimeEventsRefreshPlaybackSnapshots() {
        val js = read("app/src/main/assets/aay.js")
        assertTrue(js.contains("v.addEventListener('timeupdate', function () {"))
        assertTrue(js.contains("if (now - lastProgressReportAt > 1400)"))
        assertTrue(js.contains("window.addEventListener('yt-navigate-finish', function () { schedReport(0); })"))
    }

    @Test fun carHostDoesNotDetachUnchangedWebViewOnRender() {
        val projection = read("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        // Compose updates the projected frame repeatedly. Preserve the
        // decoder surface when that exact WebView already belongs to it.
        assertTrue(projection.contains("if (frame.childCount != 1 || frame.getChildAt(0) !== player)"))
        assertTrue(projection.contains("WebHost.attachToCarWindow(projectionContext(frame.context, frame), frame)"))
        assertTrue(projection.contains("if (!frame.isAttachedToWindow || frame.display == null) return"))
        assertTrue(projection.contains("onViewAttachedToWindow(v: View)"))
        assertTrue(projection.contains("if (WebHost.fullscreenView.value == null) bindVideoToCarFrame(frame)"))
        assertTrue(projection.contains("WebHost.detach(frame)"))
        assertTrue(projection.contains("key(version)"))
        val host = read("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(host.contains("if (w.parent !== destination || destination == null)"))
    }
}
