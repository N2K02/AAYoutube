package com.aayoutube.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory, privacy-safe diagnostics for the sole Android Auto video host.
 * No video content, account identifiers, URLs, or screenshots are retained.
 * The active host token prevents an older stopped projection from overwriting
 * a newer one. This status is intentionally independent of the media service.
 */
internal data class ExperimentalCarVideoStatus(
    val owner: String = "",
    val active: Boolean = false,
    val enabled: Boolean = false,
    val state: String = "No car video connection",
    val checks: Int = 0,
    val repairs: Int = 0,
    val displayId: Int = -1,
    val frameSize: String = "—",
    val playerSize: String = "—",
    val visibleArea: String = "—",
    val referenceVideoArea: String = "—",
    val decodedFrames: Int = -1,
    val droppedFrames: Int = -1,
    val decoderSize: String = "—",
    val decoderPaused: Boolean? = null,
    val frameHealth: String = "Not measured",
)

internal object ExperimentalCarVideoMonitor {
    private val _state = MutableStateFlow(ExperimentalCarVideoStatus())
    val state: StateFlow<ExperimentalCarVideoStatus> = _state
    private var previousFrame: CarVideoFrameSample? = null

    fun begin(owner: String, enabled: Boolean) {
        previousFrame = null
        _state.value = ExperimentalCarVideoStatus(
            owner = owner, active = true, enabled = enabled,
            state = if (enabled) "Experimental surface checks enabled" else "Standard video",
        )
        AppLog.add("CAR", "Video session mode=" + if (enabled) "experimental" else "standard")
    }

    fun check(
        owner: String,
        state: String,
        checks: Int,
        repairs: Int,
        displayId: Int,
        frameWidth: Int,
        frameHeight: Int,
        playerWidth: Int,
        playerHeight: Int,
        visibleArea: CarVideoBounds,
    ) {
        if (_state.value.owner != owner || !_state.value.active) return
        val expected = CarVideoViewportPolicy.fit16by9(frameWidth, frameHeight)
        _state.value = _state.value.copy(
            state = state, checks = checks, repairs = repairs,
            displayId = displayId,
            frameSize = "${frameWidth}×${frameHeight}",
            playerSize = "${playerWidth}×${playerHeight}",
            visibleArea = "${visibleArea.left},${visibleArea.top} ${visibleArea.width}×${visibleArea.height}",
            referenceVideoArea = "${expected.left},${expected.top} ${expected.width}×${expected.height}",
        )
        AppLog.add("CAR", "Video diag: status=$state check=$checks repairs=$repairs " +
            "display=$displayId frame=${frameWidth}x${frameHeight} " +
            "player=${playerWidth}x${playerHeight} " +
            "visible=${visibleArea.width}x${visibleArea.height} " +
            "16:9=${expected.width}x${expected.height}")
    }

    /**
     * Diagnostic-only. Never restarts playback based on frame counters.
     * The first valid sample is a baseline; short gaps and video changes are
     * classified explicitly instead of being reported as failed playback.
     */
    fun decoder(
        owner: String,
        decoded: Int,
        dropped: Int,
        width: Int,
        height: Int,
        paused: Boolean?,
        monotonicMs: Long = 0L,
        positionSeconds: Double? = null,
        hasVideo: Boolean = true,
        readyState: Int = 4,
        seeking: Boolean = false,
    ) {
        if (_state.value.owner != owner || !_state.value.active || !_state.value.enabled) return
        val sample = CarVideoFrameSample(
            monotonicMs = monotonicMs,
            hasVideo = hasVideo,
            paused = paused,
            decoded = decoded,
            dropped = dropped,
            positionSeconds = positionSeconds?.takeIf { it.isFinite() },
            width = width,
            height = height,
            readyState = readyState,
            seeking = seeking,
        )
        val health = CarVideoFrameHealthPolicy.classify(previousFrame, sample)
        // Resume from a new baseline after video transitions, position seeks,
        // pause/unpause and counter resets. Keep the baseline through short
        // successive probes so enough time can accumulate for classification.
        if (health != CarVideoFrameHealth.TOO_SOON) previousFrame = sample
        _state.value = _state.value.copy(
            decodedFrames = decoded,
            droppedFrames = dropped,
            decoderSize = "${width}×${height}",
            decoderPaused = paused,
            frameHealth = health.name.replace('_', ' ').lowercase(),
        )
        AppLog.add("CAR", "Experimental video decoder health=$health " +
            "frames=$decoded dropped=$dropped size=${width}x${height} paused=$paused")
    }

    fun fallback(owner: String, reason: String) {
        if (_state.value.owner != owner || !_state.value.active) return
        previousFrame = null
        _state.value = _state.value.copy(
            state = "Standard fallback: $reason", enabled = false,
            frameHealth = "Monitoring disabled after fallback"
        )
        AppLog.add("CAR", "Experimental video fallback: $reason")
    }

    fun end(owner: String) {
        if (_state.value.owner != owner) return
        previousFrame = null
        _state.value = _state.value.copy(active = false, state = "Disconnected (last session)")
        AppLog.add("CAR", "Video session ended")
    }
}
