package com.aayoutube.app

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger

/**
 * Runs in the ":web" process. The media process binds it for two reasons: it hands over the
 * message channel to the WebView, and while it is bound the web process keeps the media
 * service's priority, so the system does not freeze or kill the page while it plays.
 */
class WebHostService : Service() {

    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            WebHost.onCommand(message)
            true
        },
    )

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        WebHost.onLinkUnbound()
        return false
    }
}
