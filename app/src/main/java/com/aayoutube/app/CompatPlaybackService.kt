package com.aayoutube.app

import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
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
    private var outgoing: Messenger? = null
    private var bound = false
    private val pending = ArrayDeque<Pair<Int, Bundle>>()
    private val handler = Handler(Looper.getMainLooper())
    private var shuttingDown = false
    private var retries = 0
    private val retry = Runnable { if (!shuttingDown && !bound) ensureWeb() }
    private var snapshot = Snap()
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == Msg.STATE) {
            snapshot = Snap.from(message.data)
            publish(snapshot)
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            outgoing = Messenger(binder)
            retries = 0
            handler.removeCallbacks(retry)
            send(Msg.REGISTER, replyTo = incoming)
            while (pending.isNotEmpty()) {
                val (command, data) = pending.removeFirst()
                send(command, data)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = lost()
        override fun onBindingDied(name: ComponentName) = lost()
        override fun onNullBinding(name: ComponentName) = lost()
    }
    private fun ensureWeb() {
        if (bound || shuttingDown) return
        bound = runCatching {
            bindService(Intent(this, WebHostService::class.java), connection, BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) scheduleRetry()
    }
    private fun scheduleRetry() {
        if (shuttingDown || retries >= 4) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, 1_000L shl retries++)
    }
    private fun lost() {
        outgoing = null
        if (bound) runCatching { unbindService(connection) }
        bound = false
        if (snapshot.playing && snapshot.id.isNotBlank()) {
            pending.addFirst(Msg.OPEN to Bundle().apply {
                putString("id", snapshot.id)
                putLong("sec", snapshot.posMs.coerceAtLeast(0L) / 1000L)
            })
            snapshot = snapshot.copy(playing = false, buffering = true)
            publish(snapshot)
            scheduleRetry()
        }
    }
    private fun send(command: Int, data: Bundle = Bundle(), replyTo: Messenger? = null) {
        ensureWeb()
        val target = outgoing ?: run {
            if (command != Msg.REGISTER) pending.addLast(command to data)
            return
        }
        runCatching {
            target.send(Message.obtain(null, command).apply {
                this.data = data
                this.replyTo = replyTo
            })
        }.onFailure { AppLog.add("MEDIA", "Compat command failed: " + it.javaClass.simpleName) }
    }
    private fun publish(s: Snap) {
        val state = when {
            s.buffering -> PlaybackStateCompat.STATE_BUFFERING
            s.playing -> PlaybackStateCompat.STATE_PLAYING
            s.ended -> PlaybackStateCompat.STATE_STOPPED
            s.id.isNotEmpty() -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(ACTIONS)
            .setState(state, s.posMs, s.rate)
            .build())
        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, s.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, s.durMs)
            .build())
        if (s.playing && s.id.isNotBlank()) {
            Library.noteStarted(s)
            Library.saveLast(s)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Library.init(this)
        session = MediaSessionCompat(this, "AAYoutubeCompat").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    val last = Library.last
                    if (snapshot.id.isBlank() && last != null) {
                        send(Msg.OPEN, Bundle().apply {
                            putString("id", last.id)
                            putLong("sec", last.posSec)
                        })
                    } else send(Msg.PLAY)
                }
                override fun onPause() = send(Msg.PAUSE)
                override fun onSkipToNext() = send(Msg.NEXT)
                override fun onSkipToPrevious() = send(Msg.PREV)
                override fun onStop() = send(Msg.STOP)
                override fun onSeekTo(pos: Long) = send(Msg.SEEK, Bundle().apply { putLong("ms", pos) })
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    when {
                        mediaId?.startsWith("v:") == true ->
                            send(Msg.OPEN, Bundle().apply { putString("id", mediaId.removePrefix("v:")) })
                        mediaId?.startsWith("q:") == true ->
                            onPlayFromSearch(mediaId.removePrefix("q:"), extras)
                    }
                }
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    if (!query.isNullOrBlank()) send(Msg.SEARCH, Bundle().apply { putString("q", query) })
                }
            })
            setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(ACTIONS)
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
        val children = when (parentId) {
            ROOT -> listOf(folder("recent", "Recently played"), folder("favourites", "Favourites"))
            "recent" -> Library.recents().map { playable(it) }
            "favourites" -> Library.favourites().map { playable(it) }
            else -> emptyList()
        }
        result.sendResult(children.toMutableList())
    }

    private fun folder(id: String, title: String): MediaBrowserCompat.MediaItem =
        MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )

    private fun playable(item: Library.Item) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder().setMediaId("v:" + item.id)
            .setTitle(item.title).setSubtitle(item.artist).build(),
        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
    )

    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.sendResult(mutableListOf(MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder().setMediaId("q:" + query)
                .setTitle("Play search results").setSubtitle(query).build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )))
    }

    override fun onDestroy() {
        shuttingDown = true
        handler.removeCallbacksAndMessages(null)
        if (bound) runCatching { unbindService(connection) }
        bound = false
        outgoing = null
        pending.clear()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private companion object {
        const val ROOT = "root"
        const val ACTIONS = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
    }
}
