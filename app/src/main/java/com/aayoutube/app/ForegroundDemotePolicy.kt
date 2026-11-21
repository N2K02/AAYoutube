package com.aayoutube.app

/**
 * When the media service may give up foreground priority.
 *  - deliberate Pause / Stop / ended / no video: short grace (8 s);
 *  - spontaneous interruption (not user intent, no active recovery): a bounded
 *    grace so a transient Chromium pause can recover, but a stuck notification
 *    never stays forever;
 *  - playing or actively recovering: keep foreground.
 */
internal object ForegroundDemotePolicy {
    const val USER_STOP_GRACE_MS = 8_000L
    const val INTERRUPTED_GRACE_MS = 180_000L

    fun isInterrupted(s: Snap, recovering: Boolean): Boolean =
        !s.playing && !recovering && !s.userPaused && !s.ended && s.id.isNotBlank()

    /** Delay before demotion, or null to keep foreground. [interruptedSince] is elapsed-realtime ms, 0 if unknown. */
    fun delayMs(s: Snap, recovering: Boolean, interruptedSince: Long, now: Long): Long? = when {
        s.playing || recovering -> null
        !isInterrupted(s, recovering) -> USER_STOP_GRACE_MS
        interruptedSince <= 0L -> INTERRUPTED_GRACE_MS
        else -> (INTERRUPTED_GRACE_MS - (now - interruptedSince).coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    /** CPU wake lock only while playing or actively recovering, never while held for another app. */
    fun wantsWakeLock(s: Snap, recovering: Boolean): Boolean = s.playing || (recovering && !s.held)

    fun phase(s: Snap, recovering: Boolean): String = when {
        s.userPaused -> "user-paused"
        s.held -> "held-other-media"
        s.playing -> "playing"
        s.recovering || recovering -> "recovering"
        s.buffering -> "buffering"
        s.id.isNotBlank() -> "interrupted"
        else -> "idle"
    }

    /** CPU % of this process between two samples; -1 when there is no previous sample. */
    fun cpuPercent(prevElapsed: Long, prevCpu: Long, now: Long, cpu: Long): Long =
        if (prevElapsed <= 0L || now <= prevElapsed) -1L
        else ((cpu - prevCpu).coerceAtLeast(0L) * 100L / (now - prevElapsed)).coerceIn(0L, 1000L)
}
