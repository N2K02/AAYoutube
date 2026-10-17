package com.aayoutube.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.SimpleBasePlayer.State
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A Media3 player that does not play anything itself: it mirrors the YouTube page running in the
 * web process and forwards play, pause, seek, next and previous to it. That is all the media
 * session needs for the notification, lock screen, Bluetooth and Android Auto.
 *
 * It runs on the main thread of the media process and never waits for the web process: every
 * command is a fire-and-forget message ([Host]), so a slow or frozen WebView cannot make
 * Android Auto's calls time out.
 */
@OptIn(UnstableApi::class)
class BridgePlayer(looper: Looper) : SimpleBasePlayer(looper) {

    interface Host {
        fun open(id: String, startSec: Long)
        fun search(query: String)
        fun play()
        fun pause()
        fun seekTo(ms: Long)
        fun rate(r: Float)
        fun next()
        fun prev()
        fun stop()
    }

    lateinit var host: Host

    private val handler = Handler(looper)

    /** The last thing the page told us (or the placeholder while a request is on its way). */
    var snap: Snap = Snap()
        private set

    // What the user asked for, shown immediately until the page confirms (or 15 s pass).
    private var intentPlay = false
    private var intentUntil = 0L

    private val expireIntent = Runnable { invalidateState() }

    private fun now() = SystemClock.elapsedRealtime()

    private fun setIntent(play: Boolean) {
        intentPlay = play
        intentUntil = now() + INTENT_MS
        handler.removeCallbacks(expireIntent)
        handler.postDelayed(expireIntent, INTENT_MS + 50)
    }

    fun playingNow(): Boolean = snap.playing || (now() < intentUntil && intentPlay)

    /** A new report from the page. */
    fun update(s: Snap) {
        snap = s
        if (s.playing == intentPlay) intentUntil = 0L
        invalidateState()
    }

    /** The web process died: show paused, keep the item so Play can resume it. */
    fun hostLost() {
        snap = snap.copy(playing = false, buffering = false)
        intentUntil = 0L
        invalidateState()
    }

    /** Exit: forget everything. */
    fun reset() {
        snap = Snap()
        intentUntil = 0L
        invalidateState()
    }

    override fun getState(): State {
        val s = snap
        val hasItem = s.id.isNotEmpty() || s.title.isNotEmpty()
        val waiting = now() < intentUntil
        val playWhenReady = if (waiting) intentPlay else s.playing

        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_SET_SPEED_AND_PITCH,
        ).build()

        val playbackState = when {
            !hasItem -> Player.STATE_IDLE
            s.ended && !waiting -> Player.STATE_ENDED
            s.buffering || (waiting && intentPlay && !s.playing) -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }

        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlaybackParameters(PlaybackParameters(s.rate.coerceIn(0.25f, 4f)))

        if (hasItem) {
            val seekable = !s.live && s.durMs > 0
            val data = MediaItemData.Builder(if (s.id.isNotEmpty()) "v:" + s.id else "pending")
                .setMediaItem(mediaItem(s))
                .setDurationUs(if (seekable) s.durMs * 1000L else C.TIME_UNSET)
                .setIsSeekable(seekable)
                .build()
            builder.setPlaylist(ImmutableList.of(data))
                .setCurrentMediaItemIndex(0)
                .setContentPositionMs(
                    if (s.playing) PositionSupplier.getExtrapolating(s.posMs, s.rate)
                    else PositionSupplier.getConstant(s.posMs),
                )
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        setIntent(playWhenReady)
        if (playWhenReady) host.play() else host.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        setIntent(false)
        host.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (positionMs != C.TIME_UNSET) host.seekTo(positionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        host.rate(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    /** "Play this" from Android Auto, voice or the app: a video id, or "q:<search words>". */
    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val item = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
        val mediaId = item?.mediaId.orEmpty()
        when {
            mediaId.startsWith("q:") -> {
                val q = mediaId.removePrefix("q:")
                snap = Snap(title = "Searching for \"$q\"…", buffering = true)
                setIntent(true)
                host.search(q)
            }
            mediaId.startsWith("v:") -> {
                val id = mediaId.removePrefix("v:")
                snap = Snap(
                    id = id,
                    title = item?.mediaMetadata?.title?.toString().orEmpty(),
                    artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
                    buffering = true,
                )
                setIntent(true)
                host.open(id, if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs / 1000)
            }
        }
        return Futures.immediateVoidFuture()
    }

    private fun mediaItem(s: Snap): MediaItem {
        val art = when {
            s.art.isNotEmpty() -> Uri.parse(s.art)
            s.id.isNotEmpty() -> Uri.parse("https://i.ytimg.com/vi/${s.id}/hqdefault.jpg")
            else -> null
        }
        return MediaItem.Builder()
            .setMediaId(if (s.id.isNotEmpty()) "v:" + s.id else "pending")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.title.ifEmpty { "YouTube" })
                    .setArtist(s.artist.ifEmpty { "YouTube" })
                    .setArtworkUri(art)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
    }

    private companion object {
        const val INTENT_MS = 15_000L
    }
}

/**
 * Gives the notification, lock screen, Bluetooth and Android Auto working next / previous
 * buttons (the page has only one video in the player at a time, so the player alone would hide them).
 */
@OptIn(UnstableApi::class)
class ControlsPlayer(private val bridge: BridgePlayer) : ForwardingPlayer(bridge) {
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true
    override fun seekToNext() = bridge.host.next()
    override fun seekToNextMediaItem() = bridge.host.next()
    override fun seekToPrevious() = bridge.host.prev()
    override fun seekToPreviousMediaItem() = bridge.host.prev()
}
