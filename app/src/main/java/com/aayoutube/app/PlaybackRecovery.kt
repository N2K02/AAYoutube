package com.aayoutube.app

/**
 * Restores confirmed playback intent without reloading a surviving player.
 * Explicit PAUSE / STOP / OPEN / SEARCH / Quit cancels this state.
 */
internal class PlaybackRecovery {
    private var intended: Snap? = null
    val active: Boolean get() = intended != null

    fun onLinkLost(snapshot: Snap): Boolean {
        if ((snapshot.playing || snapshot.recovering) && !snapshot.userPaused &&
            !snapshot.ended && snapshot.id.isNotBlank()) intended = snapshot
        return active
    }

    fun onRendererGone(snapshot: Snap): Boolean = onLinkLost(snapshot)

    /** Resume the same paused WebView only if the user had actually been playing. */
    fun onExistingPlayer(current: Snap): Boolean {
        val previous = intended ?: return false
        if (current.id.isBlank()) return false // The WebView might still be loading.
        if (current.id != previous.id || current.userPaused || current.ended) {
            intended = null
            return false
        }
        // The page can report BUFFERING before it reports its final paused
        // state. Retain resume intent until that transition is complete.
        if (current.buffering) return false
        intended = null
        return !current.playing
    }

    fun onMissingPlayer(): Snap? = intended.also { intended = null }
    fun cancel() { intended = null }
}


// A Pause/Stop from Android Auto or the notification must survive Messenger
// loss. Retain the last explicit terminal command until the WebView actually
// confirms it was paused. A later Play/selection supersedes that command.
internal class TerminalTransportIntent {
    var pendingCommand: Int? = null
        private set
    var pendingId: Long? = null
        private set
    private var nextId = 1L

    /** Each NEW terminal command gets its own ID. Reconnect replays keep it. */
    fun onCommand(command: Int): Long? {
        when (command) {
            Msg.PAUSE, Msg.STOP -> {
                pendingCommand = command
                pendingId = nextId++
            }
            Msg.PLAY, Msg.OPEN, Msg.SEARCH, Msg.NEXT, Msg.PREV, Msg.QUIT -> clear()
        }
        return pendingId
    }

    /** An old Pause ACK must not acknowledge a newer Pause in the same epoch. */
    fun acknowledge(command: Int, id: Long) {
        if (pendingCommand == command && pendingId == id) clear()
    }

    fun clear() {
        pendingCommand = null
        pendingId = null
    }
}
