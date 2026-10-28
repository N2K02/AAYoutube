package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class LyricsProvidersTest {
    @Test fun allIntegratedProvidersArePresent() {
        val expected = setOf("LRCLIB", "SIMPMUSIC", "NETEASE", "BLYRICS", "BINIMUM", "LYRICS_OVH", "RMM")
        assertEquals(expected, LyricsProvider.entries.map { it.name }.toSet())
    }

    @Test fun freeProviderDefaultsContainAllIntegratedSources() {
        val ordered = LyricsProviders.ORDER.split(",")
        assertEquals(LyricsProvider.entries.size, ordered.size)
        assertEquals(LyricsProvider.entries.map { it.name }.toSet(), ordered.toSet())
        assertEquals("LRCLIB", ordered.first())
    }

    @Test fun providerIdentifiersAreStableAndUnique() {
        val values = LyricsProvider.entries
        assertEquals(values.size, values.map { it.name }.distinct().size)
        assertEquals(values.size, values.map { it.title }.distinct().size)
        values.forEach { assertTrue(it.title.isNotBlank()) }
    }

    @Test fun syncedLyricsTimestampPrecisionIsPreserved() {
        val sample = "[01:02.3]A\n[01:02.34]B\n[01:02.345]C"
        assertEquals(listOf(62300L, 62340L, 62345L), LyricsParser.parse(sample).map { it.atMs })
    }
}
