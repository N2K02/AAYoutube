package com.aayoutube.app

/** Single earliest-deadline queue; a later update cannot postpone scheduled work. */
internal class MirrorScheduler {
    private var due = Long.MAX_VALUE
    fun request(now: Long, delayMs: Long): Long? {
        val delay = delayMs.coerceAtLeast(0L)
        val deadline = now + delay.coerceAtMost(Long.MAX_VALUE - now)
        if (deadline >= due) return null
        due = deadline
        return deadline - now
    }
    fun clear() { due = Long.MAX_VALUE }
}
