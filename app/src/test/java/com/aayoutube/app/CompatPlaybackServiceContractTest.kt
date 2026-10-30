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
        assertTrue(source.contains("override fun onFastForward()"))
        assertTrue(source.contains("override fun onRewind()"))
        assertTrue(source.contains("METADATA_KEY_ART_URI"))
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
    @Test fun browserRecoveryAndBoundedIpcDrain() {
        val connection = listOf(File("app/src/main/java/com/aayoutube/app/CompatMediaConnection.kt"),
            File("../app/src/main/java/com/aayoutube/app/CompatMediaConnection.kt"))
            .first { it.isFile }.readText()
        assertTrue(connection.contains("override fun onConnectionFailed() { if (browser === generationBrowser) reconnect() }"))
        assertTrue(connection.contains("override fun onConnectionSuspended() { if (browser === generationBrowser) reconnect() }"))
        assertTrue(connection.contains("handler.removeCallbacks(retry)"))
        assertTrue(connection.contains("browser === generationBrowser"))
        assertTrue(connection.contains("queuedActions.clear()"))
        assertTrue(connection.contains("if (queuedActions.contains(Commands.EXIT)) return"))
        assertTrue(connection.contains("if (browser == null && retries >= 5) retries = 0"))
        assertTrue(connection.contains("if (browser == null && retries == 0) connect()"))
        assertTrue(source.contains("val toDrain = pending.size"))
        assertTrue(source.contains("if (outgoing == null || shuttingDown || quitRequested || pending.isEmpty())"))
    }
    @Test fun commandQueueAndQuitContract() {
        assertTrue(source.contains("pending.clear()"))
        assertTrue(source.contains("Msg.QUIT_ACK"))
        assertTrue(source.contains("if (quitRequested) return"))
    }
    @Test fun reconnectionAndShutdown() {
        assertTrue(source.contains("if (!shuttingDown && !quitRequested && (snapshot.playing || recoveringPlayback) && snapshot.id.isNotBlank())"))
        assertTrue(source.contains("retries >= 4"))
        assertTrue(source.contains("recoveringPlayback = true"))
        assertTrue(source.contains("} else if (recoveringPlayback) {"))
        assertTrue(source.contains("handler.removeCallbacks(idleStop)"))
        assertTrue(source.contains("idleSince = 0L"))
        assertTrue(source.contains("snapshot.copy(playing = false, buffering = false)"))
        assertTrue(source.contains("pending.isNotEmpty()) {"))
        assertFalse(source.contains("retries = 0\n            recoveringPlayback = false"))
        assertTrue(source.contains("if (s.playing) recoveringPlayback = false"))
        assertTrue(source.contains("shuttingDown = true"))
        assertTrue(source.contains("handler.removeCallbacksAndMessages(null)"))
        assertTrue(source.contains("Msg.QUIT_ACK"))
        assertTrue(source.contains("override fun onStartCommand"))
        assertTrue(source.contains("notifications.placeholder()"))
    }
}
