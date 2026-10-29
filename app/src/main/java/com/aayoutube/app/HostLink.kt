package com.aayoutube.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException

/**
 * The media process's side of the link to the web process.
 *
 * - Binds [WebHostService] (which also keeps the web process alive at the media service's priority).
 * - Every command is a fire-and-forget message: nothing here ever waits for the web process.
 * - If the web process dies, the link is dropped and the player shows "paused"; pressing play
 *   starts it again and resumes the last video at its last position.
 */
class HostLink(private val context: Context, private val player: BridgePlayer) : BridgePlayer.Host {

    var onSnapshot: ((Snap) -> Unit)? = null

    private val inbox = Messenger(
        Handler(Looper.getMainLooper()) { m ->
            if (m.what == Msg.STATE) m.data?.let { onState(Snap.from(it)) }
            true
        },
    )

    private var out: Messenger? = null
    private var bound = false
    private var seenState = false
    private val queue = ArrayList<Pair<Int, Bundle>>()
    private val main = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private val reconnect = Runnable { ensureBound() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            out = Messenger(service)
            seenState = false
            main.removeCallbacks(reconnect)
            raw(Msg.REGISTER, null, inbox)
            val pending = ArrayList(queue)
            queue.clear()
            pending.forEach { raw(it.first, it.second) }
        }

        override fun onServiceDisconnected(name: ComponentName) = lost()
        override fun onBindingDied(name: ComponentName) = lost()
        override fun onNullBinding(name: ComponentName) = lost()
    }

    /** Starts the web process if it is not running yet (a no-op when already bound). */
    fun ensureBound() {
        if (bound) return
        bound = context.bindService(
            Intent(context, WebHostService::class.java),
            connection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )
    }

    /** Lets the web process go (it frees the WebView when nothing is using it). */
    fun release() {
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        out = null
        seenState = false
        queue.clear()
        reconnectAttempts = 0
        main.removeCallbacks(reconnect)
    }

    private fun isAlive() = out != null && seenState

    private fun onState(s: Snap) {
        seenState = true
        reconnectAttempts = 0
        onSnapshot?.invoke(s)
    }

    private fun lost() {
        val active = player.playingNow()
        val before = player.snap
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        out = null
        seenState = false
        player.hostLost()
        // AABrowser-style recovery: preserve the last video/position rather than silently
        // abandoning playback when Android Auto replaces a display or kills the web process.
        // Never restart something the user intentionally paused.
        if (active && before.id.isNotBlank() && reconnectAttempts < 4) {
            queue.clear()
            queue.add(Msg.OPEN to Bundle().apply {
                putString("id", before.id)
                putLong("sec", before.posMs.coerceAtLeast(0L) / 1000L)
            })
            val backoff = 1_000L shl reconnectAttempts++
            main.removeCallbacks(reconnect)
            main.postDelayed(reconnect, backoff)
        }
    }

    private fun raw(what: Int, data: Bundle?, replyTo: Messenger? = null): Boolean {
        val target = out ?: return false
        return try {
            val m = Message.obtain(null, what)
            m.data = data
            m.replyTo = replyTo
            target.send(m)
            true
        } catch (e: RemoteException) {
            lost()
            false
        }
    }

    /** [queueIt]: keep the message and send it once the web process is up (start commands only). */
    private fun send(what: Int, data: Bundle = Bundle(), queueIt: Boolean = false) {
        if (raw(what, data)) return
        if (queueIt) {
            queue.add(what to data)
            ensureBound()
        }
    }

    override fun open(id: String, startSec: Long) =
        send(Msg.OPEN, Bundle().apply { putString("id", id); putLong("sec", startSec) }, queueIt = true)

    override fun search(query: String) =
        send(Msg.SEARCH, Bundle().apply { putString("q", query) }, queueIt = true)

    override fun play() {
        if (!isAlive()) {
            // The page is gone (or never started): bring the last video back.
            Library.last?.let { open(it.id, it.posSec); return }
        }
        send(Msg.PLAY)
    }

    override fun pause() = send(Msg.PAUSE)
    override fun seekTo(ms: Long) = send(Msg.SEEK, Bundle().apply { putLong("ms", ms) })
    override fun rate(r: Float) = send(Msg.RATE, Bundle().apply { putFloat("rate", r) })
    override fun next() = send(Msg.NEXT)
    override fun prev() = send(Msg.PREV)
    override fun stop() = send(Msg.STOP)

    /** Exit: stop the page and close the app screen if it is open. */
    fun quit() = send(Msg.QUIT)

    /** Paused for a while with no screen open: freeze the page to stop all its background work. */
    fun freeze() = send(Msg.FREEZE)
}
