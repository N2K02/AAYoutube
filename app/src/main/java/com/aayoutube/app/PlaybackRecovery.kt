package com.aayoutube.app

/**
 * Restores confirmed playback intent without reloading a surviving player.
 * Explicit PAUSE / STOP / OPEN / SEARCH / Quit cancels this state.
 */
internal class PlaybackRecovery {
    private var intended: Snap? = null
    val active: Boolean get() = intended != null

    fun onLinkLost(snapshot: Snap): Boolean {
        if (snapshot.playing && snapshot.id.isNotBlank()) intended = snapshot
        return active
    }

    fun onRendererGone(snapshot: Snap): Boolean = onLinkLost(snapshot)

    /** Resume the same paused WebView only if the user had actually been playing. */
    fun onExistingPlayer(current: Snap): Boolean {
        val previous = intended ?: return false
        if (current.id.isBlank()) return false // The WebView might still be loading.
        intended = null
        return current.id == previous.id && !current.playing && !current.buffering && !current.ended
    }

    fun onMissingPlayer(): Snap? = intended.also { intended = null }
    fun cancel() { intended = null }
}
