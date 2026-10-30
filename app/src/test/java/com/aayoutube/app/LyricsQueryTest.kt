package com.aayoutube.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsQueryTest {
    @Test fun stripsVideoDecorationsWithoutChangingSong() {
        assertEquals("Song", LyricsQuery.title("Artist - Song (Official Music Video)", "Artist"))
        assertEquals("Song", LyricsQuery.title("Song [Official Audio]", "Artist"))
        assertEquals("Song", LyricsQuery.title("Song (Lyrics)", "Artist"))
        assertEquals("Song", LyricsQuery.title("Song", "Artist"))
    }

    @Test fun doesNotStripUnrelatedLabelsOrArtists() {
        assertEquals("Live at Wembley", LyricsQuery.title("Live at Wembley", "Artist"))
        assertEquals("Other - Song", LyricsQuery.title("Other - Song", "Artist"))
    }
}
