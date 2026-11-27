package com.aayoutube.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock

/**
 * Connection-aware playback recovery (pure).
 *
 * When the connection drops, YouTube's player stalls, then errors and resets the video
 * (position 0, no duration). That pause is not the user's and not another app's audio:
 *  - while offline it must not use up the bounded resume attempts or start the
 *    "other app is playing" hold (residual output of our own WebView looks like other media);
 *  - when a validated connection returns, the same video resumes at its last good position.
 */
internal class NetworkWatch {
    var online = true
        private set
    var lostAt = 0L
        private set
    /** Last loss or default-network change; pauses shortly after one are network pauses. */
    var disruptedAt = 0L
        private set
    private var network: String? = null

    fun onLost(id: String, nowMs: Long) {
        if (network != null && id != network) return // an old network went away; the default moved on
        if (online) { online = false; lostAt = nowMs }
        disruptedAt = nowMs
        network = null
    }

    /**
     * A validated default network. Returns the outage length when this restores connectivity
     * (or switches network, which also breaks running streams), null when nothing changed.
     */
    fun onValidated(id: String, nowMs: Long): Long? {
        val wasOffline = !online
        val switched = network != null && network != id
        network = id
        if (!wasOffline && !switched) return null
        online = true
        disruptedAt = nowMs
        val outage = if (wasOffline && lostAt > 0L) nowMs - lostAt else 0L
        lostAt = 0L
        return outage
    }

    fun recentlyDisrupted(nowMs: Long): Boolean =
        !online || (disruptedAt > 0L && nowMs - disruptedAt <= NetworkRecoveryPolicy.DISRUPTION_WINDOW_MS)
}

internal object NetworkRecoveryPolicy {
    /** Let the new connection settle (DNS, first segments) before resuming. */
    const val SETTLE_MS = 1_500L
    /** A pause this soon after a loss/switch is the network's, not another app's. */
    const val DISRUPTION_WINDOW_MS = 30_000L

    /** Resume after reconnecting: playback was wanted, not paused by the user, and is not running. */
    fun shouldResume(s: Snap, playbackWanted: Boolean, otherMediaHeld: Boolean): Boolean =
        playbackWanted && !s.userPaused && !s.ended && s.id.isNotBlank() && (!s.playing || s.buffering) && !otherMediaHeld

    /** Position to resume at: the player may have reset to 0 after its network error. */
    fun resumePositionMs(current: Snap, lastGoodId: String, lastGoodPosMs: Long): Long =
        if (current.posMs > 0L || current.id != lastGoodId) current.posMs else lastGoodPosMs
}

/** Default-network callback; registered only while a playback renderer exists. */
internal object NetworkMonitor {
    private val watch = NetworkWatch()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var app: Application? = null

    val online: Boolean get() = watch.online
    fun recentlyDisrupted(): Boolean = watch.recentlyDisrupted(SystemClock.elapsedRealtime())

    fun start(application: Application) {
        if (callback != null) return
        app = application
        val cm = application.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                val now = SystemClock.elapsedRealtime()
                val wasOnline = watch.online
                watch.onLost(network.toString(), now)
                if (wasOnline && !watch.online) WebHost.onNetworkLost()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                watch.onValidated(network.toString(), SystemClock.elapsedRealtime())?.let { WebHost.onNetworkRestored(it) }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { callback = cb }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { app?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }
}
