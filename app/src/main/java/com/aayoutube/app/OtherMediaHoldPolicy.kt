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
}
