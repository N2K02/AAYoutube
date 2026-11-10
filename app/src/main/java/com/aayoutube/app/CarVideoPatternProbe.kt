package com.aayoutube.app

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manual visual check of the ORIGINAL car Compose window.
 *
 * The pattern is a short-lived overlay in the existing car activity, not a
 * second decoder, host service, virtual display or synthetic input source.
 * Only human feedback can indicate whether it reached the physical panel.
 */
internal data class CarVideoPatternStatus(
    val available: Boolean = false,
    val visible: Boolean = false,
    val awaitingFeedback: Boolean = false,
    val message: String = "Connect experimental car video to test the display",
    val owner: String = "",
)

internal object CarVideoPatternProbe {
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(CarVideoPatternStatus())
    val state: StateFlow<CarVideoPatternStatus> = _state
    private var hide: Runnable? = null

    /** The active host is always registered from its lifecycle on the main thread. */
    fun register(owner: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { register(owner) }
            return
        }
        val video = ExperimentalCarVideoMonitor.state.value
        if (!video.active || !video.enabled || video.owner != owner) return
        hide?.let { main.removeCallbacks(it) }
        hide = null
        _state.value = CarVideoPatternStatus(
            available = true, owner = owner,
            message = "Ready: pause playback, then show the car test pattern",
        )
    }

    fun request() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { request() }
            return
        }
        val owner = _state.value.owner
        val active = ExperimentalCarVideoMonitor.state.value
        if (!CarVideoPatternPolicy.canShow(
                requestedOwner = owner.takeIf { _state.value.available },
                activeOwner = active.owner,
                hostActive = active.active,
                optedIn = active.enabled,
                videoPlaying = WebHost.snapshot.value.playing,
                currentlyVisible = _state.value.visible,
            )
        ) {
            AppLog.add("CAR", "Test pattern not shown: inactive host, playing media or invalid state")
            return
        }
        hide?.let { main.removeCallbacks(it) }
        _state.value = _state.value.copy(
            visible = true, awaitingFeedback = false,
            message = "Test bars displayed in the car app for 3 seconds",
        )
        AppLog.add("CAR", "Manual car test pattern requested; audio/video unchanged")
        val task = Runnable {
            hide = null
            if (_state.value.owner == owner && _state.value.visible) {
                _state.value = _state.value.copy(
                    visible = false, awaitingFeedback = true,
                    message = "Did the color bars appear on the car screen?",
                )
                AppLog.add("CAR", "Manual car test pattern ended; awaiting user observation")
            }
        }
        hide = task
        main.postDelayed(task, CarVideoPatternPolicy.DURATION_MS)
    }

    /** If playback resumes mid-test, reveal the video immediately. */
    fun stopForPlayback(owner: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { stopForPlayback(owner) }
            return
        }
        if (_state.value.owner != owner || !_state.value.visible) return
        hide?.let { main.removeCallbacks(it) }
        hide = null
        _state.value = _state.value.copy(
            visible = false, awaitingFeedback = false,
            message = "Display test stopped when playback resumed",
        )
        AppLog.add("CAR", "Car test pattern removed: video playback resumed")
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
            message = if (seen)
                "Color bars visible on the car (user confirmed)"
            else
                "Color bars NOT visible on the car (user reported)",
        )
        AppLog.add("CAR", "Manual car test pattern user observation=" +
            if (seen) "visible" else "not visible")
    }

    fun unregister(owner: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { unregister(owner) }
            return
        }
        if (_state.value.owner != owner) return
        hide?.let { main.removeCallbacks(it) }
        hide = null
        _state.value = CarVideoPatternStatus(message = "Car video disconnected")
    }
}
