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
 * Android Auto media browser and media session; the WebView runs in a separate process.
 */
class PlaybackService : MediaBrowserServiceCompat() {
    private lateinit var session: MediaSessionCompat
    private var outgoing: Messenger? = null
    private var bound = false
    private val pending = ArrayDeque<Pair<Int, Bundle>>()
    private val maxPendingCommands = 32
    private val handler = Handler(Looper.getMainLooper())
    private var foreground = false
    private var startedForeground = false
    private val notificationId = 101
    private var lastRecentId = ""
    private var lastSavedPosition = -1L
    private var lastSavedId = ""
    private var idleSince = 0L
    private var lastPlaying = false
    private var audioManager: android.media.AudioManager? = null
    private var focusRequest: android.media.AudioFocusRequest? = null
    private var focusHeld = false
    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                send(Msg.PAUSE)
                snapshot = snapshot.copy(playing = false)
                publish(snapshot)
            }
        }
    }
    private val channelId = "aay_media"
    private var shuttingDown = false
    private var quitRequested = false
    private val finishQuit = Runnable {
        if (quitRequested) {
            shuttingDown = true
            pending.clear()
            stopSelf()
        }
    }
    private var retries = 0
    private val retry = Runnable { if (!shuttingDown && !bound) ensureWeb() }
    private val freeze = Runnable { if (!snapshot.playing && outgoing != null) send(Msg.FREEZE) }
    private val idleStop = Runnable { if (!snapshot.playing) stopSelf() }
    private var snapshot = Snap()
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == Msg.QUIT_ACK && quitRequested) {
            handler.removeCallbacks(finishQuit)
            finishQuit.run()
        } else if (message.what == Msg.STATE && !quitRequested) {
            snapshot = Snap.from(message.data)
            publish(snapshot)
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            outgoing = Messenger(binder)
            if (quitRequested) {
                send(Msg.QUIT, replyTo = incoming)
                handler.removeCallbacks(finishQuit)
                handler.postDelayed(finishQuit, 1_500L)
                return
            }
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
        if (shuttingDown || quitRequested || retries >= 4) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, 1_000L shl retries++)
    }
    private fun lost() {
        outgoing = null
        if (bound) runCatching { unbindService(connection) }
        bound = false
        if (!shuttingDown && !quitRequested && snapshot.playing && snapshot.id.isNotBlank()) {
            pending.clear()
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
        if (shuttingDown || (quitRequested && command != Msg.QUIT)) return
        if (command != Msg.QUIT && command != Msg.STOP && command != Msg.PAUSE && command != Msg.FREEZE) ensureWeb()
        val target = outgoing ?: run {
            if (command != Msg.REGISTER && command != Msg.QUIT && command != Msg.FREEZE && command != Msg.STOP && command != Msg.PAUSE) {
                if (command == Msg.PAUSE || command == Msg.STOP || command == Msg.QUIT) pending.clear()
                if (pending.size >= maxPendingCommands) pending.removeFirst()
                pending.addLast(command to data)
            }
            return
        }
        runCatching {
            target.send(Message.obtain(null, command).apply {
                this.data = data
                this.replyTo = replyTo
            })
        }.onFailure {
            AppLog.add("MEDIA", "Compat command failed: " + it.javaClass.simpleName)
            if (command != Msg.REGISTER && command != Msg.QUIT) {
                if (pending.size >= maxPendingCommands) pending.removeFirst()
                pending.addLast(command to data)
            }
            lost()
        }
    }
    private fun publish(s: Snap) {
        if (s.playing != lastPlaying) {
            lastPlaying = s.playing
            if (s.playing) requestFocus() else releaseFocus()
        }
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
        if (s.id.isNotBlank()) {
            if (s.playing && lastRecentId != s.id) {
                Library.noteStarted(s)
                lastRecentId = s.id
                notifyChildrenChanged("recent")
            }
            if (lastSavedId != s.id || lastSavedPosition < 0 || kotlin.math.abs(lastSavedPosition - s.posMs) >= 5_000L || (!s.playing && lastSavedPosition != s.posMs)) {
                Library.saveLast(s)
                lastSavedPosition = s.posMs
                lastSavedId = s.id
            }
        }
        if (quitRequested) return
        if (s.playing) {
            idleSince = 0L
            handler.removeCallbacks(freeze)
            handler.removeCallbacks(idleStop)
        } else if (idleSince == 0L) {
            idleSince = android.os.SystemClock.elapsedRealtime()
            handler.postDelayed(freeze, 120_000L)
            handler.postDelayed(idleStop, 900_000L)
        }
        updateNotification(s)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Library.init(this)
        audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        session = MediaSessionCompat(this, "AAYoutube").apply {
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
                override fun onStop() {
                    send(Msg.STOP)
                    snapshot = snapshot.copy(playing = false)
                    publish(snapshot)
                }
                override fun onCustomAction(action: String?, extras: Bundle?) {
                    when (action) {
                        Commands.HELLO -> ensureWeb()
                        Commands.FAVOURITE -> {
                            Library.toggleFavourite(snapshot)
                            notifyChildrenChanged("favourites")
                        }
                        Commands.EXIT -> {
                            quitRequested = true
                            pending.clear()
                            handler.removeCallbacks(retry)
                            handler.removeCallbacks(freeze)
                            handler.removeCallbacks(idleStop)
                            snapshot = snapshot.copy(playing = false)
                            publish(snapshot)
                            if (outgoing == null) ensureWeb()
                            send(Msg.QUIT, replyTo = incoming)
                            handler.removeCallbacks(finishQuit)
                            handler.postDelayed(finishQuit, 1_500L)
                        }
                    }
                }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A startForegroundService caller must be promoted before WebView IPC responds.
        if (!foreground) {
            runCatching {
                startForeground(notificationId, placeholderNotification())
                foreground = true
                startedForeground = true
            }.onFailure { AppLog.add("MEDIA", "Foreground start failed: ${it.javaClass.simpleName}") }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun placeholderNotification(): android.app.Notification =
        androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AAYoutube")
            .setContentText("Preparing playback")
            .setOnlyAlertOnce(true)
            .setContentIntent(android.app.PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
            .build()

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

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!snapshot.playing) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        shuttingDown = true
        releaseFocus()
        handler.removeCallbacksAndMessages(null)
        if (bound) runCatching { unbindService(connection) }
        bound = false
        outgoing = null
        pending.clear()
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun requestFocus() {
        if (focusHeld) return
        val manager = audioManager ?: return
        val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        if (manager.requestAudioFocus(request) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
            focusHeld = true
        }
    }

    private fun releaseFocus() {
        val manager = audioManager ?: return
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
        focusHeld = false
    }

    private fun mediaButton(keyCode: Int): android.app.PendingIntent =
        androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this, keyCode.toLong())

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(android.app.NotificationChannel(
                channelId, "AAYoutube playback", android.app.NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun updateNotification(s: Snap) {
        if (!s.playing && !foreground) return
        val launch = android.app.PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(s.title.ifBlank { "AAYoutube" })
            .setContentText(s.artist)
            .setContentIntent(launch)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "Previous", mediaButton(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(if (s.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (s.playing) "Pause" else "Play", mediaButton(if (s.playing) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY))
            .addAction(android.R.drawable.ic_media_next, "Next", mediaButton(PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setOngoing(s.playing)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        if (s.playing) {
            startForeground(notificationId, notification)
            foreground = true
            startedForeground = false
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            startedForeground = false
        }
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
