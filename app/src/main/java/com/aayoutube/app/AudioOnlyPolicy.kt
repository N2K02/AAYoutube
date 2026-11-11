package com.aayoutube.app

/** When the background player may stop decoding video and play audio only. */
internal object AudioOnlyPolicy {
    /** A quick app switch never touches the renderer. */
    const val DELAY_MS = 10_000L
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
