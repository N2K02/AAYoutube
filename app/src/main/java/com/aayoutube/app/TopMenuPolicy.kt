package com.aayoutube.app

/**
 * Shared phone and Android Auto app-header timeout. Zero deliberately means
 * "always show", and unknown/stale preferences return to the 3-second default.
 */
internal object TopMenuPolicy {
    const val DEFAULT_SECONDS = 3
    val options = listOf(0, 1, 2, 3, 5, 10, 15)
    fun normalize(seconds: Int): Int =
        seconds.takeIf { it in options } ?: DEFAULT_SECONDS

    fun timeoutMillis(seconds: Int): Long? =
        normalize(seconds).takeIf { it > 0 }?.times(1_000L)
}
