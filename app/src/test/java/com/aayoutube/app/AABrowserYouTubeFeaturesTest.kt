package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** YouTube features taken from AABrowser: video fit, playlist scroll guard, 10 s skip option. */
class AABrowserYouTubeFeaturesTest {
    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()
    private val js = src("app/src/main/assets/aay.js")

    @Test fun fitCyclesFitFillStretch() {
        assertEquals("fill", VideoFitPolicy.next("fit"))
        assertEquals("stretch", VideoFitPolicy.next("fill"))
        assertEquals("fit", VideoFitPolicy.next("stretch"))
        assertEquals("fit", VideoFitPolicy.normalize("bogus"))
        assertEquals("fill", VideoFitPolicy.next(null))
        assertEquals("fit", FeatureFlags().videoFit)
    }

    @Test fun fitAppliesOnlyToAFilledVideo() {
        assertTrue(js.contains("vfitCss('aay-vfill', 'cover') + vfitCss('aay-vstretch', 'fill')"))
        assertTrue(js.contains("'html.aay-car video.html5-main-video,:fullscreen video.html5-main-video,'"))
        assertTrue(js.contains("root.classList.toggle('aay-vfill', CFG.videoFit === 'fill');"))
        assertTrue(src("app/src/main/java/com/aayoutube/app/Features.kt")
            .contains(".put(\"videoFit\", VideoFitPolicy.normalize(videoFit))"))
        for (f in listOf("Screens.kt", "LegacyProjectionActivity.kt"))
            assertTrue(f, src("app/src/main/java/com/aayoutube/app/$f")
                .contains("Features.update { it.copy(videoFit = VideoFitPolicy.next(it.videoFit)) }"))
    }

    @Test fun playlistScrollGuardOnlyWhileAPlaylistPlaysAtTheTop() {
        val guard = js.substringAfter("1f. Keep the video in view when a playlist moves on").substringBefore("1g. AABrowser-style tap-to-seek")
        assertTrue(guard.contains("/[?&]list=/.test(location.search)"))
        assertTrue(guard.contains("(window.scrollY || 0) < 80"))
        assertTrue(guard.contains("return scrollIntoView.apply(this, arguments);"))
        assertTrue(guard.contains("focus.call(this, { preventScroll: true })"))
        assertTrue("never in the browse page", guard.contains("if (!BROWSE) (function () {"))
    }

    @Test fun skipButtonsSeekTenSecondsOnlyWhenChosen() {
        assertFalse("default keeps next / previous video", FeatureFlags().skipSeeks)
        assertEquals(40_000L, VideoFitPolicy.seekTarget(30_000L, 200_000L, forward = true))
        assertEquals(20_000L, VideoFitPolicy.seekTarget(30_000L, 200_000L, forward = false))
        assertEquals(0L, VideoFitPolicy.seekTarget(4_000L, 200_000L, forward = false))
        assertEquals(200_000L, VideoFitPolicy.seekTarget(195_000L, 200_000L, forward = true))
        val service = src("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        assertEquals(2, Regex("if \\(Features\\.current\\.skipSeeks\\)").findAll(service).count())
    }
}
