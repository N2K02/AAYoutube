package com.aayoutube.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsProviderPriorityTest {
    @Test fun laterTimedLyricsWinOverEarlyPlainText() = runBlocking {
        val accessed = mutableListOf<LyricsProvider>()
        val synced = listOf(LyricLine(500L, "Timed"))
        val result = LyricsProviders.preferTimed(
            listOf(LyricsProvider.LRCLIB, LyricsProvider.BLYRICS, LyricsProvider.LYRICS_OVH)
        ) { provider ->
            accessed += provider
            when (provider) {
                LyricsProvider.LRCLIB -> emptyList<LyricLine>() to "Plain"
                LyricsProvider.BLYRICS -> synced to null
                else -> null
            }
        }
        assertEquals(synced, result.first)
        assertEquals(null, result.second)
        assertEquals(listOf(LyricsProvider.LRCLIB, LyricsProvider.BLYRICS), accessed)
    }

    @Test fun plainFallbackIsReturnedOnlyWhenNoProviderIsTimed() = runBlocking {
        val result = LyricsProviders.preferTimed(
            listOf(LyricsProvider.LRCLIB, LyricsProvider.BLYRICS)
        ) {
            if (it == LyricsProvider.LRCLIB) emptyList<LyricLine>() to "First plain"
            else emptyList<LyricLine>() to "Second plain"
        }
        assertTrue(result.first.isEmpty())
        assertEquals("First plain", result.second)
    }

    @Test fun allUnavailableProvidersReturnEmpty() = runBlocking {
        assertEquals(emptyList<LyricLine>() to null,
            LyricsProviders.preferTimed(listOf(LyricsProvider.LRCLIB)) { null })
    }
}
