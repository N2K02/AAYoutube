package com.aayoutube.app

internal class RendererTrial {
    private var startedAt: Long? = null
    val active get() = startedAt != null
    fun start(now: Long) { startedAt = now }
    fun expired(now: Long) = startedAt?.let { now - it >= TIMEOUT_MS } == true
    fun finish(now: Long): Long? {
        val start = startedAt ?: return null
        startedAt = null
        return (now - start).coerceAtLeast(0L)
    }
    companion object { const val TIMEOUT_MS = 8_000L }
}
