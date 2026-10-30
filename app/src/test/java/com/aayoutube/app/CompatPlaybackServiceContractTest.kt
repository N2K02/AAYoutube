package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CompatPlaybackServiceContractTest {
    private val source by lazy {
        listOf(File("app/src/main/java/com/aayoutube/app/PlaybackService.kt"),
            File("../app/src/main/java/com/aayoutube/app/PlaybackService.kt"))
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
    @Test fun notificationManagerIsSeparated() {
        val manager = listOf(File("app/src/main/java/com/aayoutube/app/CarMediaNotificationManager.kt"),
            File("../app/src/main/java/com/aayoutube/app/CarMediaNotificationManager.kt"))
            .first { it.isFile }.readText()
        assertTrue(source.contains("notifications.playback(s, session.sessionToken)"))
        assertTrue(manager.contains("MediaButtonReceiver.buildMediaButtonPendingIntent"))
        assertTrue(manager.contains("setShowActionsInCompactView(0, 1, 2)"))
        assertTrue(manager.contains("fun placeholder()"))
    }
    @Test fun reconnectionAndShutdown() {
        assertTrue(source.contains("if (!shuttingDown && !quitRequested && snapshot.playing && snapshot.id.isNotBlank())"))
        assertTrue(source.contains("retries >= 4"))
        assertTrue(source.contains("shuttingDown = true"))
        assertTrue(source.contains("handler.removeCallbacksAndMessages(null)"))
        assertTrue(source.contains("Msg.QUIT_ACK"))
        assertTrue(source.contains("override fun onStartCommand"))
        assertTrue(source.contains("notifications.placeholder()"))
    }
}
