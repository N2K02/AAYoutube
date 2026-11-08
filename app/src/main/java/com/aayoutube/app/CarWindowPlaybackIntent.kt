package com.aayoutube.app

/** Preserve the actual play intent when a replacement Android Auto surface
 * forces Chromium to recreate its decoder-bearing WebView. */
internal object CarWindowPlaybackIntent {
    fun shouldAutoplay(state: Snap, playbackWanted: Boolean, recentlyPlaying: Boolean): Boolean =
        state.id.isNotBlank() && !state.userPaused && !state.ended &&
            (state.playing || state.recovering || playbackWanted || recentlyPlaying)
}
