package com.aayoutube.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manual, low-resolution PixelCopy of AAYouTube's own legacy car Window.
 * This is NOT a screenshot of the final head-unit panel and cannot verify
 * that Android Auto paints it. All sampled pixels are discarded immediately.
 * No output is saved, shared, uploaded or used for automatic recovery.
 */
internal data class CarWindowPixelProbeStatus(
    val available: Boolean = false,
    val running: Boolean = false,
    val message: String = "Connect experimental car video to check window rendering",
    val darkPercent: Int? = null,
    val brightnessRange: Int? = null,
)

internal object CarWindowPixelProbe {
    private const val WIDTH = 96
    private const val HEIGHT = 54
    private const val TIMEOUT_MS = 3_000L

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(CarWindowPixelProbeStatus())
    val state: StateFlow<CarWindowPixelProbeStatus> = _state

    private var activeOwner: String? = null
    private var carCapture: (() -> Unit)? = null
    private class Request(val owner: String, val bitmap: Bitmap) {
        var timeout: Runnable? = null
    }
    private var pending: Request? = null

    /** Only the active legacy car host can register an on-demand action. */
    fun register(owner: String, capture: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { register(owner, capture) }
            return
        }
        if (ExperimentalCarVideoMonitor.state.value.owner != owner ||
            !ExperimentalCarVideoMonitor.state.value.enabled) return
        activeOwner = owner
        carCapture = capture
        _state.value = CarWindowPixelProbeStatus(
            available = true, message = "Ready for manual car-window pixel check")
    }

    fun unregister(owner: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { unregister(owner) }
            return
        }
        if (activeOwner != owner) return
        activeOwner = null
        carCapture = null
        clearPending("Car video connection ended")
        _state.value = CarWindowPixelProbeStatus(message = "Car video disconnected")
    }

    /** Called from phone settings; never records pixels unless clicked. */
    fun request() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { request() }
            return
        }
        val owner = activeOwner
        if (pending != null || owner == null || carCapture == null ||
            ExperimentalCarVideoMonitor.state.value.owner != owner ||
            !ExperimentalCarVideoMonitor.state.value.enabled) return
        carCapture?.invoke()
    }

    /**
     * Only the active car host calls this, using its current car Window.
     * Window readback excludes content from secure/independent surfaces.
     */
    fun capture(owner: String, window: Window?, frame: View?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { capture(owner, window, frame) }
            return
        }
        if (owner != activeOwner || pending != null) return
        if (window == null || frame == null || !frame.isAttachedToWindow ||
            frame.width <= 0 || frame.height <= 0) {
            failure("Window or car view not ready")
            return
        }
        val visible = Rect()
        if (!frame.getLocalVisibleRect(visible) || visible.isEmpty) {
            failure("Car window has no visible video region")
            return
        }
        val location = IntArray(2)
        frame.getLocationInWindow(location)
        val region = Rect(
            location[0] + visible.left,
            location[1] + visible.top,
            location[0] + visible.right,
            location[1] + visible.bottom,
        )
        if (region.isEmpty) {
            failure("No valid sample region")
            return
        }

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val request = Request(owner, bitmap)
        pending = request
        _state.value = CarWindowPixelProbeStatus(
            available = true, running = true, message = "Sampling the current car window…")
        request.timeout = Runnable {
            if (pending === request) {
                pending = null
                _state.value = CarWindowPixelProbeStatus(
                    available = true,
                    message = "Car-window readback timed out (inconclusive)")
                AppLog.add("CAR", "Manual window pixel readback timed out")
            }
            // PixelCopy may still own bitmap; its eventual callback recycles it.
        }.also { main.postDelayed(it, TIMEOUT_MS) }
        try {
            PixelCopy.request(
                window, region, bitmap,
                PixelCopy.OnPixelCopyFinishedListener { result ->
                    request.timeout?.let { main.removeCallbacks(it) }
                    val valid = pending === request && activeOwner == owner &&
                        ExperimentalCarVideoMonitor.state.value.owner == owner
                    try {
                        if (valid) {
                            if (result == PixelCopy.SUCCESS) {
                                val pixels = IntArray(WIDTH * HEIGHT)
                                bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                                val stats = CarWindowPixelPolicy.analyze(pixels)
                                _state.value = CarWindowPixelProbeStatus(
                                    available = true,
                                    message = stats.assessment,
                                    darkPercent = stats.darkPercent,
                                    brightnessRange = stats.brightnessRange,
                                )
                                AppLog.add("CAR", "Manual car-window sample: dark=" +
                                    stats.darkPercent + "% range=" + stats.brightnessRange +
                                    " (not proof of head-unit paint)")
                            } else {
                                failure("PixelCopy unavailable (result code $result)")
                            }
                        }
                    } catch (e: Exception) {
                        if (valid) failure("Pixel analysis unavailable: " + e.javaClass.simpleName)
                    } finally {
                        bitmap.recycle()
                        if (pending === request) pending = null
                    }
                },
                main,
            )
        } catch (e: Exception) {
            request.timeout?.let { main.removeCallbacks(it) }
            if (pending === request) pending = null
            bitmap.recycle()
            failure("Window readback unavailable: " + e.javaClass.simpleName)
        }
    }

    private fun clearPending(message: String) {
        pending?.timeout?.let { main.removeCallbacks(it) }
        // Pending bitmap must stay valid until PixelCopy calls back.
        pending = null
        _state.value = CarWindowPixelProbeStatus(message = message)
    }

    private fun failure(message: String) {
        _state.value = CarWindowPixelProbeStatus(
            available = activeOwner != null, message = message)
        AppLog.add("CAR", "Manual car-window probe: " + message)
    }
}
