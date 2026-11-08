package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class YouTubeSavedHostTest {
    @Test fun exactDomainAndSubdomainsAreAccepted() {
        assertTrue(WebHost.isYouTubeHost("youtube.com"))
        assertTrue(WebHost.isYouTubeHost("m.youtube.com"))
        assertTrue(WebHost.isYouTubeHost("WWW.YOUTUBE.COM"))
    }

    @Test fun suffixLookalikesAndOtherHostsAreRejected() {
        for (host in listOf("evilyoutube.com", "notyoutube.com", "youtube.com.evil.test", "youtube.co", "youtu.be", "google.com", "youtube.com@evil.test", "")) {
            assertFalse(host, WebHost.isYouTubeHost(host))
        }
        assertFalse(WebHost.isYouTubeHost(null))
    }
}
