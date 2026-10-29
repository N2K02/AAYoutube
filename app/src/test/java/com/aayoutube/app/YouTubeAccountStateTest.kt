package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class YouTubeAccountStateTest {
    @Test fun onlyExplicitLoginReportsRevealSignOut() {
        assertEquals(YouTubeAccountState.SIGNED_IN, YouTubeAccountState.fromScript("signed_in"))
        assertEquals(YouTubeAccountState.SIGNED_OUT, YouTubeAccountState.fromScript("signed_out"))
        assertNull(YouTubeAccountState.fromScript("unknown"))
        assertNull(YouTubeAccountState.fromScript("true"))
        assertNull(YouTubeAccountState.fromScript(""))
        assertNotEquals(YouTubeAccountState.SIGNED_IN, YouTubeAccountState.CHECKING)
    }
}
