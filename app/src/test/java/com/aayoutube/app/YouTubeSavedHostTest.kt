package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class YouTubeSavedHostTest {
    @Test fun exactDomainAndSubdomainsAreAccepted() {
        assertTrue(YouTubeSavedHostPolicy.matches("youtube.com"))
        assertTrue(YouTubeSavedHostPolicy.matches("m.youtube.com"))
        assertTrue(YouTubeSavedHostPolicy.matches("WWW.YOUTUBE.COM"))
    }

    @Test fun suffixLookalikesAndOtherHostsAreRejected() {
        for (host in listOf("evilyoutube.com", "notyoutube.com", "youtube.com.evil.test", "youtube.co", "youtu.be", "google.com", "youtube.com@evil.test", "")) {
            assertFalse(host, YouTubeSavedHostPolicy.matches(host))
        }
        assertFalse(YouTubeSavedHostPolicy.matches(null))
    }
}
