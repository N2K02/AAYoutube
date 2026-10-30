package com.aayoutube.app

/** Capped reconnect delay shared by media service and active phone/car clients. */
internal object MediaRetryPolicy {
    fun delayMs(attempt: Int): Long = 1_000L shl attempt.coerceIn(0, 5)
    fun shouldRetry(attempt: Int, hasActiveWork: Boolean): Boolean =
        attempt < 4 || hasActiveWork
}
