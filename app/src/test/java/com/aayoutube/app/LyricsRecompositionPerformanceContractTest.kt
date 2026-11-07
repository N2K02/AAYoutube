package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRecompositionPerformanceContractTest {
    @Test fun timedLyricsOnlyInvalidateTextOnLineChange() {
        val lyrics = listOf(
            File("app/src/main/java/com/aayoutube/app/Lyrics.kt"),
            File("../app/src/main/java/com/aayoutube/app/Lyrics.kt"),
        ).first { it.isFile }.readText()
        assertTrue(lyrics.contains("val activeLine by remember(lines) { derivedStateOf { LyricsParser.current(lines, now) } }"))
        assertTrue(lyrics.contains("val index = activeLine"))
        assertTrue(lyrics.contains("Crossfade(targetState = index"))
        assertTrue(lyrics.contains("delay(if (latestClock.playing && !latestClock.buffering) 100L else 1_000L)"))
    }
}
