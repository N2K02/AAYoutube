package com.aayoutube.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeSharedLinkTest {
    @Test fun findsFirstPlayableUrlInShareText() {
        val url = "https://youtu.be/86p7SP34qSc?si=example&t=42"
        assertEquals(url, YouTubeSharedLink.find("Title\nRead more: https://example.org/news\n($url)."))
        assertEquals(url, YouTubeSharedLink.find(StringBuilder(url)))
    }

    @Test fun supportsWatchShortsLiveAndPlaylists() {
        val urls = listOf(
            "https://www.youtube.com/watch?feature=shared&v=86p7SP34qSc",
            "https://m.youtube.com/shorts/86p7SP34qSc",
            "https://youtube.com/live/86p7SP34qSc?si=example",
            "https://music.youtube.com/playlist?list=PL_example",
        )
        for (url in urls) assertEquals(url, YouTubeSharedLink.find(url))
    }

    @Test fun rejectsInvalidAndUnrelatedShares() {
        val unsupported = listOf(
            null,
            "Plain text",
            "https://www.youtube.com/",
            "https://www.youtube.com/watch?v=wrong",
            "https://www.youtube.com/playlist",
            "https://youtube.com.evil.test/watch?v=86p7SP34qSc",
            "https://evil.test/?next=https://youtu.be/86p7SP34qSc",
            "https://evil.test@youtu.be/86p7SP34qSc",
            "https://youtu.be:8080/86p7SP34qSc",
            "https://youtu.be/86p7SP34qSc/extra",
            "javascript:https://youtu.be/invalid",
        )
        for (text in unsupported) assertNull(text, YouTubeSharedLink.find(text))
    }
}
