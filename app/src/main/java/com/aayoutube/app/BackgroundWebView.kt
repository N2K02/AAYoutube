package com.aayoutube.app

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * AABrowser's projection WebView keeps its renderer alive through transient
 * host onPause/window-hide callbacks. The page's explicit pause command still
 * pauses video: this intercepts only Android's automatic WebView lifecycle.
 * When idle and not projected, the ordinary lifecycle remains available for
 * WebHost.freeze() and its battery-saving timer shutdown.
 */
class BackgroundWebView(context: Context) : WebView(context) {
    private fun preservePlayback(): Boolean = WebPlaybackPolicy.preserveRenderer(
        projectionVisible = WebHost.carShown || WebHost.carDisplay,
        inPip = WebHost.inPip.value,
        playing = WebHost.snapshot.value.playing,
        backgroundPlay = Features.current.backgroundPlay,
    )

    override fun onPause() {
        if (preservePlayback()) {
            AppLog.add("VIEW", "Ignored automatic WebView pause while video is active on car/PiP")
            return
        }
        super.onPause()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.VISIBLE && preservePlayback()) {
            AppLog.add("VIEW", "Window hidden ($visibility): preserving projected video renderer")
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
            if (visibility == View.VISIBLE) invalidate()
        }
    }
}
