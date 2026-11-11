package com.aayoutube.app

/**
 * Conservative, testable guard for the optional car-video recovery checks.
 * Never acts on the phone, an inactive car window, a zero-sized frame or a
 * correctly attached renderer. Recovery always uses the existing legacy path.
 */
internal object ExperimentalCarVideoPolicy {
    const val CHECK_DELAY_MS = 2_500L
    const val MAX_CHECKS = 4
    const val MAX_REBINDS = 2

    fun needsRebind(
        enabled: Boolean,
        activeCarHost: Boolean,
        frameAttached: Boolean,
        frameWidth: Int,
        frameHeight: Int,
        frameDisplayId: Int,
        playerAttached: Boolean,
        playerWidth: Int,
        playerHeight: Int,
        playerDisplayId: Int,
    ): Boolean {
        if (!enabled || !activeCarHost || !frameAttached) return false
        if (frameWidth <= 0 || frameHeight <= 0 || frameDisplayId < 0) return false
        return !playerAttached || playerWidth <= 0 || playerHeight <= 0 ||
            playerDisplayId != frameDisplayId
    }
}
