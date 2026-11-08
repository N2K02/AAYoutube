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
        return current.id == previous.id && !current.userPaused &&
            !current.playing && !current.buffering && !current.ended
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

    fun onCommand(command: Int) {
        when (command) {
            Msg.PAUSE, Msg.STOP -> pendingCommand = command
            Msg.PLAY, Msg.OPEN, Msg.SEARCH, Msg.NEXT, Msg.PREV, Msg.QUIT ->
                pendingCommand = null
        }
    }

    fun onWebState(state: Snap) {
        if (state.userPaused) pendingCommand = null
    }

    fun clear() { pendingCommand = null }
}
