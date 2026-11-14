package com.aayoutube.app

import android.content.Intent
import android.app.PendingIntent
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
    // A transient WebView pause must not immediately drop foreground-media priority.
    // Android may throttle/kill the :web process before JS gets a chance to resume.
    private val demoteForeground = Runnable {
        if (foreground && !snapshot.playing && !recoveringPlayback && !carAttached) {
            AppLog.add("MEDIA", "Foreground grace period expired; playback still paused")
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
    }
    private val notifications = CarMediaNotificationManager(this)
    private val updateGate = MediaUpdateGate()
    private val artworkLoader = MediaArtworkLoader {
        if (!shuttingDown) {
            updateGate.invalidateMetadata()
            lastNotification = null
            publish(snapshot)
        }
    }
    private val queueGate = QueueUpdateGate()
    private val transportSkips = TransportSkipState()
    private data class NotificationKey(
        val id: String, val title: String, val artist: String,
        val canNext: Boolean, val canPrev: Boolean,
        val artwork: String, val bitmapReady: Boolean
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
    private val recovery = PlaybackRecovery()
    private val terminalIntent = TerminalTransportIntent()
    /** A binder connection alone is not evidence that the WebView can accept commands. */
    private val registrationTimeout = Runnable {
        if (!shuttingDown && !quitRequested && awaitingRegistration && bound) {
            AppLog.add("MEDIA", "WebView registration timed out; reconnecting")
            lost()
        }
    }
    private val retry = Runnable { if (!shuttingDown && !quitRequested && !bound) ensureWeb() }
    private val freeze = Runnable { if (!snapshot.playing && !recoveringPlayback && !quitRequested && outgoing != null) send(Msg.FREEZE) }
    private val idleStop = Runnable { if (!snapshot.playing && !recoveringPlayback && !quitRequested && !carAttached) stopSelf() }
    /** As AABrowser's ForegroundService: foreground for as long as the car screen exists. */
    private var carAttached = false
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
                handler.removeCallbacks(registrationTimeout)
                // Pause and Stop may have been pressed while :web was absent.
                // Replay those first, before asking for a player snapshot.
                terminalIntent.pendingCommand?.let { pending ->
                    send(pending, Bundle().apply {
                        putLong("terminalId", terminalIntent.pendingId ?: 0L)
                    })
                }
                drainPending()
                send(Msg.SYNC)
            }
        } else if (message.what == Msg.STATE && !quitRequested && validWebMessage(message)) {
            val fresh = Snap.from(message.data)
            // A page-originated user Pause is still authoritative, but a
            // transport Pause/Stop clears only after its explicit IPC ACK.
            // Otherwise an old paused snapshot could falsely acknowledge Stop.
            // A user may have pressed Pause on YouTube while the IPC link was
            // unavailable. Do not replay a stale, pre-disconnect play intent.
            if (fresh.userPaused) recovery.cancel()
            val resume = recovery.onExistingPlayer(fresh)
            recoveringPlayback = recovery.active || fresh.recovering
            transportSkips.onPlaybackState(fresh.id, fresh.playing)
            snapshot = fresh
            publish(fresh)
            if (resume) send(Msg.PLAY)
        } else if (message.what == Msg.TRANSPORT_ACK && !quitRequested && validWebMessage(message)) {
            terminalIntent.acknowledge(
                message.data.getInt("command", -1),
                message.data.getLong("terminalId", -1L)
            )
        } else if (message.what == Msg.PLAYER_MISSING && !quitRequested && validWebMessage(message)) {
            // OPEN is allowed only when the WebView confirms the player is missing.
            val wanted = recovery.onMissingPlayer()
            recoveringPlayback = false
            if (wanted != null) {
                send(Msg.OPEN, Bundle().apply {
                    putString("id", wanted.id)
                    putLong("sec", wanted.posMs.coerceAtLeast(0L) / 1_000L)
                })
            }
        } else if (message.what == Msg.RENDERER_GONE && !quitRequested && validWebMessage(message)) {
            val previous = Snap.from(message.data)
            recoveringPlayback = recovery.onRendererGone(previous)
            snapshot = previous.copy(playing = false, buffering = recoveringPlayback)
            publish(snapshot)
        }
        true
    })
    private var connection: ServiceConnection? = null

    private fun validWebMessage(message: Message): Boolean =
        bound && !awaitingRegistration && outgoing != null &&
            message.data.getInt("epoch", -1) == registrationEpoch

    private fun newConnection(): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (connection !== this || shuttingDown) return
            outgoing = Messenger(binder)
            if (quitRequested) {
                send(Msg.QUIT, replyTo = incoming)
                handler.removeCallbacks(finishQuit)
                handler.postDelayed(finishQuit, 1_500L)
                return
            }
            handler.removeCallbacks(retry)
            handler.removeCallbacks(registrationTimeout)
            registrationEpoch++
            awaitingRegistration = true
            send(Msg.REGISTER, Bundle().apply { putInt("epoch", registrationEpoch) }, replyTo = incoming)
            if (outgoing == null) return
            handler.postDelayed(registrationTimeout, 5_000L)
            // Wait for REGISTER_ACK before replaying commands to the new process.
        }
        override fun onServiceDisconnected(name: ComponentName) {
            if (connection === this) lost()
        }
        override fun onBindingDied(name: ComponentName) {
            if (connection === this) lost()
        }
        override fun onNullBinding(name: ComponentName) {
            if (connection === this) lost()
        }
    }
    /** Replay only the commands queued when registration completed; failed sends
     * can requeue a command without making this loop spin indefinitely. */
    private fun drainPending() {
        val toDrain = pending.size
        for (index in 0 until toDrain) {
            if (outgoing == null || shuttingDown || quitRequested || pending.isEmpty()) break
            val (command, data) = pending.removeFirst()
            send(command, data)
        }
    }

    private fun ensureWeb() {
        if (bound || shuttingDown) return
        // Mark binding before invoking the framework: a synchronous failure must not
        // leave the service believing it still has a live connection.
        val attempt = newConnection()
        connection = attempt
        bound = runCatching {
            bindService(Intent(this, WebHostService::class.java), attempt, BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            if (connection === attempt) connection = null
            scheduleRetry()
        }
    }
    private fun scheduleRetry() {
        val hasWork = recovery.active || pending.isNotEmpty() ||
            terminalIntent.pendingCommand != null || snapshot.playing
        if (shuttingDown || quitRequested || !MediaRetryPolicy.shouldRetry(retries, hasWork)) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, MediaRetryPolicy.delayMs(retries))
        retries = (retries + 1).coerceAtMost(6)
    }
    private fun lost() {
        handler.removeCallbacks(registrationTimeout)
        outgoing = null
        val wasBound = bound
        val previous = connection
        connection = null
        bound = false
        if (wasBound && previous != null) runCatching { unbindService(previous) }
        if (!shuttingDown && !quitRequested && recovery.onLinkLost(snapshot)) {
            recoveringPlayback = true
            idleSince = 0L
            // The WebView might still be playing; ask it before any navigation.
            snapshot = snapshot.copy(playing = false, buffering = true)
            publish(snapshot)
            scheduleRetry()
        } else if (!shuttingDown && !quitRequested &&
            (pending.isNotEmpty() || awaitingRegistration || terminalIntent.pendingCommand != null)) {
            // A binder can die before registration or the first playback snapshot arrives.
            scheduleRetry()
        }
    }
    private fun send(command: Int, data: Bundle = Bundle(), replyTo: Messenger? = null) {
        if (shuttingDown || (quitRequested && command != Msg.QUIT)) return
        val replayId = data.getLong("terminalId", 0L)
        if ((command == Msg.PAUSE || command == Msg.STOP) && replayId > 0L) {
            if (terminalIntent.pendingCommand != command ||
                terminalIntent.pendingId != replayId) return
        } else {
            val id = terminalIntent.onCommand(command)
            if (command == Msg.PAUSE || command == Msg.STOP)
                id?.let { data.putLong("terminalId", it) }
        }
        // A terminal transport action must not disappear merely because
        // the WebView Messenger was temporarily disconnected.
        if ((command == Msg.PAUSE || command == Msg.STOP) &&
            !bound && snapshot.id.isNotBlank()) ensureWeb()
        if (command != Msg.QUIT && command != Msg.STOP && command != Msg.PAUSE && command != Msg.FREEZE) ensureWeb()
        if (command == Msg.PAUSE || command == Msg.STOP || command == Msg.QUIT ||
            command == Msg.OPEN || command == Msg.SEARCH) {
            recovery.cancel()
            recoveringPlayback = false
            if (command == Msg.PAUSE || command == Msg.STOP || command == Msg.QUIT) pending.clear()
        }
        if (command == Msg.OPEN && pending.isNotEmpty()) {
            // A fast sequence of wheel presses during WebView re-registration
            // has only one final destination. Never replay obsolete OPENs.
            val before = pending.size
            pending.removeAll { it.first == Msg.OPEN || it.first == Msg.NEXT || it.first == Msg.PREV }
            if (before != pending.size)
                AppLog.add("MEDIA", "Superseded ${before - pending.size} stale queued navigation commands")
        }
        // Never issue queued play/open/search commands before WebHost acknowledged
        // its new Messenger. Pause, stop and Quit must bypass this barrier.
        val target = outgoing?.takeUnless {
            awaitingRegistration && command != Msg.REGISTER && command != Msg.QUIT &&
                command != Msg.PAUSE && command != Msg.STOP && command != Msg.FREEZE
        } ?: run {
            if (command != Msg.REGISTER && command != Msg.QUIT && command != Msg.FREEZE && command != Msg.STOP && command != Msg.PAUSE) {
                if (pending.size >= maxPendingCommands) pending.removeFirst()
                pending.addLast(command to data)
            }
            return
        }
        if (command == Msg.OPEN || command == Msg.NEXT || command == Msg.PREV) {
            AppLog.add("MEDIA", "IPC dispatch command=$command target=${data.getString("id").orEmpty()} epoch=$registrationEpoch")
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
            s.playing -> PlaybackStateCompat.STATE_PLAYING
            s.buffering || s.recovering -> PlaybackStateCompat.STATE_BUFFERING
            s.ended -> PlaybackStateCompat.STATE_STOPPED
            s.id.isNotEmpty() -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        val position = s.posMs.coerceAtLeast(0L)
        val speed = when {
            !s.playing -> 0f
            !s.rate.isFinite() -> 1f
            else -> s.rate.coerceIn(0.1f, 4f)
        }
        val queueChanged = queueGate.update(s.queue)
        if (queueChanged) {
            // Android Auto's Now Playing queue is a native MediaSession queue, not
            // just browse-only "Up next" children.
            val entries = queueGate.items().mapIndexed { i, item ->
                MediaSessionCompat.QueueItem(
                    MediaDescriptionCompat.Builder()
                        .setMediaId("v:" + item.id)
                        .setTitle(item.title)
                        .setSubtitle(item.artist)
                        .setIconUri(android.net.Uri.parse("https://i.ytimg.com/vi/${item.id}/hqdefault.jpg"))
                        .build(),
                    i.toLong(),
                )
            }
            session.setQueue(entries)
            session.setQueueTitle("Up next")
            notifyChildrenChanged(ROOT)
            notifyChildrenChanged("queue")
        }
        val actions = actionsFor(s)
        val playbackChanged = updateGate.playbackChanged(
            state, actions, position, speed, android.os.SystemClock.elapsedRealtime()
        )
        if (playbackChanged || queueChanged) {
            val items = queueGate.items()
            val activeIndex = items.indexOfFirst { it.id == s.id }
            session.setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, position, speed)
                .setActiveQueueItemId(if (activeIndex >= 0) activeIndex.toLong()
                    else MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong())
                .build())
        }
        val artwork = MediaArtworkLoader.urlFor(s.art, s.id)
        artworkLoader.request(artwork)
        if (updateGate.metadataChanged(s.id, s.title, s.artist, artwork, s.durMs.coerceAtLeast(0L))) {
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, s.id)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, s.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, s.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, s.durMs.coerceAtLeast(0L))
                .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artwork)
            artworkLoader.bitmapFor(artwork)?.let {
                metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
                metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            }
            session.setMetadata(metadata.build())
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
            // Android Auto can redirect media-card taps to the app's video
            // projection when the same package exports both entry points.
            // Follow AABrowser: keep the car's native media card in place.
            setSessionActivity(PendingIntent.getActivity(
                this@PlaybackService, 0,
                Intent(this@PlaybackService, NoOpMediaActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    val target = MediaResumeTarget.select(snapshot, Library.last)
                    if (snapshot.id.isBlank() && target != null) {
                        send(Msg.OPEN, Bundle().apply {
                            putString("id", target.id)
                            putLong("sec", target.sec)
                        })
                    } else {
                        // When a hidden STOP released Chromium, the native
                        // MediaSession may still remember the selected video.
                        // Send its identity so a WebHost with no WebView can
                        // restore the player instead of dropping Play.
                        send(Msg.PLAY, Bundle().apply {
                            if (target != null) {
                                putString("id", target.id)
                                putLong("sec", target.sec)
                            }
                        })
                    }
                }
                override fun onPause() {
                    transportSkips.clear()
                    send(Msg.PAUSE)
                    snapshot = snapshot.copy(playing = false, userPaused = true, buffering = false, recovering = false)
                    publish(snapshot)
                }
                override fun onSkipToNext() {
                    if (Features.current.skipSeeks) {
                        onSeekTo(VideoFitPolicy.seekTarget(snapshot.posMs, snapshot.durMs, forward = true)); return
                    }
                    // Native queue is authoritative when it contains the active
                    // video; otherwise the WebView handles the fallback.
                    val target = transportSkips.next(snapshot.id, queueGate.items(), android.os.SystemClock.elapsedRealtime())
                    if (target != null) {
                        AppLog.add("MEDIA", "Next: native queue target=$target from=${snapshot.id}")
                        send(Msg.OPEN, Bundle().apply { putString("id", target) })
                    } else {
                        AppLog.add("MEDIA", "Next: YouTube player fallback")
                        send(Msg.NEXT)
                    }
                }
                override fun onSkipToPrevious() {
                    if (Features.current.skipSeeks) {
                        onSeekTo(VideoFitPolicy.seekTarget(snapshot.posMs, snapshot.durMs, forward = false)); return
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (transportSkips.shouldRestartPrevious(snapshot.id, snapshot.posMs, now)) {
                        AppLog.add("MEDIA", "Previous: restarting current video after 5s")
                        send(Msg.SEEK, Bundle().apply { putLong("ms", 0L) })
                        return
                    }
                    val target = transportSkips.previous(snapshot.id, queueGate.items(), now)
                    if (target != null) {
                        AppLog.add("MEDIA", "Previous: native queue target=$target from=${snapshot.id}")
                        send(Msg.OPEN, Bundle().apply { putString("id", target) })
                    } else {
                        AppLog.add("MEDIA", "Previous: YouTube player fallback")
                        send(Msg.PREV)
                    }
                }
                override fun onSkipToQueueItem(id: Long) {
                    val item = MusicQueueNavigation.at(id, queueGate.items()) ?: return
                    transportSkips.clear()
                    send(Msg.OPEN, Bundle().apply { putString("id", item) })
                }
                override fun onStop() {
                    transportSkips.clear()
                    send(Msg.STOP)
                    snapshot = snapshot.copy(playing = false, userPaused = true, buffering = false, recovering = false)
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
                    transportSkips.clear()
                    when {
                        mediaId?.startsWith("v:") == true ->
                            send(Msg.OPEN, Bundle().apply { putString("id", mediaId.removePrefix("v:")) })
                        mediaId?.startsWith("q:") == true ->
                            onPlayFromSearch(mediaId.removePrefix("q:"), extras)
                    }
                }
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    transportSkips.clear()
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
        recovery.cancel()
        awaitingRegistration = false
        handler.removeCallbacks(retry)
        handler.removeCallbacks(registrationTimeout)
        handler.removeCallbacks(freeze)
        handler.removeCallbacks(idleStop)
        handler.removeCallbacks(demoteForeground)
        snapshot = snapshot.copy(playing = false, buffering = false, recovering = false, userPaused = true)
        publish(snapshot)
        if (outgoing == null) ensureWeb()
        send(Msg.QUIT, replyTo = incoming)
        handler.removeCallbacks(finishQuit)
        handler.postDelayed(finishQuit, 1_500L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A MediaBrowserService can be BOUND without ever being STARTED.
        // Explicitly start it when WebHost first reports playback, so the OS grants
        // foreground-media priority to both the service and its bound WebView process.
        val startingPlayback = intent?.action == ACTION_PLAYBACK
        val incoming = if (startingPlayback) intent?.getBundleExtra(EXTRA_PLAYBACK)?.let(Snap::from)
            else null
        val initial = incoming?.takeIf { it.playing && snapshot.id.isBlank() }
        if (initial != null) snapshot = initial
        // startForegroundService callers must be promoted promptly, before WebView IPC.
        if (!foreground) {
            runCatching {
                val notification = if (snapshot.playing) {
                    notifications.playback(snapshot, session.sessionToken,
                        artworkLoader.bitmapFor(MediaArtworkLoader.urlFor(snapshot.art, snapshot.id)))
                } else notifications.placeholder()
                startForeground(CarMediaNotificationManager.NOTIFICATION_ID, notification)
                foreground = true
                lastNotification = null
                AppLog.add("MEDIA", "Foreground media service started; playing=${snapshot.playing}")
            }.onFailure {
                AppLog.add("MEDIA", "Foreground start failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        when (intent?.action) {
            ACTION_CAR_ATTACHED -> {
                carAttached = true
                handler.removeCallbacks(demoteForeground)
                handler.removeCallbacks(idleStop)
            }
            ACTION_CAR_DETACHED -> {
                carAttached = false
                // Back to the normal rules: paused playback gives up the foreground.
                if (!snapshot.playing && !recoveringPlayback) {
                    handler.postDelayed(demoteForeground, 8_000L)
                    handler.postDelayed(idleStop, 900_000L)
                }
            }
        }
        if (intent?.action == ACTION_QUIT) requestQuit()
        // Publish the initial playing state even when the WebView Messenger has
        // not registered yet; future IPC reports will update position and metadata.
        if (initial != null && !quitRequested) publish(snapshot)
        handler.post { if (!shuttingDown) updateNotification(snapshot) }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        AppLog.add("MEDIA", "Browse root requested from " + clientPackageName)
        return BrowserRoot(ROOT, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val children = when (parentId) {
            ROOT -> listOfNotNull(
                folder("recent", "Recently played"),
                folder("favourites", "Favourites"),
                folder("queue", "Up next").takeIf { queueGate.items().isNotEmpty() }
            )
            "recent" -> Library.recents().map { playable(it) }
            "favourites" -> Library.favourites().map { playable(it) }
            "queue" -> queueGate.items().map { playable(it.id, it.title, it.artist) }
            else -> emptyList()
        }
        result.sendResult(children.toMutableList())
    }

    private fun folder(id: String, title: String): MediaBrowserCompat.MediaItem =
        MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )

    private fun playable(item: Library.Item) = playable(item.id, item.title, item.artist)

    private fun playable(id: String, title: String, artist: String) =
        MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder().setMediaId("v:" + id)
                .setTitle(title).setSubtitle(artist)
                .setIconUri(android.net.Uri.parse("https://i.ytimg.com/vi/${id}/hqdefault.jpg")).build(),
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
        if (!snapshot.playing && !snapshot.buffering && !recoveringPlayback &&
            terminalIntent.pendingCommand == null) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        shuttingDown = true
        handler.removeCallbacksAndMessages(null)
        val activeConnection = connection
        connection = null
        if (bound && activeConnection != null) runCatching { unbindService(activeConnection) }
        bound = false
        outgoing = null
        pending.clear()
        recovery.cancel()
        terminalIntent.clear()
        artworkLoader.shutdown()
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun updateNotification(s: Snap) {
        handler.removeCallbacks(demoteForeground)
        if (!s.playing) {
            lastNotification = null
            if (foreground && !recoveringPlayback) {
                // Explicit pause is still reflected immediately in MediaSession state.
                // Hold the foreground briefly for an unexpected background pause.
                handler.postDelayed(demoteForeground, 8_000L)
            }
            return
        }
        val cover = MediaArtworkLoader.urlFor(s.art, s.id)
        val key = NotificationKey(s.id, s.title, s.artist, s.canNext, s.canPrev,
            cover, artworkLoader.bitmapFor(cover) != null)
        if (foreground && lastNotification == key) return
        runCatching {
            startForeground(CarMediaNotificationManager.NOTIFICATION_ID,
                notifications.playback(s, session.sessionToken, artworkLoader.bitmapFor(cover)))
            foreground = true
            lastNotification = key
            AppLog.add("MEDIA", "Playback notification foreground=true id=${s.id} " +
                "channel=${notifications.channelStatus()}")
        }.onFailure {
            AppLog.add("MEDIA", "Foreground playback notification failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** Android Auto hides buttons not declared in PlaybackStateCompat.
     * YouTube frequently reports canNext/canPrev=false even with an active
     * playlist; native skip handling falls back to our captured queue. */
    private fun actionsFor(s: Snap): Long {
        var actions = ACTIONS
        if (s.id.isNotBlank()) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            if (queueGate.items().isNotEmpty()) {
                actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
            }
        }
        if (!s.live && !s.ad && s.durMs > 0L) {
            actions = actions or PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND
        }
        return actions
    }

    companion object {
        const val ACTION_PLAYBACK = "com.aayoutube.app.action.PLAYBACK"
        const val EXTRA_PLAYBACK = "com.aayoutube.app.extra.PLAYBACK"
        const val ACTION_QUIT = "com.aayoutube.app.action.QUIT"
        const val ACTION_CAR_ATTACHED = "com.aayoutube.app.action.CAR_ATTACHED"
        const val ACTION_CAR_DETACHED = "com.aayoutube.app.action.CAR_DETACHED"

        /** Called by the car screen when it is created / destroyed. */
        fun carAttached(context: android.content.Context, attached: Boolean) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, PlaybackService::class.java)
                        .setAction(if (attached) ACTION_CAR_ATTACHED else ACTION_CAR_DETACHED))
            }.onFailure { AppLog.add("MEDIA", "Car foreground request failed: " + it.javaClass.simpleName) }
        }
        private const val ROOT = "root"
        const val ACTIONS = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
    }
}
