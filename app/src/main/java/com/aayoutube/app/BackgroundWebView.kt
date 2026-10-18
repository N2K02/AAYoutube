package com.aayoutube.app

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * A WebView that does not tell the browser engine when its window is hidden.
 *
 * When the app goes to the background, Android marks the window as gone and the WebView
 * passes that on to Chromium, which pauses media below anything the page script can stop.
 * Reporting "visible" keeps the video (and its sound) playing; nothing is drawn while the
 * window is hidden, so this costs no rendering work.
 * Known workaround: https://github.com/react-native-webview/react-native-webview/issues/1570
 */
class BackgroundWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.VISIBLE && Features.current.backgroundPlay) {
            AppLog.add("VIEW", "Window hidden ($visibility): keeping the page visible for background play")
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }
}
