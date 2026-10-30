package com.aayoutube.app

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat

/** Reconnecting MediaCompat client shared by phone and projection screens. */
class CompatMediaConnection(
    private val context: Context,
    private val onConnected: (MediaControllerCompat) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private var browser: MediaBrowserCompat? = null
    private var controller: MediaControllerCompat? = null
    private val queuedActions = ArrayDeque<String>()
    private var closed = false
    private var retries = 0
    private val retry = Runnable { connect() }

    fun connect() {
        if (browser != null || closed) return
        var generationBrowser: MediaBrowserCompat? = null
        val next = MediaBrowserCompat(context,
            ComponentName(context, PlaybackService::class.java),
            object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    if (closed || browser !== generationBrowser) return
                    retries = 0
                    handler.removeCallbacks(retry)
                    val active = runCatching {
                        MediaControllerCompat(context, generationBrowser!!.sessionToken)
                    }.getOrElse {
                        reconnect()
                        return
                    }
                    controller = active
                    onConnected(active)
                    while (!closed && queuedActions.isNotEmpty()) {
                        val action = queuedActions.first()
                        if (runCatching {
                            active.transportControls.sendCustomAction(action, Bundle.EMPTY)
                        }.isFailure) {
                            reconnect()
                            break
                        }
                        queuedActions.removeFirst()
                    }
                }
                override fun onConnectionFailed() { if (browser === generationBrowser) reconnect() }
                override fun onConnectionSuspended() { if (browser === generationBrowser) reconnect() }
            }, null)
        generationBrowser = next
        browser = next
        runCatching { next.connect() }.onFailure { reconnect() }
    }

    private fun reconnect() {
        if (closed) return
        controller = null
        browser?.let { runCatching { it.disconnect() } }
        browser = null
        if (retries >= 5) {
            AppLog.add("MEDIA", "Media browser connection retries exhausted")
            return
        }
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, 1_000L shl retries++)
    }

    fun command(action: String) {
        if (closed) return
        val active = controller
        if (active != null && runCatching {
                active.transportControls.sendCustomAction(action, Bundle.EMPTY)
            }.isSuccess) return
        if (queuedActions.size >= 8) queuedActions.removeFirst()
        queuedActions.addLast(action)
        if (active != null) reconnect() else connect()
    }

    fun disconnect() {
        closed = true
        handler.removeCallbacks(retry)
        controller = null
        queuedActions.clear()
        browser?.let { runCatching { it.disconnect() } }
        browser = null
    }
}
