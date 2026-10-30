package com.aayoutube.app

import android.graphics.Bitmap
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver

/** Builds MediaCompat notifications without owning service lifecycle decisions. */
internal class CarMediaNotificationManager(
    private val service: PlaybackService
) {
    companion object {
        const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "aay_media"
    }

    fun createChannel() {
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AAYoutube playback", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun launchIntent(): PendingIntent = PendingIntent.getActivity(
        service, 0, Intent(service, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun placeholder(): Notification = NotificationCompat.Builder(service, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("AAYoutube")
        .setContentText("Preparing playback")
        .setOnlyAlertOnce(true)
        .setContentIntent(launchIntent())
        .build()

    private fun action(action: Long): PendingIntent =
        MediaButtonReceiver.buildMediaButtonPendingIntent(service, action)

    fun playback(snapshot: Snap, token: MediaSessionCompat.Token, artwork: Bitmap? = null): Notification {
        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(snapshot.title.ifBlank { "AAYoutube" })
            .setContentText(snapshot.artist)
            .setLargeIcon(artwork)
            .setContentIntent(launchIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(snapshot.playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val compact = mutableListOf<Int>()
        var actionIndex = 0
        if (snapshot.canPrev) {
            builder.addAction(android.R.drawable.ic_media_previous, "Previous",
                action(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            compact.add(actionIndex++)
        }
        builder.addAction(
            if (snapshot.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (snapshot.playing) "Pause" else "Play",
            action(if (snapshot.playing) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY)
        )
        compact.add(actionIndex++)
        if (snapshot.canNext) {
            builder.addAction(android.R.drawable.ic_media_next, "Next",
                action(PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            compact.add(actionIndex)
        }
        return builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(token)
                .setShowActionsInCompactView(*compact.toIntArray())
        ).build()
    }
}
