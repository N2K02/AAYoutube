package com.aayoutube.app

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * Match AABrowser's primary WebView behavior: Android Auto and phone/launcher
 * lifecycle transitions must not pause the Chromium renderer or hide its
 * window. Playback controls still pause the actual HTML video on user request.
 *
 * WebHost may explicitly freeze an idle page via pauseForIdle(), and destroys
 * the entire WebView during Quit. These are different from Android calling
 * onPause() as the projected window changes focus.
 */
class BackgroundWebView(context: Context) : WebView(context) {
    override fun onPause() {
        // AABrowser's deliberate no-op, independent of projection/playing flags.
    }

    /** Audio-only background: Chromium is told the page is hidden. */
    var audioOnly = false
        private set

    override fun onWindowVisibilityChanged(visibility: Int) {
        // AABrowser always tells Chromium its primary WebView remains visible,
        // except while WebHost deliberately runs audio-only in the background.
        // Android's own window visibility is never forwarded.
        if (audioOnly) super.onWindowVisibilityChanged(View.GONE)
        else super.onWindowVisibilityChanged(View.VISIBLE)
        if (visibility == View.VISIBLE) invalidate()
    }

    /**
     * Let Chromium see the page hidden (or visible again). Chromium then turns
     * off the video track of an audible video (its background video-track
     * optimisation): no video decoding, audio continues. The page script keeps
     * YouTube itself believing it is visible, and audible pages are not timer-
     * throttled. Visible again re-enables the track at the current position.
     */
    fun setAudioOnly(on: Boolean) {
        if (audioOnly == on) return
        audioOnly = on
        super.onWindowVisibilityChanged(if (on) View.GONE else View.VISIBLE)
        if (!on) invalidate()
    }

    /** Explicit idle cleanup is the only caller allowed to pause this WebView. */
    fun pauseForIdle() = super.onPause()
}
