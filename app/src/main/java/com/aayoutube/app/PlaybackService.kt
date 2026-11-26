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
import android.os.PowerManager
import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat

/**
 * Android Auto media browser and media session. The WebView renderer is sandboxed;
 * the app's media service and WebView host run in the same application process.
 */
class PlaybackService : MediaBrowserServiceCompat() {
    private lateinit var session: MediaSessionCompat
    private var outgoing: Messenger? = null
    private var bound = false
    private val pending = ArrayDeque<Pair<Int, Bundle>>()
    private val maxPendingCommands = 32
    private val handler = Handler(Looper.getMainLooper())
    private var foreground = false
    // Phase transitions are logged as states arrive; health is sampled by its own
    // timer so a WebView that stops reporting is still visible in logcat.
    private var lastHealthElapsed = 0L
    private var lastCpuMs = 0L
    private val recentMediaCpu = ArrayDeque<Long>()
    private var lastPlaybackPhase = ""
    private var interruptedSince = 0L
    private fun tracePlaybackPhase(s: Snap) {
        val phase = ForegroundDemotePolicy.phase(s, recoveringPlayback)
        if (phase != lastPlaybackPhase) {
            AppLog.add("PLAYBACK", "transition $lastPlaybackPhase -> $phase foreground=$foreground bound=$bound")
            lastPlaybackPhase = phase
        }
    }
    private fun logHealth() {
        val now = android.os.SystemClock.elapsedRealtime()
        val cpu = android.os.Process.getElapsedCpuTime()
        // Main process only: the WebView renderer is a separate sandboxed UID.
        val cpuPercent = ForegroundDemotePolicy.cpuPercent(lastHealthElapsed, lastCpuMs, now, cpu)
        lastHealthElapsed = now
        lastCpuMs = cpu
        if (cpuPercent >= 0 && cpuPercent <= 1000) {
            recentMediaCpu.addLast(cpuPercent)
            if (recentMediaCpu.size > 10) recentMediaCpu.removeFirst()
        }
        val cpuAverage = if (recentMediaCpu.isEmpty()) -1 else recentMediaCpu.sum() / recentMediaCpu.size
        val cpuPeak = recentMediaCpu.maxOrNull() ?: -1
        val audio = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        AppLog.add("HEALTH", "phase=${ForegroundDemotePolicy.phase(snapshot, recoveringPlayback)} " +
            "mainCpuPct=$cpuPercent avgCpuPct=$cpuAverage peakCpuPct=$cpuPeak " +
            "musicActive=${audio.isMusicActive} screen=${power.isInteractive} " +
            "wakeHeld=${playbackWakeLock?.isHeld == true} wakeHeldMs=${wakeHeldMs(now)} " +
            "wakeAcquisitions=$wakeAcquisitions wakeStallReleases=$wakeStallReleases " +
            "mediaClock=${mediaClock.state(now)} clockBufferingMs=${mediaClock.totals(now).first} " +
            "clockStalledMs=${mediaClock.totals(now).second} " +
            "perfLevel=${PerformanceMonitor.level.value} foreground=$foreground bound=$bound")
    }
    private val healthTick = object : Runnable {
        override fun run() {
            if (shuttingDown || !foreground) return
            logHealth()
            scheduleDemoteIfNeeded(snapshot) // re-check even if no new state arrived
            // Bounded wake lock: refresh its timeout or end a stalled recovery's hold.
            updatePlaybackWakeLock(ForegroundDemotePolicy.wantsWakeLock(snapshot, recoveringPlayback))
            // Screen off: fewer diagnostic wake-ups (60 s on, 180 s off).
            handler.postDelayed(this, AdaptivePerformancePolicy.healthIntervalMs(PerformanceMonitor.screenOn()))
        }
    }
    private fun startHealthTicker() {
        handler.removeCallbacks(healthTick)
        handler.postDelayed(healthTick, 60_000L)
    }
    private fun scheduleDemoteIfNeeded(s: Snap) {
        handler.removeCallbacks(demoteForeground)
        if (!foreground || carAttached || quitRequested) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!ForegroundDemotePolicy.isInterrupted(s, recoveringPlayback)) interruptedSince = 0L
        else if (interruptedSince == 0L) interruptedSince = now
        val delay = ForegroundDemotePolicy.delayMs(s, recoveringPlayback, interruptedSince, now) ?: return
        handler.postDelayed(demoteForeground, delay)
    }
    private val resumePrefs by lazy {
        getSharedPreferences("background_playback_restore", Context.MODE_PRIVATE)
    }
    private var lastRestoreSavedAt = 0L

    /** Minimal, short-lived record used only after Android restarts this service.
     * Not used for reboot/autostart and never overrides explicit user intent.
     */
    private fun rememberPlaybackForSystemRestart(s: Snap) {
        if (s.userPaused || s.ended || s.id.isBlank() || quitRequested) {
            if (resumePrefs.getBoolean("active", false))
                resumePrefs.edit().clear().apply()
            return
        }
        if (!s.playing) return // transient Chromium pause is not user intent
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastRestoreSavedAt != 0L && now - lastRestoreSavedAt < 30_000L &&
            resumePrefs.getString("id", null) == s.id) return
        lastRestoreSavedAt = now
        resumePrefs.edit().putBoolean("active", true).putString("id", s.id)
            .putLong("updatedAt", System.currentTimeMillis()).apply()
    }

    private fun restoreSystemKilledPlayback() {
        val active = resumePrefs.getBoolean("active", false)
        val id = resumePrefs.getString("id", null)
        val savedAt = resumePrefs.getLong("updatedAt", 0L)
        // Only immediately recover a genuinely interrupted session. A stale
        // record must never start music hours after the user stopped using it.
        val age = System.currentTimeMillis() - savedAt
        if (!active || id.isNullOrBlank() || age !in 0L..300_000L ||
            !Features.current.backgroundPlay) {
            resumePrefs.edit().clear().apply()
            return
        }
        val saved = Library.last
        val sec = saved?.takeIf { it.id == id }?.posSec?.coerceAtLeast(0L) ?: 0L
        AppLog.add("MEDIA", "Restoring recent playback after Android service recreation")
        send(Msg.PLAY, Bundle().apply {
            putString("id", id)
            putLong("sec", sec)
        })
    }
    // A WebView is not a native MediaPlayer. Prevent CPU sleep while its
    // stream is playing or recovering, without keeping the display awake.
    private var playbackWakeLock: PowerManager.WakeLock? = null
    // Wake-lock audit: when the current hold began, total held time, and stalled-recovery bound.
    private var wakeAcquiredAt = 0L
    private var wakeRefreshedAt = 0L
    private var wakeHeldTotalMs = 0L
    private var wakeAcquisitions = 0
    private var wakeStallReleases = 0
    // Media clock progression: playing=true alone does not prove playback (stuck buffering reports it too).
    private val mediaClock = MediaClockWatch()
    private fun wakeHeldMs(now: Long) = wakeHeldTotalMs + currentHoldMs(now)
    /** A hold whose refreshes stopped ended at its timeout, not now. */
    private fun currentHoldMs(now: Long): Long = if (wakeAcquiredAt <= 0L) 0L
        else (minOf(now, wakeRefreshedAt + WakeLockPolicy.TIMEOUT_MS) - wakeAcquiredAt).coerceAtLeast(0L)

    private fun releaseWakeLock(reason: String) {
        playbackWakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        if (wakeAcquiredAt > 0L) {
            val held = currentHoldMs(android.os.SystemClock.elapsedRealtime())
            wakeHeldTotalMs += held
            AppLog.add("POWER", "Background audio CPU wake lock released reason=$reason heldMs=$held")
        }
        wakeAcquiredAt = 0L
        wakeRefreshedAt = 0L
    }

    /** Absolute (elapsedRealtime) time of the scheduled stall release; 0 = none. */
    private var stallReleaseAt = 0L
    private val stallRelease = Runnable {
        stallReleaseAt = 0L
        updatePlaybackWakeLock(ForegroundDemotePolicy.wantsWakeLock(snapshot, recoveringPlayback))
    }

    /**
     * Re-arms the stall release only when its deadline moved (clock progress pushes it later).
     * The handler's uptime clock runs because the wake lock itself keeps the CPU awake.
     */
    private fun scheduleStallRelease(now: Long) {
        val due = WakeLockPolicy.releaseDueAt(mediaClock.progressAt())
        if (due == stallReleaseAt) return
        handler.removeCallbacks(stallRelease)
        stallReleaseAt = due
        if (due > 0L) handler.postDelayed(stallRelease, WakeLockPolicy.delayUntil(due, now))
    }

    private fun cancelStallRelease() {
        handler.removeCallbacks(stallRelease)
        stallReleaseAt = 0L
    }

    private fun updatePlaybackWakeLock(active: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!active || quitRequested || shuttingDown) {
            mediaClock.onSample(snapshot.id, snapshot.posMs, wantsPlay = false, isBuffering = false, nowMs = now)
            cancelStallRelease()
            releaseWakeLock("not-wanted")
            return
        }
        // Wanted (playing or recovering) but the media clock has not moved: bounded by WakeLockPolicy.
        if (!WakeLockPolicy.hold(true, mediaClock.stalledSince(now), now)) {
            cancelStallRelease()
            if (playbackWakeLock?.isHeld == true) {
                wakeStallReleases++
                releaseWakeLock("stalled-recovery")
            }
            return
        }
        // The stall limit has its own deadline: the health tick may be 3 minutes apart (screen off).
        scheduleStallRelease(now)
        runCatching {
            val lock = playbackWakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AAYouTube:BackgroundAudio")
                .apply { setReferenceCounted(false) }
                .also { playbackWakeLock = it }
            if (!lock.isHeld) {
                // Never unbounded: the timeout ends the hold if refreshes ever stop.
                lock.acquire(WakeLockPolicy.TIMEOUT_MS)
                if (wakeAcquiredAt > 0L) wakeHeldTotalMs += currentHoldMs(now) // the previous hold timed out
                wakeAcquiredAt = now
                wakeRefreshedAt = now
                wakeAcquisitions++
                AppLog.add("POWER", "Background audio CPU wake lock acquired timeoutMs=${WakeLockPolicy.TIMEOUT_MS}")
            } else if (WakeLockPolicy.needsRefresh(wakeRefreshedAt, now)) {
                lock.acquire(WakeLockPolicy.TIMEOUT_MS)   // not reference counted: extends the timeout
                wakeRefreshedAt = now
            }
        }.onFailure { AppLog.add("POWER", "Audio wake lock unavailable: ${it.javaClass.simpleName}") }
    }
    // A transient WebView pause must not immediately drop foreground-media priority.
    // Android may throttle/kill the :web process before JS gets a chance to resume.
    private val demoteForeground = Runnable {
        if (foreground && !snapshot.playing && !recoveringPlayback && !carAttached) {
            AppLog.add("MEDIA", "Foreground grace period expired; playback still paused " +
                "phase=${ForegroundDemotePolicy.phase(snapshot, recoveringPlayback)}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            interruptedSince = 0L
            handler.removeCallbacks(healthTick)
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
        val artwork: String, val bitmapReady: Boolean,
        val playing: Boolean
    )
    private var lastNotification: NotificationKey? = null
    private var lastRecentId = ""
    private var lastSavedPosition = -1L
    private var lastSavedAt = 0L
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
        val queueChanged = queueGate.update(s.queue, retainDuringTransition =
            !s.userPaused && !s.ended && (s.buffering || s.recovering ||
                transportSkips.hasPending(android.os.SystemClock.elapsedRealtime())))
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
            // Pause, Stop, Quit, end and a new video save at once; playback every 15 s (30 s screen off).
            val savedNow = android.os.SystemClock.elapsedRealtime()
            if (PositionPersistPolicy.shouldSave(lastSavedId, lastSavedPosition, lastSavedAt, s, savedNow, PerformanceMonitor::screenOn)) {
                Library.saveLast(s)
                lastSavedPosition = s.posMs
                lastSavedId = s.id
                lastSavedAt = savedNow
            }
        }
        if (s.playing) recoveringPlayback = false
        rememberPlaybackForSystemRestart(s)
        mediaClock.onSample(s.id, s.posMs, ForegroundDemotePolicy.wantsWakeLock(s, recoveringPlayback), s.buffering,
            android.os.SystemClock.elapsedRealtime())
        updatePlaybackWakeLock(ForegroundDemotePolicy.wantsWakeLock(s, recoveringPlayback))
        tracePlaybackPhase(s)
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

    // Headphones / Bluetooth disconnected: pause like the YouTube app instead of
    // suddenly playing from the phone speaker. Treated as a deliberate pause.
    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!NoisyAudioPolicy.shouldPause(intent?.action, snapshot.playing, quitRequested,
                    recovering = recoveringPlayback || snapshot.recovering || snapshot.held)) return
            AppLog.add("MEDIA", "Audio output disconnected (headphones/Bluetooth): pausing")
            runCatching { session.controller.transportControls.pause() }
        }
    }
    private var noisyRegistered = false

    override fun onCreate() {
        super.onCreate()
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(this, noisyReceiver,
                android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyRegistered = true
        }
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
                    resumePrefs.edit().clear().apply()
                    transportSkips.clear()
                    recovery.cancel()
                    recoveringPlayback = false
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
                    } else if (transportSkips.hasPending(android.os.SystemClock.elapsedRealtime())) {
                        AppLog.add("MEDIA", "Next: pending native queue boundary; awaiting playback")
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
                    } else if (transportSkips.hasPending(now)) {
                        AppLog.add("MEDIA", "Previous: pending native queue boundary; awaiting playback")
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
                    resumePrefs.edit().clear().apply()
                    transportSkips.clear()
                    recovery.cancel()
                    recoveringPlayback = false
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
        resumePrefs.edit().clear().apply()
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
                if (!foreground) startHealthTicker()
                foreground = true
                lastNotification = null
                AppLog.add("MEDIA", "Foreground media service started; playing=${snapshot.playing}")
            }.onFailure {
                AppLog.add("MEDIA", "Foreground start failed: ${it.javaClass.simpleName}: ${it.message}")
                // Never leave a started service running without the foreground
                // priority Android requires for background media playback.
                // Pause the WebView and release any playback CPU wake lock.
                runCatching { WebHost.cmd("pause") }
                updatePlaybackWakeLock(false)
                handler.removeCallbacks(healthTick)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        when (intent?.action) {
            ACTION_CAR_ATTACHED -> {
                carAttached = true
                handler.removeCallbacks(demoteForeground)
                handler.removeCallbacks(idleStop)
                if (!quitRequested && (snapshot.playing || recoveringPlayback || snapshot.recovering)) {
                    // Reconnect to the existing renderer; never reopen a video just for a car attach.
                    ensureWeb()
                    if (bound && !awaitingRegistration) send(Msg.SYNC)
                }
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
        if (intent == null && !quitRequested) {
            AppLog.add("LIFECYCLE", "sticky service recreated after process/service stop")
            restoreSystemKilledPlayback()
        }
        // Publish the initial playing state even when the WebView Messenger has
        // not registered yet; future IPC reports will update position and metadata.
        if (initial != null && !quitRequested) publish(snapshot)
        handler.post { if (!shuttingDown) updateNotification(snapshot) }
        super.onStartCommand(intent, flags, startId)
        // When EMUI reclaims a started foreground service for memory pressure,
        // Android may recreate it later. This does not bypass user force-stop.
        return START_STICKY
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
        // EMUI may dispatch task removal during launcher/recents cleanup while
        // Chromium is momentarily silent. This is not a user Stop or Quit.
        // The existing idleStop timer performs bounded idle cleanup; stopping
        // here would remove the foreground service that protects screen-off play.
        AppLog.add("MEDIA", "Task removed; foreground=$foreground playing=${snapshot.playing} " +
            "recovering=$recoveringPlayback userPaused=${snapshot.userPaused} quit=$quitRequested")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (noisyRegistered) runCatching { unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
        AppLog.add("LIFECYCLE", "service destroyed playing=${snapshot.playing} recovering=$recoveringPlayback quit=$quitRequested")
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
        updatePlaybackWakeLock(false)
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun updateNotification(s: Snap) {
        handler.removeCallbacks(demoteForeground)
        val cover = MediaArtworkLoader.urlFor(s.art, s.id)
        val key = NotificationKey(s.id, s.title, s.artist, s.canNext, s.canPrev,
            cover, artworkLoader.bitmapFor(cover) != null, s.playing)
        if (!s.playing) {
            // Android 17's system media player uses the session state, but the
            // existing Notification action can retain an old icon until refreshed.
            // Reflect Pause immediately while foreground grace is still active.
            if (foreground && lastNotification != key) {
                runCatching {
                    getSystemService(android.app.NotificationManager::class.java).notify(
                        CarMediaNotificationManager.NOTIFICATION_ID,
                        notifications.playback(s, session.sessionToken, artworkLoader.bitmapFor(cover))
                    )
                    lastNotification = key
                }.onFailure {
                    AppLog.add("MEDIA", "Paused notification refresh failed: " + it.javaClass.simpleName)
                }
            }
            if (!recoveringPlayback) scheduleDemoteIfNeeded(s)
            return
        }
        if (foreground && lastNotification == key) return
        runCatching {
            startForeground(CarMediaNotificationManager.NOTIFICATION_ID,
                notifications.playback(s, session.sessionToken, artworkLoader.bitmapFor(cover)))
            if (!foreground) startHealthTicker()
            foreground = true
            interruptedSince = 0L
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
