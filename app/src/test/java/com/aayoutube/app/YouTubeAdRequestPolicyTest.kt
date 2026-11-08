package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class YouTubeAdRequestPolicyTest {
    @Test fun youtubePagesUseScriptAdHandlingWithoutBreakingPlayerRequests() {
        val pages = listOf("youtube.com", "m.youtube.com", "www.youtube.com", "youtu.be")
        val resources = listOf(
            "doubleclick.net", "googleads.g.doubleclick.net", "pagead2.googlesyndication.com",
            "youtube.com", "www.youtube.com", "r1---sn-abc.googlevideo.com", "i.ytimg.com",
            "googleadservices.com"
        )
        pages.forEach { page ->
            resources.forEach { resource ->
                assertFalse("$resource from $page must not be network blocked",
                    YouTubeAdRequestPolicy.shouldBlock(resource, page))
            }
        }
    }

    @Test fun youtubeAndVideoAssetsAreSafeOnAnyPage() {
        assertFalse(YouTubeAdRequestPolicy.shouldBlock("R1.GoogleVideo.com", "accounts.google.com"))
        assertFalse(YouTubeAdRequestPolicy.shouldBlock("img.YTIMG.com", null))
        assertFalse(YouTubeAdRequestPolicy.shouldBlock("youtube.com", "www.google.com"))
    }

    @Test fun unrelatedPageAdRequestsCanStillBeBlocked() {
        assertTrue(YouTubeAdRequestPolicy.shouldBlock("ads.doubleclick.net", "accounts.google.com"))
        assertTrue(YouTubeAdRequestPolicy.shouldBlock("adservice.google.com", null))
        assertFalse(YouTubeAdRequestPolicy.shouldBlock("google.com", null))
        assertFalse(YouTubeAdRequestPolicy.shouldBlock("badgooglevideo.com.evil.test", "example.com"))
        assertTrue(YouTubeAdRequestPolicy.shouldBlock("a.DOUBLECLICK.NET.", "example.com"))
    }
}
