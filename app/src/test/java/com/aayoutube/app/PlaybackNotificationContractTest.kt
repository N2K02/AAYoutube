package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Protect foreground playback and its MediaStyle notification across process boundaries. */
class PlaybackNotificationContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun webPlaybackStartsMediaServiceNotJustBrowserBinding() {
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("ContextCompat.startForegroundService("))
        assertTrue(web.contains(".setAction(PlaybackService.ACTION_PLAYBACK)"))
        assertTrue(web.contains(".putExtra(PlaybackService.EXTRA_PLAYBACK, current.toBundle())"))
        assertTrue(web.contains("ensurePlaybackService(previous, s)"))
        assertTrue(web.contains("previous.playing && previous.id == current.id"))
        assertTrue(web.contains("Cannot start playback foreground service"))
    }

    @Test fun startedServiceDisplaysMediaImmediatelyAndMaintainsQuitPath() {
        val media = source("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        assertTrue(media.contains("const val ACTION_PLAYBACK"))
        assertTrue(media.contains("const val EXTRA_PLAYBACK"))
        assertTrue(media.contains("val startingPlayback = intent?.action == ACTION_PLAYBACK"))
        assertTrue(media.contains("snapshot = initial"))
        assertTrue(media.contains("startForeground(CarMediaNotificationManager.NOTIFICATION_ID, notification)"))
        assertTrue(media.contains("if (initial != null && !quitRequested) publish(snapshot)"))
        assertTrue(media.contains("if (intent?.action == ACTION_QUIT) requestQuit()"))
        assertTrue(media.contains("handler.removeCallbacksAndMessages(null)"))
        assertTrue(media.contains("if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)"))
    }

    @Test fun notificationIsMediaStyleAndChannelBlockingIsDiagnosable() {
        val notifications = source("app/src/main/java/com/aayoutube/app/CarMediaNotificationManager.kt")
        assertTrue(notifications.contains("fun channelStatus()"))
        assertTrue(notifications.contains("manager.areNotificationsEnabled()"))
        assertTrue(notifications.contains("channel?.importance"))
        assertTrue(notifications.contains("FOREGROUND_SERVICE_IMMEDIATE"))
        assertTrue(notifications.contains("MediaStyle()"))
        assertTrue(notifications.contains(".setMediaSession(token)"))
        assertTrue(notifications.contains("android.R.drawable.ic_media_previous"))
    }
}
