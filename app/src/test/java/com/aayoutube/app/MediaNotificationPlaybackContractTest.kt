package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The system media controls must reflect actual playback, not a cached placeholder. */
class MediaNotificationPlaybackContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun mediaSessionPublishesRealStateAndNotificationTracksPlayPauseTransitions() {
        val service = source("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        val notification = source("app/src/main/java/com/aayoutube/app/CarMediaNotificationManager.kt")
        assertTrue(service.contains("s.playing -> PlaybackStateCompat.STATE_PLAYING"))
        assertTrue(service.contains("s.id.isNotEmpty() -> PlaybackStateCompat.STATE_PAUSED"))
        assertTrue(service.contains("val playing: Boolean"))
        assertTrue(service.contains("lastNotification != key"))
        assertTrue(service.contains("android.app.NotificationManager::class.java).notify("))
        assertTrue(service.contains("notifications.playback(s, session.sessionToken, artworkLoader.bitmapFor(cover))"))
        assertTrue(notification.contains("if (snapshot.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play"))
        assertTrue(notification.contains("if (snapshot.playing) \"Pause\" else \"Play\""))
        assertFalse(notification.contains("Sleep timer:"))
    }

    @Test fun playerStateResyncDoesNotOverrideExplicitPauseOrCarPlayback() {
        val js = source("app/src/main/assets/aay.js")
        assertTrue(js.contains("function syncPlayerState()"))
        assertTrue(js.contains("if (st !== 2) return"))
        assertTrue(js.contains("#movie_player, .html5-video-player, .html5-video-container"))
        assertTrue(js.contains("PAUSED || bgWatch.explicitPause || bgWatch.held || isAd()"))
        assertTrue(js.contains("if (v.paused) {"))
    }
}
