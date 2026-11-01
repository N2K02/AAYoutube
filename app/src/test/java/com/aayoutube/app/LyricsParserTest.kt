package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class LyricsParserTest {
    @Test fun parsesMillisecondsAndSortsOutOfOrder() {
        val lines = LyricsParser.parse("[00:12.50]Second\n[00:01.005]First")
        assertEquals(2, lines.size)
        assertEquals(1005L, lines[0].atMs)
        assertEquals("First", lines[0].text)
        assertEquals(12500L, lines[1].atMs)
    }

    @Test fun expandsMultipleTimestampsForOneLine() {
        val lines = LyricsParser.parse("[00:01.00][00:02.00]Chorus")
        assertEquals(listOf(1000L, 2000L), lines.map { it.atMs })
        assertTrue(lines.all { it.text == "Chorus" })
    }

    @Test fun ignoresMetadataAndBlankLines() {
        assertEquals(listOf("Hello"), LyricsParser.parse("[ar:Artist]\n[00:00.00]Hello\n").map { it.text })
    }

    @Test fun currentLineFollowsSeekAndPausePositions() {
        val lines = LyricsParser.parse("[00:01.00]One\n[00:04.00]Two\n[00:08.00]Three")
        assertEquals(-1, LyricsParser.current(lines, 999))
        assertEquals(0, LyricsParser.current(lines, 1000))
        assertEquals(1, LyricsParser.current(lines, 7999))
        assertEquals(2, LyricsParser.current(lines, 8000))
        assertEquals(0, LyricsParser.current(lines, 1500))
    }

    @Test fun noLyricsIsSafe() {
        assertTrue(LyricsParser.parse("").isEmpty())
        assertEquals(-1, LyricsParser.current(emptyList(), 2000))
    }
}
