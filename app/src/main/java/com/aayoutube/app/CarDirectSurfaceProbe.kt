package com.aayoutube.app

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owner-safe coordinator for a manual test of a distinct SurfaceView layer
 * in the already-existing car window. The current WebView and media session
 * remain attached, but are never decoded or duplicated by this probe.
 */
internal data class CarDirectSurfaceStatus(
    val available: Boolean = false,
    val visible: Boolean = false,
    val awaitingFeedback: Boolean = false,
    val owner: String = "",
    val runId: Int = 0,
    val frameCount: Int = 0,
    val estimatedFps: Int = 0,
    val maxFrameGapMs: Long = 0,
    val readback: String = "Not checked",
    val message: String = "Connect experimental car video for direct-surface test",
)

internal object CarDirectSurfaceProbe {
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(CarDirectSurfaceStatus())
    val state: StateFlow<CarDirectSurfaceStatus> = _state

    private var nextRunId = 0
    private var finishTask: Runnable? = null

    fun register(owner: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { register(owner) }
            return
        }
        val host = ExperimentalCarVideoMonitor.state.value
        if (!host.active || !host.enabled || host.owner != owner) return
        if (_state.value.owner == owner) return
        finishTask?.let { main.removeCallbacks(it) }
        finishTask = null
        _state.value = CarDirectSurfaceStatus(
            available = true, owner = owner,
            message = "Ready: pause video to test a separate rendering surface",
        )
    }

    fun request() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { request() }
            return
        }
        val current = _state.value
        val host = ExperimentalCarVideoMonitor.state.value
        val otherTestRunning = CarVideoPatternProbe.state.value.visible ||
            CarWindowPixelProbe.state.value.running
        if (!CarDirectSurfacePolicy.canStart(
            requestedOwner = current.owner.takeIf { current.available },
            activeOwner = host.owner,
            active = host.active,
            enabled = host.enabled,
            playing = WebHost.snapshot.value.playing,
            anotherTestRunning = otherTestRunning,
            alreadyVisible = current.visible,
        )) {
            AppLog.add("CAR", "Direct surface test rejected by active host / playback gate")
            return
        }
        finishTask?.let { main.removeCallbacks(it) }
        nextRunId++
        _state.value = current.copy(
            visible = true, awaitingFeedback = false, runId = nextRunId,
            frameCount = 0, estimatedFps = 0, maxFrameGapMs = 0,
            readback = "Waiting for first surface frame",
            message = "Rendering moving test pattern in car window for five seconds",
        )
        AppLog.add("CAR", "Synthetic direct SurfaceView test started; WebView unchanged")
        val owner = current.owner
        val runId = nextRunId
        finishTask = Runnable {
            finishTask = null
            val active = _state.value
            if (active.owner == owner && active.runId == runId && active.visible) {
                _state.value = active.copy(
                    visible = false, awaitingFeedback = true,
                    message = "Did the moving test pattern appear on the car screen?",
                )
                AppLog.add("CAR", "Direct SurfaceView test complete; frames=" +
                    active.frameCount + " observedFps=" + active.estimatedFps)
            }
        }.also { main.postDelayed(it, CarDirectSurfacePolicy.DURATION_MS) }
    }

    /** Receive aggregate draw results only, never pixels or image buffers. */
    fun drawn(owner: String, runId: Int, frames: Int, elapsedMs: Long, maxGapMs: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { drawn(owner, runId, frames, elapsedMs, maxGapMs) }
            return
        }
        val current = _state.value
        if (current.owner != owner || current.runId != runId || !current.visible) return
        if (ExperimentalCarVideoMonitor.state.value.owner != owner) return
        if (frames <= current.frameCount) return
        _state.value = current.copy(
            frameCount = frames,
            estimatedFps = CarDirectSurfacePolicy.estimatedFps(frames, elapsedMs),
            maxFrameGapMs = maxGapMs,
        )
    }

    /** A readback of this specific SurfaceView (not the full car Window). */
    fun readback(owner: String, runId: Int, result: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { readback(owner, runId, result) }
            return
        }
        val current = _state.value
        val host = ExperimentalCarVideoMonitor.state.value
        if (current.owner != owner || current.runId != runId ||
            !current.available || host.owner != owner || !host.active) return
        _state.value = current.copy(readback = result)
        AppLog.add("CAR", "Direct SurfaceView synthetic readback: " + result)
    }

    fun reportSeen(seen: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { reportSeen(seen) }
            return
        }
        val current = _state.value
        val host = ExperimentalCarVideoMonitor.state.value
        if (!current.awaitingFeedback || !current.available ||
            current.owner != host.owner || !host.active) return
        _state.value = current.copy(
            awaitingFeedback = false,
            message = if (seen) "Moving surface pattern visible (user confirmed)"
                else "Moving surface pattern NOT visible (user reported)",
        )
        AppLog.add("CAR", "Direct SurfaceView physical display observation=" +
            if (seen) "visible" else "not visible")
    }

    fun stopForPlayback(owner: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { stopForPlayback(owner) }
            return
        }
        if (_state.value.owner != owner || !_state.value.visible) return
        finishTask?.let { main.removeCallbacks(it) }
        finishTask = null
        _state.value = _state.value.copy(
            visible = false, awaitingFeedback = false,
            message = "Direct surface test stopped because playback resumed",
        )
        AppLog.add("CAR", "Direct surface test stopped on playback resume")
    }

    fun unregister(owner: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { unregister(owner) }
            return
        }
        if (_state.value.owner != owner) return
        finishTask?.let { main.removeCallbacks(it) }
        finishTask = null
        _state.value = CarDirectSurfaceStatus(message = "Car video disconnected")
    }
}
