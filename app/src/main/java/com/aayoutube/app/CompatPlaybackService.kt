package com.aayoutube.app

import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat

/**
 * AABrowser-compatible media browser foundation. This is deliberately not
 * exported until the Media3 session's consumers are migrated atomically.
 */
class CompatPlaybackService : MediaBrowserServiceCompat() {
    private lateinit var session: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "AAYoutubeCompat").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = Unit
                override fun onPause() = Unit
                override fun onSkipToNext() = Unit
                override fun onSkipToPrevious() = Unit
                override fun onStop() = Unit
                override fun onPlayFromSearch(query: String?, extras: Bundle?) = Unit
            })
            setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH)
                .setState(PlaybackStateCompat.STATE_NONE, 0, 1f)
                .build())
            isActive = true
        }
        sessionToken = session.sessionToken
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(ROOT, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val children = if (parentId == ROOT) listOf(
            folder("recent", "Recently played"),
            folder("favourites", "Favourites")
        ) else emptyList()
        result.sendResult(children.toMutableList())
    }

    private fun folder(id: String, title: String): MediaBrowserCompat.MediaItem =
        MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )

    override fun onDestroy() {
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private companion object { const val ROOT = "root" }
}
