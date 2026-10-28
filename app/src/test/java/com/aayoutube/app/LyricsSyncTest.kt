package com.aayoutube.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsSyncTest {
    @Test fun advancesAtPlaybackSpeed() {
        assertEquals(4500L, LyricsSync.position(1000, true, false, 1.75f, 2000, 0))
    }
    @Test fun freezesWhenPausedOrBuffering() {
        assertEquals(1000L, LyricsSync.position(1000, false, false, 1f, 5000, 0))
        assertEquals(1000L, LyricsSync.position(1000, true, true, 1f, 5000, 0))
    }
    @Test fun offsetAdjustsAndClamps() {
        assertEquals(1250L, LyricsSync.position(1000, false, false, 1f, 0, 250))
        assertEquals(750L, LyricsSync.position(1000, false, false, 1f, 0, -250))
        assertEquals(0L, LyricsSync.position(100, false, false, 1f, 0, -500))
    }
    @Test fun playbackRateAndNegativeElapsed() {
        assertEquals(2000L, LyricsSync.position(1000, true, false, 2f, 500, 0))
        assertEquals(1000L, LyricsSync.position(1000, true, false, 1f, -100, 0))
    }
}
