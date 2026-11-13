package com.aayoutube.app

/**
 * YouTube's fullscreen button while our own layout already fills the screen
 * (phone held sideways, or Android Auto): toggle filled video <-> watch page
 * with playlist, and never show Chromium's separate fullscreen screen.
 * Portrait phone keeps the normal fullscreen.
 */
internal object FullscreenTogglePolicy {
    const val ECHO_MS = 1_000L

    fun intercept(car: Boolean, landscape: Boolean, fullPlayer: Boolean): Boolean =
        fullPlayer && (car || landscape)

    /** Chromium may report "fullscreen closed" for the request we just declined. */
    fun isEcho(sinceInterceptMs: Long): Boolean = sinceInterceptMs in 0..ECHO_MS
}
