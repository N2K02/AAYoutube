package com.aayoutube.app

/**
 * Pure gates and frame-pacing summary for the optional *synthetic only*
 * SurfaceView probe. This does not receive, capture, decode or replay video.
 */
internal object CarDirectSurfacePolicy {
    const val DURATION_MS = 5_000L
    const val TARGET_FRAME_INTERVAL_MS = 33L

    fun canStart(
        requestedOwner: String?,
        activeOwner: String,
        active: Boolean,
        enabled: Boolean,
        playing: Boolean,
        anotherTestRunning: Boolean,
        alreadyVisible: Boolean,
    ): Boolean = requestedOwner != null && requestedOwner == activeOwner &&
        active && enabled && !playing && !anotherTestRunning && !alreadyVisible

    fun estimatedFps(frames: Int, elapsedMs: Long): Int =
        if (frames <= 0 || elapsedMs <= 0) 0
        else (frames.toLong() * 1000L / elapsedMs).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    fun describe(frames: Int, elapsedMs: Long, maxFrameGapMs: Long): String =
        when {
            frames <= 0 -> "No direct-surface frames submitted (inconclusive)"
            elapsedMs <= 0 -> "Direct surface started; awaiting frame timing"
            else -> "Submitted $frames synthetic frames; ~${estimatedFps(frames, elapsedMs)} fps, " +
                "max gap ${maxFrameGapMs}ms (not proof of head-unit display)"
        }
}
