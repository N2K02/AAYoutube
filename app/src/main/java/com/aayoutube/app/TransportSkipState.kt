package com.aayoutube.app

/**
 * Tracks the selected target while a WebView document is navigating.
 *
 * Native playback snapshots can still identify the old video, or temporarily
 * contain no ID/queue. Subsequent steering-wheel presses must advance from the
 * most recently requested target, not jump back to the same item.
 */
internal class TransportSkipState {
    private var target: String? = null
    private var items: List<QItem> = emptyList()
    private var deadlineMs: Long = 0L
    private val timeoutMs = 8_000L

    fun clear() {
        target = null
        items = emptyList()
        deadlineMs = 0L
    }

    fun onPlaybackState(current: String, playing: Boolean) {
        // A newer queue snapshot may briefly identify an older video during
        // navigation; only the target itself confirms the transition.
        if (playing && current.isNotBlank() && current == target) clear()
    }

    /** Previous restarts the currently established track beyond five seconds.
     * During an in-flight skip it reverses the pending target instead. */
    fun shouldRestartPrevious(current: String, positionMs: Long, nowMs: Long): Boolean {
        if (target != null && nowMs >= deadlineMs) clear()
        return target == null && current.isNotBlank() && positionMs > 5_000L
    }

    fun hasPending(nowMs: Long): Boolean {
        if (target != null && nowMs >= deadlineMs) clear()
        return target != null
    }

    fun next(current: String, queue: List<QItem>, nowMs: Long): String? =
        select(current, queue, nowMs, forward = true)

    fun previous(current: String, queue: List<QItem>, nowMs: Long): String? =
        select(current, queue, nowMs, forward = false)

    private fun select(current: String, queue: List<QItem>, nowMs: Long, forward: Boolean): String? {
        if (target != null && nowMs >= deadlineMs) clear()
        val active = target
        val candidateQueue = when {
            active != null && queue.any { it.id == active } -> queue
            active != null && items.any { it.id == active } -> items
            else -> queue
        }
        val base = active ?: current
        val selected = if (forward)
            MusicQueueNavigation.transportNext(base, candidateQueue)
        else MusicQueueNavigation.transportPrevious(base, candidateQueue)
        if (selected != null && selected != base) {
            target = selected
            items = candidateQueue.toList()
            deadlineMs = nowMs + timeoutMs
        }
        return selected
    }
}
