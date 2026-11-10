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
)

internal object ExperimentalCarVideoMonitor {
    private val _state = MutableStateFlow(ExperimentalCarVideoStatus())
    val state: StateFlow<ExperimentalCarVideoStatus> = _state

    fun begin(owner: String, enabled: Boolean) {
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

    /** Observability only: decoded frames are not proof that the head unit paints them. */
    fun decoder(owner: String, decoded: Int, dropped: Int, width: Int, height: Int, paused: Boolean?) {
        if (_state.value.owner != owner || !_state.value.active) return
        _state.value = _state.value.copy(
            decodedFrames = decoded,
            droppedFrames = dropped,
            decoderSize = "${width}×${height}",
            decoderPaused = paused,
        )
        AppLog.add("CAR", "Experimental video decoder frames=$decoded dropped=$dropped " +
            "size=${width}x${height} paused=$paused")
    }

    fun fallback(owner: String, reason: String) {
        if (_state.value.owner != owner || !_state.value.active) return
        _state.value = _state.value.copy(
            state = "Standard fallback: $reason", enabled = false
        )
        AppLog.add("CAR", "Experimental video fallback: $reason")
    }

    fun end(owner: String) {
        if (_state.value.owner != owner) return
        _state.value = _state.value.copy(active = false, state = "Disconnected (last session)")
        AppLog.add("CAR", "Video session ended")
    }
}
