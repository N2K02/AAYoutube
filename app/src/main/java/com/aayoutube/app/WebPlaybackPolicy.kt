package com.aayoutube.app

/**
 * Only suppress Android's automatic WebView pausing while projection, PiP or
 * enabled background playback needs its renderer. User-initiated PAUSE is
 * delivered to the page independently; an idle page can still freeze.
 */
internal object WebPlaybackPolicy {
    fun preserveRenderer(
        projectionVisible: Boolean,
        inPip: Boolean,
        playing: Boolean,
        backgroundPlay: Boolean,
    ): Boolean = projectionVisible || inPip || (playing && backgroundPlay)
}
