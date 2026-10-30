package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CompatPlaybackServiceContractTest {
    private val source by lazy {
        listOf(File("app/src/main/java/com/aayoutube/app/CompatPlaybackService.kt"),
            File("../app/src/main/java/com/aayoutube/app/CompatPlaybackService.kt"))
            .first { it.isFile }.readText()
    }
    @Test fun compatBrowseAndTransport() {
        assertTrue(source.contains("MediaBrowserServiceCompat()"))
        assertTrue(source.contains("MediaSessionCompat(this"))
        assertTrue(source.contains("Library.recents().map"))
        assertTrue(source.contains("Library.favourites().map"))
        assertTrue(source.contains("override fun onPlayFromSearch"))
        assertTrue(source.contains("override fun onSeekTo"))
    }
    @Test fun reconnectionAndShutdown() {
        assertTrue(source.contains("if (snapshot.playing && snapshot.id.isNotBlank())"))
        assertTrue(source.contains("retries >= 4"))
        assertTrue(source.contains("shuttingDown = true"))
        assertTrue(source.contains("handler.removeCallbacksAndMessages(null)"))
    }
}
