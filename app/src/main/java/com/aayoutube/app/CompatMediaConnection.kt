package com.aayoutube.app

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat

/** MediaCompat browser connection shared by phone and projection screens. */
class CompatMediaConnection(
    private val context: Context,
    private val onConnected: (MediaControllerCompat) -> Unit = {}
) {
    private var browser: MediaBrowserCompat? = null
    private var controller: MediaControllerCompat? = null

    fun connect() {
        if (browser != null) return
        val next = MediaBrowserCompat(context,
            ComponentName(context, PlaybackService::class.java),
            object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    val connected = browser ?: return
                    controller = MediaControllerCompat(context, connected.sessionToken)
                    controller?.let(onConnected)
                }
                override fun onConnectionFailed() { disconnect() }
                override fun onConnectionSuspended() { controller = null }
            }, null)
        browser = next
        next.connect()
    }

    fun command(action: String) {
        controller?.transportControls?.sendCustomAction(action, Bundle.EMPTY)
    }

    fun disconnect() {
        controller = null
        browser?.disconnect()
        browser = null
    }
}
