package com.aayoutube.app

/** Transient problems do not disable playback for the session. */
internal class MirrorHealth {
    private var generation: Int? = null
    private var failures = 0
    private var retryAt = 0L
    fun observeGeneration(next: Int): Boolean {
        if (generation == next) return false
        generation = next
        failures = 0
        retryAt = 0
        return true
    }
    fun backoff(now: Long) {
        failures = (failures + 1).coerceAtMost(4)
        retryAt = now + (5_000L shl (failures - 1)).coerceAtMost(40_000L)
    }
    fun waiting(now: Long) = now < retryAt
    fun remaining(now: Long) = (retryAt - now).coerceAtLeast(0L)
    fun success() { failures = 0; retryAt = 0 }
    fun reset() { generation = null; success() }
}
