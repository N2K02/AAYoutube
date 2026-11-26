package com.aayoutube.app

/**
 * What moves with playback from one engine to the other: position, user intent, speed,
 * queue and metadata. Captured from the media session's snapshot at handoff time.
 */
internal data class HandoffState(
    val videoId: String,
    val positionMs: Long,
    /** elapsedRealtime when [positionMs] was observed. */
    val capturedAtMs: Long,
    val playing: Boolean,
    /** An explicit user pause: never resumed by a handoff. */
    val userPaused: Boolean,
    val rate: Float = 1f,
    val durationMs: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val art: String = "",
    val queue: List<QItem> = emptyList(),
) {
    companion object {
        fun from(s: Snap, nowMs: Long) = HandoffState(
            videoId = s.id, positionMs = s.posMs, capturedAtMs = nowMs,
            playing = s.playing && !s.userPaused, userPaused = s.userPaused, rate = s.rate,
            durationMs = s.durMs, title = s.title, artist = s.artist, art = s.art, queue = s.queue,
        )
    }
}

internal data class HandoffDecision(val allowed: Boolean, val reason: String)

internal object PlaybackHandoffPolicy {
    /** Native preparation longer than this is abandoned; the WebView keeps playing. */
    const val PREPARE_TIMEOUT_MS = 8_000L
    /** Positions closer than this to the end are not handed off (the next video is imminent). */
    const val END_MARGIN_MS = 5_000L

    /** Native audio may replace video only when no visible player needs it. */
    fun audioOnlyAllowed(featureOn: Boolean, backgroundPlay: Boolean, shown: Boolean, pip: Boolean, car: Boolean): Boolean =
        featureOn && backgroundPlay && !shown && !pip && !car

    /** The clock still reports periodically, but DSP sleep should not be interrupted every second. */
    fun nativeClockIntervalMs(playWhenReady: Boolean, offloadActive: Boolean, sleeping: Boolean): Long? = when {
        !playWhenReady -> null
        offloadActive && sleeping -> 10_000L
        else -> 1_000L
    }

    /** Position now: a playing clock kept moving since it was captured. */
    fun positionAt(s: HandoffState, nowMs: Long): Long {
        val moved = if (s.playing && !s.userPaused) ((nowMs - s.capturedAtMs).coerceAtLeast(0L) * s.rate).toLong() else 0L
        val p = (s.positionMs + moved).coerceAtLeast(0L)
        return if (s.durationMs > 0L) p.coerceAtMost(s.durationMs) else p
    }

    /** The target engine plays only if the user wanted playback; an explicit pause stays paused. */
    fun shouldResume(s: HandoffState): Boolean = s.playing && !s.userPaused

    fun canHandToNative(s: Snap, featureOn: Boolean, nowMs: Long = 0L): HandoffDecision = when {
        !featureOn -> HandoffDecision(false, "feature-off")
        s.id.isBlank() -> HandoffDecision(false, "no-video")
        s.live -> HandoffDecision(false, "live")
        s.ad -> HandoffDecision(false, "ad")
        s.ended -> HandoffDecision(false, "ended")
        s.durMs <= 0L -> HandoffDecision(false, "unknown-duration")
        s.durMs - s.posMs < END_MARGIN_MS -> HandoffDecision(false, "near-end")
        !s.playing || s.userPaused -> HandoffDecision(false, "not-playing")
        else -> HandoffDecision(true, "ok")
    }

    /** Native state shown through the existing media session; metadata and queue stay the web's. */
    fun merge(web: Snap, nativePosMs: Long, nativePlaying: Boolean, nativeBuffering: Boolean, userPaused: Boolean): Snap =
        web.copy(
            posMs = nativePosMs.coerceAtLeast(0L).let { if (web.durMs > 0L) it.coerceAtMost(web.durMs) else it },
            playing = nativePlaying,
            buffering = nativeBuffering,
            userPaused = userPaused,
            ended = false,
        )
}
