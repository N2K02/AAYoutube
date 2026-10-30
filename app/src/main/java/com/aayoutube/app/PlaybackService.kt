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
    private val notifications = CarMediaNotificationManager(this)
    private val updateGate = MediaUpdateGate()
    private data class NotificationKey(
        val id: String, val title: String, val artist: String,
        val canNext: Boolean, val canPrev: Boolean
    )
    private var lastNotification: NotificationKey? = null
    private var lastRecentId = ""
    private var lastSavedPosition = -1L
    private var lastSavedId = ""
    private var idleSince = 0L
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
    private var registrationEpoch = 0
    private var awaitingRegistration = false
    private var recoveringPlayback = false
    private val retry = Runnable { if (!shuttingDown && !quitRequested && !bound) ensureWeb() }
    private val freeze = Runnable { if (!snapshot.playing && !recoveringPlayback && !quitRequested && outgoing != null) send(Msg.FREEZE) }
    private val idleStop = Runnable { if (!snapshot.playing && !recoveringPlayback && !quitRequested) stopSelf() }
    private var snapshot = Snap()
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == Msg.QUIT_ACK && quitRequested) {
            handler.removeCallbacks(finishQuit)
            finishQuit.run()
        } else if (message.what == Msg.REGISTER_ACK && !quitRequested) {
            // Only a response from the current binding can reset the retry budget.
            if (bound && outgoing != null && message.data.getInt("epoch", -1) == registrationEpoch) {
                awaitingRegistration = false
                retries = 0
                handler.removeCallbacks(retry)
            }
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
            handler.removeCallbacks(retry)
            registrationEpoch++
            awaitingRegistration = true
            send(Msg.REGISTER, Bundle().apply { putInt("epoch", registrationEpoch) }, replyTo = incoming)
            if (outgoing == null) return
            // Drain only the commands present at connection time. A failed send can
            // requeue its command; never spin indefinitely on a dead binder.
            val toDrain = pending.size
            for (index in 0 until toDrain) {
                if (outgoing == null || shuttingDown || quitRequested || pending.isEmpty()) break
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
        // Mark binding before invoking the framework: a synchronous failure must not
        // leave the service believing it still has a live connection.
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
        val wasBound = bound
        bound = false
        if (wasBound) runCatching { unbindService(connection) }
        if (!shuttingDown && !quitRequested && (snapshot.playing || recoveringPlayback) && snapshot.id.isNotBlank()) {
            recoveringPlayback = true
            idleSince = 0L
            pending.clear()
            pending.addFirst(Msg.OPEN to Bundle().apply {
                putString("id", snapshot.id)
                putLong("sec", snapshot.posMs.coerceAtLeast(0L) / 1000L)
            })
            snapshot = snapshot.copy(playing = false, buffering = true)
            publish(snapshot)
            scheduleRetry()
        } else if (!shuttingDown && !quitRequested && (pending.isNotEmpty() || awaitingRegistration)) {
            // A binder can die before registration or the first playback snapshot arrives.
            scheduleRetry()
        }
    }
    private fun send(command: Int, data: Bundle = Bundle(), replyTo: Messenger? = null) {
        if (shuttingDown || (quitRequested && command != Msg.QUIT)) return
        if (command != Msg.QUIT && command != Msg.STOP && command != Msg.PAUSE && command != Msg.FREEZE) ensureWeb()
        if (command == Msg.PAUSE || command == Msg.STOP || command == Msg.QUIT) {
            pending.clear()
            recoveringPlayback = false
        }
        val target = outgoing ?: run {
            if (command != Msg.REGISTER && command != Msg.QUIT && command != Msg.FREEZE && command != Msg.STOP && command != Msg.PAUSE) {
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
            if (command != Msg.REGISTER && command != Msg.QUIT && command != Msg.STOP && command != Msg.PAUSE && command != Msg.FREEZE) {
                if (pending.size >= maxPendingCommands) pending.removeFirst()
                pending.addLast(command to data)
            }
            lost()
        }
    }
    private fun publish(s: Snap) {
        val state = when {
            s.buffering -> PlaybackStateCompat.STATE_BUFFERING
            s.playing -> PlaybackStateCompat.STATE_PLAYING
            s.ended -> PlaybackStateCompat.STATE_STOPPED
            s.id.isNotEmpty() -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        val position = s.posMs.coerceAtLeast(0L)
        val speed = if (s.playing) s.rate.coerceAtLeast(0.1f) else 0f
        val actions = actionsFor(s)
        if (updateGate.playbackChanged(
                state, actions, position, speed, android.os.SystemClock.elapsedRealtime()
            )) {
            session.setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, position, speed)
                .build())
        }
        val artwork = s.art.takeIf { it.startsWith("https://") }.orEmpty()
        if (updateGate.metadataChanged(s.id, s.title, s.artist, artwork, s.durMs.coerceAtLeast(0L))) {
            session.setMetadata(MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, s.id)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, s.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, s.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, s.durMs.coerceAtLeast(0L))
                .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artwork)
                .build())
        }
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
        if (s.playing) recoveringPlayback = false
        if (quitRequested) {
            updateNotification(s)
            return
        }
        if (s.playing) {
            idleSince = 0L
            handler.removeCallbacks(freeze)
            handler.removeCallbacks(idleStop)
        } else if (recoveringPlayback) {
            // A disconnected WebView is not an idle playback session.
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
        notifications.createChannel()
        Library.init(this)
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
                override fun onPause() {
                    send(Msg.PAUSE)
                    snapshot = snapshot.copy(playing = false, buffering = false)
                    publish(snapshot)
                }
                override fun onSkipToNext() = send(Msg.NEXT)
                override fun onSkipToPrevious() = send(Msg.PREV)
                override fun onStop() {
                    send(Msg.STOP)
                    snapshot = snapshot.copy(playing = false, buffering = false)
                    publish(snapshot)
                }
                override fun onCustomAction(action: String?, extras: Bundle?) {
                    when (action) {
                        Commands.HELLO -> ensureWeb()
                        Commands.FAVOURITE -> {
                            Library.toggleFavourite(snapshot)
                            notifyChildrenChanged("favourites")
                        }
                        Commands.EXIT -> requestQuit()
                    }
                }
                override fun onSeekTo(pos: Long) = send(Msg.SEEK, Bundle().apply { putLong("ms", pos.coerceAtLeast(0L)) })
                override fun onFastForward() = onSeekTo((snapshot.posMs.coerceAtLeast(0L) + 30_000L).coerceAtMost(snapshot.durMs.takeIf { it > 0 } ?: Long.MAX_VALUE))
                override fun onRewind() = onSeekTo((snapshot.posMs - 10_000L).coerceAtLeast(0L))
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    when {
                        mediaId?.startsWith("v:") == true ->
                            send(Msg.OPEN, Bundle().apply { putString("id", mediaId.removePrefix("v:")) })
                        mediaId?.startsWith("q:") == true ->
                            onPlayFromSearch(mediaId.removePrefix("q:"), extras)
                    }
                }
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    if (query.isNullOrBlank()) onPlay()
                    else send(Msg.SEARCH, Bundle().apply { putString("q", query) })
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

    private fun requestQuit() {
        if (quitRequested || shuttingDown) return
        quitRequested = true
        pending.clear()
        recoveringPlayback = false
        awaitingRegistration = false
        handler.removeCallbacks(retry)
        handler.removeCallbacks(freeze)
        handler.removeCallbacks(idleStop)
        snapshot = snapshot.copy(playing = false, buffering = false)
        publish(snapshot)
        if (outgoing == null) ensureWeb()
        send(Msg.QUIT, replyTo = incoming)
        handler.removeCallbacks(finishQuit)
        handler.postDelayed(finishQuit, 1_500L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A startForegroundService caller must be promoted before WebView IPC responds.
        if (!foreground) {
            runCatching {
                startForeground(CarMediaNotificationManager.NOTIFICATION_ID, notifications.placeholder())
                foreground = true
                lastNotification = null
            }.onFailure { AppLog.add("MEDIA", "Foreground start failed: ${it.javaClass.simpleName}") }
        }
        if (intent?.action == ACTION_QUIT) requestQuit()
        // The placeholder is only a startup requirement, not an idle media card.
        handler.post { if (!shuttingDown) updateNotification(snapshot) }
        return super.onStartCommand(intent, flags, startId)
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
            .setTitle(item.title).setSubtitle(item.artist)
            .setIconUri(android.net.Uri.parse("https://i.ytimg.com/vi/${item.id}/hqdefault.jpg")).build(),
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
        if (!snapshot.playing && !snapshot.buffering && !recoveringPlayback) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        shuttingDown = true
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

    private fun updateNotification(s: Snap) {
        if (!s.playing) {
            lastNotification = null
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
            }
            return
        }
        val key = NotificationKey(s.id, s.title, s.artist, s.canNext, s.canPrev)
        if (foreground && lastNotification == key) return
        runCatching {
            startForeground(CarMediaNotificationManager.NOTIFICATION_ID,
                notifications.playback(s, session.sessionToken))
            foreground = true
            lastNotification = key
        }.onFailure {
            AppLog.add("MEDIA", "Foreground playback notification failed: ${it.javaClass.simpleName}")
        }
    }

    /** Match the controls to the actual YouTube player's capabilities. */
    private fun actionsFor(s: Snap): Long {
        var actions = ACTIONS
        if (s.canNext) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (s.canPrev) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if (!s.live && !s.ad && s.durMs > 0L) {
            actions = actions or PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND
        }
        return actions
    }

    companion object {
        const val ACTION_QUIT = "com.aayoutube.app.action.QUIT"
        private const val ROOT = "root"
        const val ACTIONS = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
    }
}
