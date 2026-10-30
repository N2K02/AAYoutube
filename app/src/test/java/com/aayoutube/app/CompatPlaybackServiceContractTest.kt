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
        assertTrue(source.contains("\"queue\" -> queueGate.items().map"))
        assertTrue(source.contains("notifyChildrenChanged(\"queue\")"))
        assertTrue(source.contains("folder(\"queue\", \"Up next\")"))
        assertTrue(source.contains("override fun onPlayFromSearch"))
        assertTrue(source.contains("override fun onSeekTo"))
        assertTrue(source.contains("override fun onFastForward()"))
        assertTrue(source.contains("override fun onRewind()"))
        assertTrue(source.contains("METADATA_KEY_ART_URI"))
        assertTrue(source.contains("updateGate.playbackChanged("))
        assertTrue(source.contains("updateGate.metadataChanged("))
        assertTrue(source.contains("val actions = actionsFor(s)"))
        assertTrue(source.contains("!s.rate.isFinite() -> 1f"))
        assertTrue(source.contains("s.rate.coerceIn(0.1f, 4f)"))
        assertTrue(source.contains("if (s.canNext)"))
        assertTrue(source.contains("if (s.canPrev)"))
        assertTrue(source.contains("!s.live && !s.ad && s.durMs > 0L"))
        assertTrue(source.contains("if (query.isNullOrBlank()) onPlay()"))
    }
    @Test fun notificationManagerIsSeparated() {
        val manager = listOf(File("app/src/main/java/com/aayoutube/app/CarMediaNotificationManager.kt"),
            File("../app/src/main/java/com/aayoutube/app/CarMediaNotificationManager.kt"))
            .first { it.isFile }.readText()
        assertTrue(source.contains("notifications.playback(s, session.sessionToken)"))
        assertTrue(manager.contains("MediaButtonReceiver.buildMediaButtonPendingIntent"))
        assertTrue(manager.contains("setShowActionsInCompactView(*compact.toIntArray())"))
        assertTrue(manager.contains("if (snapshot.canPrev)"))
        assertTrue(manager.contains("if (snapshot.canNext)"))
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
        assertTrue(connection.contains("val previous = browser"))
        assertTrue(connection.contains("browser = null\n        previous?.let"))
        assertTrue(connection.contains("queuedActions.clear()"))
        assertTrue(connection.contains("if (queuedActions.contains(Commands.EXIT)) return"))
        assertTrue(connection.contains("MediaRetryPolicy.delayMs(retries)"))
        assertTrue(connection.contains("retries = (retries + 1).coerceAtMost(6)"))
        assertTrue(connection.contains("if (browser == null && retries == 0) connect()"))
        assertTrue(source.contains("val toDrain = pending.size"))
        assertTrue(source.contains("drainPending()"))
        assertTrue(source.contains("Wait for REGISTER_ACK before replaying commands"))
        assertTrue(source.contains("awaitingRegistration && command != Msg.REGISTER"))
        assertTrue(source.contains("if (outgoing == null) return"))
        assertTrue(source.contains("val wasBound = bound"))
        assertTrue(source.contains("private fun newConnection(): ServiceConnection"))
        assertTrue(source.contains("if (connection !== this || shuttingDown) return"))
        assertTrue(source.contains("if (connection === this) lost()"))
        assertTrue(source.contains("if (wasBound && previous != null)"))
        assertTrue(source.contains("if (outgoing == null || shuttingDown || quitRequested || pending.isEmpty())"))
    }
    @Test fun commandQueueAndQuitContract() {
        assertTrue(source.contains("pending.clear()"))
        assertTrue(source.contains("Msg.QUIT_ACK"))
        assertTrue(source.contains("if (quitRequested || shuttingDown) return"))
        assertTrue(source.contains("if (intent?.action == ACTION_QUIT) requestQuit()"))
        assertTrue(source.contains("Commands.EXIT -> requestQuit()"))
    }
    @Test fun reconnectionAndShutdown() {
        assertTrue(source.contains("!shuttingDown && !quitRequested && recovery.onLinkLost(snapshot)"))
        assertTrue(source.contains("send(Msg.SYNC)"))
        assertTrue(source.contains("recovery.onRendererGone(previous)"))
        assertTrue(source.contains("MediaRetryPolicy.shouldRetry(retries, hasWork)"))
        assertTrue(source.contains("Msg.REGISTER_ACK"))
        assertTrue(source.contains("handler.postDelayed(registrationTimeout, 5_000L)"))
        assertTrue(source.contains("handler.removeCallbacks(registrationTimeout)"))
        assertTrue(source.contains("awaitingRegistration && bound"))
        assertTrue(source.contains("message.data.getInt(\"epoch\", -1) == registrationEpoch"))
        assertTrue(source.contains("bound && !awaitingRegistration && outgoing != null"))
        assertTrue(source.contains("message.data.getInt(\"epoch\", -1) == registrationEpoch"))
        assertTrue(source.contains("awaitingRegistration = true"))
        assertTrue(source.contains("awaitingRegistration = false"))
        assertTrue(source.contains("registrationEpoch++"))
        assertTrue(source.contains("message.data.getInt(\"epoch\", -1) == registrationEpoch"))
        assertTrue(source.contains("recoveringPlayback = true"))
        assertTrue(source.contains("} else if (recoveringPlayback) {"))
        assertTrue(source.contains("handler.removeCallbacks(idleStop)"))
        assertTrue(source.contains("!snapshot.playing && !recoveringPlayback && !quitRequested"))
        assertTrue(source.contains("idleSince = 0L"))
        assertTrue(source.contains("snapshot.copy(playing = false, buffering = false)"))
        assertTrue(source.contains("!snapshot.playing && !snapshot.buffering && !recoveringPlayback"))
        assertTrue(source.contains("pending.isNotEmpty() || awaitingRegistration"))
        assertFalse(source.contains("retries = 0\n            recoveringPlayback = false"))
        assertTrue(source.contains("if (s.playing) recoveringPlayback = false"))
        assertTrue(source.contains("shuttingDown = true"))
        assertTrue(source.contains("handler.removeCallbacksAndMessages(null)"))
        assertTrue(source.contains("Msg.QUIT_ACK"))
        assertTrue(source.contains("override fun onStartCommand"))
        assertTrue(source.contains("notifications.placeholder()"))
        assertTrue(source.contains("if (foreground && lastNotification == key) return"))
        assertTrue(source.contains("NotificationKey(s.id, s.title, s.artist, s.canNext, s.canPrev)"))
        assertTrue(source.contains("if (!shuttingDown) updateNotification(snapshot)"))
    }
    @Test fun acknowledgedHandshakeAndDirectExitFallback() {
        val web = listOf(File("app/src/main/java/com/aayoutube/app/WebHost.kt"),
            File("../app/src/main/java/com/aayoutube/app/WebHost.kt"))
            .first { it.isFile }.readText()
        val activity = listOf(File("app/src/main/java/com/aayoutube/app/MainActivity.kt"),
            File("../app/src/main/java/com/aayoutube/app/MainActivity.kt"))
            .first { it.isFile }.readText()
        val protocol = listOf(File("app/src/main/java/com/aayoutube/app/Protocol.kt"),
            File("../app/src/main/java/com/aayoutube/app/Protocol.kt"))
            .first { it.isFile }.readText()
        assertTrue(protocol.contains("const val REGISTER_ACK = 103"))
        assertTrue(web.contains("send(Msg.REGISTER_ACK"))
        assertTrue(web.contains("d.getInt(\"epoch\", -1)"))
        assertTrue(web.contains("if (what == Msg.STATE) data.putInt(\"epoch\", linkEpoch)"))
        assertTrue(web.contains("linkEpoch = -1"))
        assertTrue(source.contains("message.data.getInt(\"epoch\", -1) == registrationEpoch"))
        assertTrue(source.contains("if (intent?.action == ACTION_QUIT) requestQuit()"))
        assertTrue(activity.contains("setAction(PlaybackService.ACTION_QUIT)"))
    }

}
