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
    private val queuedActions = ArrayDeque<String>()
    private var closed = false

    fun connect() {
        if (browser != null || closed) return
        val next = MediaBrowserCompat(context,
            ComponentName(context, PlaybackService::class.java),
            object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    if (closed) return
                    val connected = browser ?: return
                    controller = MediaControllerCompat(context, connected.sessionToken)
                    controller?.let { connectedController ->
                        onConnected(connectedController)
                        while (queuedActions.isNotEmpty()) {
                            connectedController.transportControls.sendCustomAction(queuedActions.removeFirst(), Bundle.EMPTY)
                        }
                    }
                }
                override fun onConnectionFailed() { disconnect() }
                override fun onConnectionSuspended() { controller = null }
            }, null)
        browser = next
        next.connect()
    }

    fun command(action: String) {
        if (closed) return
        val active = controller
        if (active != null) active.transportControls.sendCustomAction(action, Bundle.EMPTY)
        else if (queuedActions.size < 8) queuedActions.addLast(action)
    }

    fun disconnect() {
        closed = true
        controller = null
        queuedActions.clear()
        browser?.disconnect()
        browser = null
    }
}
