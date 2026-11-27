package com.aayoutube.app

/**
 * Another app took the audio (music/video app, call, ringtone) and Chromium paused us.
 * Never grab focus back while it plays; resume where we were once it has been quiet
 * for a moment. A hold is bounded so music never starts hours later.
 */
internal object OtherMediaHoldPolicy {
    /** Chromium pauses on focus loss before the other app's output starts. */
    const val SETTLE_MS = 1_500L
    const val QUIET_MS = 2_000L
    /** Allow playback state to settle before judging other media. */
    const val SELF_SILENCE_MS = 400L
    const val QUALITY_RELOAD_MAX_MS = 12_000L
    /** Delay only a known source reload. Never infer ownership from global isMusicActive. */
    fun qualityReloadDelay(deadline: Long, now: Long): Long =
        (deadline - now).coerceIn(0L, QUALITY_RELOAD_MAX_MS)
    /** Timed wake lock per playback/mode callback: enough for the quiet check. */
    const val WAKE_CHECK_MS = 5_000L
    const val MAX_HOLD_MS = 30 * 60_000L

    enum class Step { KEEP_HOLDING, RESUME, GIVE_UP }

    /** AudioManager.MODE_NORMAL = 0; ringtone, call, communication and screening are > 0. */
    fun othersActive(musicActive: Boolean, audioMode: Int): Boolean = musicActive || audioMode > 0

    fun step(othersActive: Boolean, heldSince: Long, quietSince: Long, now: Long): Step = when {
        now - heldSince >= MAX_HOLD_MS -> Step.GIVE_UP
        othersActive || quietSince <= 0L -> Step.KEEP_HOLDING
        now - quietSince >= QUIET_MS -> Step.RESUME
        else -> Step.KEEP_HOLDING
    }

    /** Next quiet-since value for a poll: reset whenever something else is audible. */
    fun quietSince(previous: Long, othersActive: Boolean, now: Long): Long =
        if (othersActive) 0L else if (previous <= 0L) now else previous
    /** Callbacks wake the poll immediately; use slower fallback while external audio is active. */
    fun pollDelay(active: Boolean, quietSince: Long, now: Long): Long =
        if (active) 2_000L else if (quietSince <= 0L) 1_000L
        else (QUIET_MS - (now - quietSince)).coerceIn(100L, 1_000L)
}
