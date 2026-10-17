package com.aayoutube.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The media process: media session, notification and everything Android Auto talks to.
 *
 * It has no WebView and no screens. Its main thread only answers from memory and sends
 * fire-and-forget messages to the web process ([HostLink]), so Android Auto's calls (browse,
 * search, play, next) always return at once, whatever the WebView is doing.
 *
 * Battery: a partial wake lock is held only while a video is actually playing; after 2 minutes
 * paused the page is frozen (timers and scripts stop), and after 15 minutes the web process is
 * released and this service stops.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private lateinit var bridge: BridgePlayer
    private lateinit var link: HostLink
    private lateinit var session: MediaLibrarySession
    private val main = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastRecorded = ""
    private var lastSavedAt = 0L
    private var buttonsKey = ""

    private val freezeTask = Runnable { link.freeze() }
    private val stopTask = Runnable { exitApp() }

    override fun onCreate() {
        super.onCreate()
        Library.init(this)

        bridge = BridgePlayer(Looper.getMainLooper())
        link = HostLink(this, bridge)
        bridge.host = link
        link.onSnapshot = ::onSnapshot

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, ControlsPlayer(bridge), LibraryCallback())
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(buttons())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!bridge.isPlaying()) exitApp()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        setWake(false)
        link.release()
        session.release()
        bridge.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- what the page reports

    private fun onSnapshot(s: Snap) {
        val idChanged = s.id != bridge.snap.id
        val wasPlaying = bridge.snap.playing
        bridge.update(s)

        if (s.id.isNotEmpty() && s.title.isNotEmpty() && s.id != lastRecorded) {
            lastRecorded = s.id
            Library.noteStarted(s)
        }
        // Remember the position when something notable happens, and every ~30 s while playing.
        val now = SystemClock.elapsedRealtime()
        if (s.id.isNotEmpty() && (idChanged || (wasPlaying && !s.playing) || (s.playing && now - lastSavedAt > 25_000))) {
            lastSavedAt = now
            Library.saveLast(s)
        }
        setWake(s.playing)
        scheduleIdle(s.playing)
        refreshButtons()
    }

    private fun setWake(on: Boolean) {
        if (on) {
            if (wakeLock?.isHeld != true) {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AAYoutube:play")
                    .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
            }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    private fun scheduleIdle(playing: Boolean) {
        main.removeCallbacks(freezeTask)
        main.removeCallbacks(stopTask)
        if (!playing) {
            main.postDelayed(freezeTask, FREEZE_AFTER_MS)
            main.postDelayed(stopTask, STOP_AFTER_MS)
        }
    }

    /** Exit button, or nothing left to do: stop playback, let the web process go, stop the service. */
    private fun exitApp() {
        main.removeCallbacks(freezeTask)
        main.removeCallbacks(stopTask)
        link.stop()
        link.release()
        bridge.reset()
        setWake(false)
        stopSelf()
    }

    // ---------------------------------------------------------------- notification buttons

    private fun buttons(): List<CommandButton> {
        val s = bridge.snap
        val fav = s.id.isNotEmpty() && Library.isFavourite(s.id)
        return listOfNotNull(
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                .setDisplayName("Previous")
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .setDisplayName("Next")
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
            if (s.id.isEmpty()) null else
                CommandButton.Builder(if (fav) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                    .setCustomIconResId(if (fav) R.drawable.ic_heart_filled else R.drawable.ic_heart)
                    .setSessionCommand(Commands.FAVOURITE)
                    .setDisplayName(if (fav) "Remove from favourites" else "Add to favourites")
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build(),
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setCustomIconResId(R.drawable.ic_exit)
                .setSessionCommand(Commands.EXIT)
                .setDisplayName("Exit")
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    private fun refreshButtons() {
        val s = bridge.snap
        val key = s.id + Library.isFavourite(s.id)
        if (key == buttonsKey) return
        buttonsKey = key
        session.setMediaButtonPreferences(buttons())
    }

    // ---------------------------------------------------------------- browse tree

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    private fun folders() = listOf(
        folder(RECENT, "Recently played"),
        folder(FAVOURITES, "Favourites"),
    )

    private fun videoItem(id: String, title: String, artist: String) = MediaItem.Builder()
        .setMediaId("v:$id")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title.ifEmpty { "YouTube video" })
                .setArtist(artist.ifEmpty { null })
                .setArtworkUri(Uri.parse("https://i.ytimg.com/vi/$id/mqdefault.jpg"))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

    private fun searchItem(query: String) = MediaItem.Builder()
        .setMediaId("q:$query")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Play \"$query\"")
                .setArtist("YouTube search")
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

    private fun pageOf(items: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(items)
        val from = (page.toLong() * pageSize).coerceIn(0L, items.size.toLong()).toInt()
        val to = minOf(items.size, from + pageSize)
        return ImmutableList.copyOf(items.subList(from, to))
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            if (!base.isAccepted) return base
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    base.availableSessionCommands.buildUpon()
                        .add(Commands.HELLO)
                        .add(Commands.FAVOURITE)
                        .add(Commands.EXIT)
                        .build(),
                )
                .setAvailablePlayerCommands(base.availablePlayerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                Commands.HELLO.customAction -> link.ensureBound()
                Commands.FAVOURITE.customAction -> {
                    Library.toggleFavourite(bridge.snap)
                    buttonsKey = ""
                    refreshButtons()
                }
                Commands.EXIT.customAction -> exitApp()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply {
                putBoolean(CONTENT_STYLE_SUPPORTED, true)
                putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST)
                putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
                putBoolean(SEARCH_SUPPORTED, true)
            }
            val rootParams = LibraryParams.Builder().setExtras(extras).build()
            return Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "AAYoutube"), rootParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            fun result(items: List<MediaItem>) = LibraryResult.ofItemList(pageOf(items, page, pageSize), params)
            val list = when (parentId) {
                ROOT -> folders()
                RECENT -> Library.recents().take(BROWSE_LIMIT).map { videoItem(it.id, it.title, it.artist) }
                FAVOURITES -> Library.favourites().take(BROWSE_LIMIT).map { videoItem(it.id, it.title, it.artist) }
                else -> return Futures.immediateFuture(
                    LibraryResult.ofError<ImmutableList<MediaItem>>(LibraryResult.RESULT_ERROR_BAD_VALUE),
                )
            }
            return Futures.immediateFuture(result(list))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (mediaId == ROOT) return Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "AAYoutube"), null))
            folders().firstOrNull { it.mediaId == mediaId }?.let {
                return Futures.immediateFuture(LibraryResult.ofItem(it, null))
            }
            val item = when {
                mediaId.startsWith("q:") -> searchItem(mediaId.removePrefix("q:"))
                mediaId.startsWith("v:") -> {
                    val id = mediaId.removePrefix("v:")
                    val known = Library.find(id)
                    videoItem(id, known?.title.orEmpty(), known?.artist.orEmpty())
                }
                else -> return Futures.immediateFuture(
                    LibraryResult.ofError<MediaItem>(LibraryResult.RESULT_ERROR_BAD_VALUE),
                )
            }
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        // Search: the result is one row, "Play <what you said>"; the page does the actual search.
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            main.post { session.notifySearchResultChanged(browser, query, 1, params) }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(searchItem(query)), params))

        /** Every "play this" request (app, Auto, voice) arrives here with a video id or a search query. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapNotNull { request ->
                when {
                    request.mediaId.startsWith("v:") -> {
                        val id = request.mediaId.removePrefix("v:")
                        val known = Library.find(id)
                        videoItem(id, known?.title.orEmpty(), known?.artist.orEmpty())
                    }
                    request.mediaId.startsWith("q:") -> request
                    request.mediaId.isEmpty() -> {
                        // Voice: "Play lo-fi on AAYoutube". An empty query means "play something".
                        val q = request.requestMetadata.searchQuery.orEmpty()
                        if (q.isNotBlank()) searchItem(q)
                        else Library.last?.let { videoItem(it.id, it.title, it.artist) }
                    }
                    else -> null
                }
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        /** Android Auto / Bluetooth "play" with nothing queued: resume the last video. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val last = Library.last
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing played yet"))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(videoItem(last.id, last.title, last.artist)),
                    0,
                    last.posSec * 1000,
                ),
            )
        }
    }

    private companion object {
        const val ROOT = "root"
        const val RECENT = "recent"
        const val FAVOURITES = "favourites"
        const val BROWSE_LIMIT = 20

        const val FREEZE_AFTER_MS = 2 * 60_000L
        const val STOP_AFTER_MS = 15 * 60_000L

        // Android Auto layout hints
        const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        const val CONTENT_STYLE_LIST = 1
    }
}
