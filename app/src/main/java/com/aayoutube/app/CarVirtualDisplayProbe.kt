package com.aayoutube.app

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.widget.FrameLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A NON-car test of unprivileged Android virtual-display composition.
 * Draws only a solid synthetic color into a private ImageReader surface.
 * Never captures the phone, touches WebHost, creates audio or starts a new
 * YouTube decoder. The NavigationTemplate/Shizuku car backend is NOT here.
 */
internal data class CarVirtualDisplayProbeStatus(
    val running: Boolean = false,
    val passed: Boolean = false,
    val message: String = "Not tested",
)

internal object CarVirtualDisplayProbe {
    private const val WIDTH = 640
    private const val HEIGHT = 360
    private const val DPI = 160
    private const val TIMEOUT_MS = 7_000L
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(CarVirtualDisplayProbeStatus())
    val state: StateFlow<CarVirtualDisplayProbeStatus> = _state

    private class Session(
        val thread: HandlerThread,
        val reader: ImageReader,
        val display: VirtualDisplay,
        val presentation: Presentation,
    ) {
        var timeout: Runnable? = null
    }

    /** Session is accessed only on the main looper. */
    private var session: Session? = null

    fun start(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { start(context) }
            return
        }
        if (session != null) return
        if (ExperimentalCarVideoMonitor.state.value.active) {
            _state.value = CarVirtualDisplayProbeStatus(
                message = "Unavailable while car video is connected")
            return
        }

        var worker: HandlerThread? = null
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        var window: Presentation? = null
        try {
            worker = HandlerThread("aay-virtual-display-probe").also { it.start() }
            reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            display = manager.createVirtualDisplay(
                "AAYouTube synthetic test", WIDTH, HEIGHT, DPI, reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            ) ?: error("VirtualDisplay unavailable")
            val target = display.display ?: error("Virtual display has no Display")
            window = Presentation(context, target).apply {
                setContentView(FrameLayout(context).apply {
                    setBackgroundColor(android.graphics.Color.rgb(
                        CarVirtualDisplayPixelPolicy.RED,
                        CarVirtualDisplayPixelPolicy.GREEN,
                        CarVirtualDisplayPixelPolicy.BLUE,
                    ))
                })
            }
            val probe = Session(worker, reader, display, window)
            session = probe
            _state.value = CarVirtualDisplayProbeStatus(
                running = true, message = "Checking synthetic display composition…")
            reader.setOnImageAvailableListener({ source ->
                // This callback is on the short-lived worker. Never persist frames.
                val img = runCatching { source.acquireLatestImage() }.getOrNull()
                if (img != null) {
                    val matched = try {
                        val plane = img.planes.firstOrNull()
                        if (plane == null || plane.pixelStride < 3) false else {
                            val index = (img.height / 2).toLong() * plane.rowStride +
                                (img.width / 2).toLong() * plane.pixelStride
                            val bytes = plane.buffer
                            if (index < 0 || index + 2 >= bytes.limit()) false else {
                                val pixel = index.toInt()
                                CarVirtualDisplayPixelPolicy.isExpected(
                                    bytes.get(pixel).toInt() and 255,
                                    bytes.get(pixel + 1).toInt() and 255,
                                    bytes.get(pixel + 2).toInt() and 255,
                                )
                            }
                        }
                    } catch (_: Exception) { false } finally {
                        runCatching { img.close() }
                    }
                    if (matched) main.post {
                        finish(probe, true,
                            "Synthetic virtual-display frame verified (640×360)")
                    }
                }
            }, Handler(worker.looper))
            probe.timeout = Runnable {
                finish(probe, false,
                    "No matching test-pattern frame within the check window")
            }.also { main.postDelayed(it, TIMEOUT_MS) }
            window.show()
            AppLog.add("CAR", "Synthetic virtual-display composition check started")
        } catch (t: Exception) {
            val active = session
            if (active != null) {
                finish(active, false, "Virtual display unavailable: " + t.javaClass.simpleName)
            } else {
                runCatching { window?.dismiss() }
                runCatching { display?.release() }
                runCatching { reader?.close() }
                runCatching { worker?.quitSafely() }
                _state.value = CarVirtualDisplayProbeStatus(
                    message = "Virtual display unavailable: " + t.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cancel() }
            return
        }
        session?.let { finish(it, false, "Synthetic display test cancelled") }
    }

    private fun finish(probe: Session, passed: Boolean, message: String) {
        if (session !== probe) return
        session = null
        probe.timeout?.let { main.removeCallbacks(it) }
        runCatching { probe.presentation.dismiss() }
        runCatching { probe.reader.setOnImageAvailableListener(null, null) }
        runCatching { probe.display.release() }
        runCatching { probe.reader.close() }
        runCatching { probe.thread.quitSafely() }
        _state.value = CarVirtualDisplayProbeStatus(passed = passed, message = message)
        AppLog.add("CAR", "Synthetic virtual-display check: " + message)
    }
}
