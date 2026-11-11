package com.aayoutube.app

/** When the background player may stop decoding video and play audio only. */
internal object AudioOnlyPolicy {
    /** Instant: the notification shade or volume panel never stop the activity,
     * so only a real leave (Home, another app, screen off) switches. */
    const val DELAY_MS = 0L
    /** An unexpected pause this soon after switching means audio-only broke playback. */
    const val BREAK_WINDOW_MS = 30_000L

    fun eligible(
        enabled: Boolean,
        backgroundPlay: Boolean,
        activityVisible: Boolean,
        inPip: Boolean,
        carShown: Boolean,
        broken: Boolean,
    ): Boolean = enabled && backgroundPlay && !activityVisible && !inPip && !carShown && !broken

    fun shouldBreak(audioOnly: Boolean, sinceSwitchMs: Long): Boolean =
        audioOnly && sinceSwitchMs in 0..BREAK_WINDOW_MS
}
