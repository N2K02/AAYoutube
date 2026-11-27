package com.aayoutube.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Experimental Media3 audio-only engine. Audio only: the video track type is disabled and only
 * the separate audio stream is loaded, so no video is downloaded or decoded.
 *
 *  - Audio focus is Media3's (handleAudioFocus = true): calls pause and resume it, navigation
 *    prompts duck it. Becoming noisy (Bluetooth/wired disconnect) pauses it.
 *  - Offload follows [AudioOffloadPolicy]; a failure while offloaded is recorded by the caller's
 *    breaker and the stream is re-prepared once without offload at the same position.
 *  - Structured `NATIVE-AUDIO` logs report the real decoder (or DSP offload) and offload state.
 *
 * Created and used on the main thread only.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class NativeAudioEngine(
    private val context: Context,
    private val listener: Listener,
) : PreparableEngine {
    interface Listener {
        fun onReady()
        fun onState(playing: Boolean, buffering: Boolean, positionMs: Long, playWhenReady: Boolean)
        fun onEnded()
        /** [offloaded]: the failure happened during offloaded playback. */
        fun onError(offloaded: Boolean, codecMime: String, detail: String)
        fun log(line: String)
    }

    override val id = EngineId.NATIVE
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var sabrPlayback: SabrAudioSource? = null
    private var stream: AudioStream? = null
    private var readyReported = false
    private var started = false
    var offloadRequested = false
        private set
    var offloadActive = false
        private set
    var sleepingForOffload = false
        private set
    var decoderName = ""
        private set
    private var retriedWithoutOffload = false

    override fun prepare(stream: AudioStream, positionMs: Long, offload: OffloadDecision) {
        release()
        this.stream = stream
        readyReported = false
        started = false
        retriedWithoutOffload = false
        build(stream, positionMs, offload.enabled)
    }

    private fun build(stream: AudioStream, positionMs: Long, offload: Boolean) {
        offloadRequested = offload
        offloadActive = false
        sleepingForOffload = false
        decoderName = ""
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(false)
            .setDefaultRequestProperties(stream.headers)
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(
                        if (offload) TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                        else TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                    .setIsGaplessSupportRequired(false)
                    .build())
            .build()
        p.addListener(playerListener)
        p.addAnalyticsListener(analytics)
        p.addAudioOffloadListener(offloadListener)
        try {
            if (stream.sabrManifest != null) {
                val sabr = SabrAudioSource.create(stream)
                sabrPlayback = sabr
                sabr.updatePosition(positionMs)
                p.setMediaSource(sabr.source, positionMs)
                listener.log("transport=sabr audioOnly=true")
            } else p.setMediaItem(MediaItem.Builder().setUri(stream.url).setMimeType(stream.mimeType.substringBefore(';')).build(), positionMs)
        } catch (_: Exception) {
            p.removeListener(playerListener); p.removeAnalyticsListener(analytics); p.removeAudioOffloadListener(offloadListener); p.release()
            sabrPlayback?.close(); sabrPlayback = null
            listener.onError(offloaded = false, codecMime = stream.codecMime, detail = "invalid-sabr-source")
            return
        }
        p.playWhenReady = false
        p.volume = 0f // silent until start(): never audible while web still plays
        player = p
        p.prepare()
        listener.log("state=preparing codec=${stream.codecMime} container=${stream.mimeType.substringBefore(';')} " +
            "bitrate=${stream.bitrate} offloadRequested=$offload startMs=$positionMs")
    }

    override fun start(videoId: String, positionMs: Long, resume: Boolean) {
        val p = player ?: return
        started = true
        if (positionMs >= 0L && kotlin.math.abs(p.currentPosition - positionMs) > 250L) p.seekTo(positionMs)
        p.volume = 1f
        p.playWhenReady = resume
        tick.run()
        listener.log("state=started video=$videoId posMs=$positionMs resume=$resume offloadRequested=$offloadRequested")
    }

    override fun silence() {
        started = false // callbacks from pausing must not publish or re-arm the clock
        player?.let { it.playWhenReady = false; it.volume = 0f }
        main.removeCallbacks(tick)
    }

    override fun pause() { player?.playWhenReady = false }
    override fun play() { player?.playWhenReady = true }
    override fun seek(positionMs: Long) { sabrPlayback?.updatePosition(positionMs); player?.seekTo(positionMs); if (started) tick.run() }
    override fun positionMs(): Long = player?.currentPosition ?: 0L

    override fun release() {
        main.removeCallbacks(tick)
        sabrPlayback?.close(); sabrPlayback = null
        player?.let {
            it.removeListener(playerListener)
            it.removeAnalyticsListener(analytics)
            it.removeAudioOffloadListener(offloadListener)
            it.release()
            listener.log("state=released offloadActive=$offloadActive decoder=${decoderName.ifBlank { "none" }}")
        }
        player = null
        started = false
        offloadActive = false
        sleepingForOffload = false
        stream = null
    }

    /** Position updates: sparse while DSP sleeps; state changes still report immediately. */
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!started) return
            sabrPlayback?.updatePosition(p.currentPosition)
            listener.onState(p.isPlaying, p.playbackState == Player.STATE_BUFFERING, p.currentPosition, p.playWhenReady)
            main.removeCallbacks(this)
            PlaybackHandoffPolicy.nativeClockIntervalMs(p.playWhenReady, offloadActive, sleepingForOffload)
                ?.let { main.postDelayed(this, it) }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY && !readyReported) {
                readyReported = true
                listener.log("state=ready decoder=${decoderName.ifBlank { if (offloadRequested) "pending/offload" else "pending" }}")
                listener.onReady()
            }
            if (state == Player.STATE_ENDED && started) listener.onEnded()
            if (started) tick.run()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!started) return
            // Becoming noisy (headset/Bluetooth gone) is a pause like the user's own.
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                listener.log("event=becoming-noisy paused")
            tick.run()
        }

        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            if (!started) return
            // Transient focus loss (call, other app): Media3 holds playWhenReady and resumes itself.
            listener.log("event=suppression reason=$reason")
            tick.run()
        }

        override fun onPlayerError(error: PlaybackException) {
            val s = stream ?: return
            val rejected = SabrRejectionPolicy.terminalStatus(error)
            val detail = rejected?.let { "SABR_HTTP_$it" } ?: error.errorCodeName
            val wasOffloaded = rejected == null && (offloadActive || (offloadRequested && error.errorCode in 5001..5004))
            listener.log("event=error code=$detail offloadActive=$offloadActive offloadRequested=$offloadRequested")
            if (wasOffloaded && !retriedWithoutOffload) {
                // Offload caused (or coincided with) the failure: retry once without it, same position.
                retriedWithoutOffload = true
                val pos = player?.currentPosition ?: 0L
                val resume = started && (player?.playWhenReady ?: false)
                val wasStarted = started
                listener.onError(offloaded = true, codecMime = s.codecMime, detail = error.errorCodeName)
                main.removeCallbacks(tick)
                player?.let { it.removeListener(this); it.removeAnalyticsListener(analytics); it.removeAudioOffloadListener(offloadListener); it.release() }
                player = null
                sabrPlayback?.close(); sabrPlayback = null
                readyReported = wasStarted
                build(s, pos, offload = false)
                if (wasStarted && player != null) { started = true; player?.volume = 1f; player?.playWhenReady = resume }
                return
            }
            listener.onError(offloaded = false, codecMime = s.codecMime, detail = detail)
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long,
        ) {
            this@NativeAudioEngine.decoderName = decoderName
            listener.log("decoder=$decoderName initMs=$initializationDurationMs offloadActive=$offloadActive")
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            listener.log("format sampleMime=${format.sampleMimeType} codecs=${format.codecs} " +
                "sampleRate=${format.sampleRate} channels=${format.channelCount} bitrate=${format.bitrate}")
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long,
        ) {
            listener.log("event=underrun bufferMs=$bufferSizeMs sinceFeedMs=$elapsedSinceLastFeedMs offloadActive=$offloadActive")
        }
    }

    private val offloadListener = object : ExoPlayer.AudioOffloadListener {
        override fun onOffloadedPlayback(offloadedPlayback: Boolean) {
            offloadActive = offloadedPlayback
            // Offloaded audio is decoded by the DSP: no MediaCodec decoder is initialized.
            if (offloadedPlayback) decoderName = "dsp-offload"
            listener.log("offloadActive=$offloadedPlayback requested=$offloadRequested decoder=${decoderName.ifBlank { "pending" }}")
        }

        override fun onSleepingForOffloadChanged(isSleepingForOffload: Boolean) {
            sleepingForOffload = isSleepingForOffload
            listener.log("offloadSleeping=$isSleepingForOffload")
            if (started) tick.run()
        }
    }
}
