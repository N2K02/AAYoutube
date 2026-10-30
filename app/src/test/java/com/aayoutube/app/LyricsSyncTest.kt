package com.aayoutube.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun directVideoClockExtrapolatesFromMonotonicTimestamp() {
        val sample = LyricsClock("video", 11_000, true, false, 1.25f, 20_000)
        assertEquals(12_250L, sample.positionFor("video", 21_000, 0))
        assertEquals(12_000L, sample.positionFor("video", 21_000, -250))
        assertEquals(11_000L, sample.positionFor("video", 20_000, 0))
    }

    @Test fun directClockHandlesSeekPauseBufferingAndOldVideos() {
        val seeked = LyricsClock("video", 80_000, true, false, 1f, 30_000)
        assertEquals(80_250L, seeked.positionFor("video", 30_250, 0))
        assertNull(seeked.positionFor("previous", 30_250, 0))
        val paused = LyricsClock("video", 85_000, false, false, 1f, 30_000)
        assertEquals(85_000L, paused.positionFor("video", 45_000, 0))
        val buffering = LyricsClock("video", 85_000, true, true, 1f, 30_000)
        assertEquals(85_000L, buffering.positionFor("video", 45_000, 0))
        assertNull(LyricsClock().positionFor("video", 30_000, 0))
    }
}
