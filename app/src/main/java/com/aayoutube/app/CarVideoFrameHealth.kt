package com.aayoutube.app

/**
 * Pure analysis of two Chromium HTMLVideoElement observations.
 *
 * This deliberately does NOT attempt to diagnose head-unit pixels: video frames
 * can decode normally even when Android Auto paints a black surface. No recovery
 * action may be triggered from these heuristics.
 */
internal data class CarVideoFrameSample(
    val monotonicMs: Long,
    val hasVideo: Boolean,
    val paused: Boolean?,
    val decoded: Int,
    val dropped: Int,
    val positionSeconds: Double?,
    val width: Int,
    val height: Int,
    val readyState: Int = 4,
    val seeking: Boolean = false,
)

internal enum class CarVideoFrameHealth {
    NO_VIDEO,
    PAUSED,
    BUFFERING_OR_SEEKING,
    AWAITING_BASELINE,
    TOO_SOON,
    COUNTER_RESET,
    FRAMES_ADVANCING,
    DECODER_STALL_SUSPECTED,
    PLAYBACK_STALL_SUSPECTED,
    FRAME_COUNTER_UNAVAILABLE,
}

internal object CarVideoFrameHealthPolicy {
    const val MIN_SAMPLE_INTERVAL_MS = 4_000L

    fun classify(
        previous: CarVideoFrameSample?,
        current: CarVideoFrameSample,
    ): CarVideoFrameHealth {
        if (!current.hasVideo || current.width <= 0 || current.height <= 0)
            return CarVideoFrameHealth.NO_VIDEO
        if (current.paused != false) return CarVideoFrameHealth.PAUSED
        // YouTube may keep paused=false while fetching its next segment.
        // Do not report decoder stalls until the video is actually ready.
        if (current.readyState < 3 || current.seeking)
            return CarVideoFrameHealth.BUFFERING_OR_SEEKING
        if (previous == null || !previous.hasVideo || previous.paused != false ||
            previous.readyState < 3 || previous.seeking)
            return CarVideoFrameHealth.AWAITING_BASELINE
        val elapsed = current.monotonicMs - previous.monotonicMs
        if (elapsed < 0) return CarVideoFrameHealth.COUNTER_RESET
        if (elapsed < MIN_SAMPLE_INTERVAL_MS) return CarVideoFrameHealth.TOO_SOON
        if (current.decoded < 0 || previous.decoded < 0)
            return CarVideoFrameHealth.FRAME_COUNTER_UNAVAILABLE
        if (current.decoded < previous.decoded || current.dropped < previous.dropped)
            return CarVideoFrameHealth.COUNTER_RESET
        val framesDelta = current.decoded - previous.decoded
        val clockDelta = if (current.positionSeconds != null &&
            previous.positionSeconds != null) {
            current.positionSeconds - previous.positionSeconds
        } else null
        // A user seek changes the clock discontinuously and must not be mistaken
        // for a stalled or healthy renderer.
        if (clockDelta != null && (clockDelta < -0.5 ||
            clockDelta > elapsed / 1000.0 + 3.0)) return CarVideoFrameHealth.COUNTER_RESET
        if (framesDelta > 0) return if (clockDelta != null && clockDelta < 0.25)
            CarVideoFrameHealth.PLAYBACK_STALL_SUSPECTED
        else CarVideoFrameHealth.FRAMES_ADVANCING
        return CarVideoFrameHealth.DECODER_STALL_SUSPECTED
    }
}
