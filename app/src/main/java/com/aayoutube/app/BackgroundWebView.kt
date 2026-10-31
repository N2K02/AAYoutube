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

    override fun onWindowVisibilityChanged(visibility: Int) {
        // AABrowser always tells Chromium its primary WebView remains visible.
        super.onWindowVisibilityChanged(View.VISIBLE)
        if (visibility == View.VISIBLE) invalidate()
    }

    /** Explicit idle cleanup is the only caller allowed to pause this WebView. */
    fun pauseForIdle() = super.onPause()
}
