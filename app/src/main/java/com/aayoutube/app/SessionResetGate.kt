package com.aayoutube.app

/** Main-thread transitions; volatile phase lets background bridge readers fail closed. */
internal class SessionResetGate {
    private enum class Phase { IDLE, COOKIES, HOME }
    @Volatile private var phase = Phase.IDLE
    private var serial = 0L
    val clearing get() = phase != Phase.IDLE
    val cookiePending get() = phase == Phase.COOKIES
    val homePending get() = phase == Phase.HOME

    fun begin(): Long? {
        if (clearing) return null
        phase = Phase.COOKIES
        return ++serial
    }

    fun cookiesFinished(request: Long): Boolean {
        if (!cookiePending || request != serial) return false
        phase = Phase.HOME
        return true
    }

    fun finish(): Boolean {
        if (!homePending) return false
        phase = Phase.IDLE
        return true
    }

    fun cancelIfSafe(): Boolean {
        if (cookiePending) return false
        ++serial
        phase = Phase.IDLE
        return true
    }
}
