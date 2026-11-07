package com.aayoutube.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Prevent high-frequency background work from returning to the WebView and lyrics UI. */
class PlaybackPerformanceContractTest {
    private fun source(path: String): String {
        val files = listOf(File("src/main/$path"), File("app/src/main/$path"))
        return files.first { it.exists() }.readText()
    }

    @Test fun playerMutationWorkIsCoalescedInBackground() {
        val script = source("assets/aay.js")
        assertTrue(script.contains("tickTimer = setTimeout(tick, (A.isBg && !A.inPip && !A.isCar) ? 5000 : 900)"))
        assertFalse(script.contains("setTimeout(tick, 300)"))
        assertTrue(script.contains("setInterval(reportAccount, 30000)"))
        // Retain explicit playback event reporting instead of replacing it with slow polling.
        assertTrue(script.contains("v.addEventListener('timeupdate'"))
        assertTrue(script.contains("setInterval(backgroundWatchdog, 5000)"))
    }

    @Test fun hiddenOrMissingLyricsDoNotStartAnActiveClock() {
        val ui = source("java/com/aayoutube/app/Lyrics.kt")
        val guard = ui.indexOf("if (!content.foundFor(LyricsCache.key(snap))) return")
        val clock = ui.indexOf("LaunchedEffect(snap.id, offsetMs)")
        assertTrue(guard >= 0 && guard < clock)
        assertTrue(ui.contains("delay(if (latestClock.playing && !latestClock.buffering) 100L else 1_000L)"))
    }
}
