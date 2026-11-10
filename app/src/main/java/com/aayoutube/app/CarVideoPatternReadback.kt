package com.aayoutube.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window

/**
 * An optional one-shot PixelCopy of the synthetic car-window test bars.
 * No WebView is attached, no screen recording is made and no pixels are saved.
 * Reports ONLY how many fixed color-bar samples match the expected pattern.
 *
 * All mutable state lives on the Android main looper. Exactly one request can
 * be outstanding; if the platform never calls back, that single tiny buffer
 * remains allocated rather than spawning retries indefinitely.
 */
internal object CarVideoPatternReadback {
    private const val WIDTH = 96
    private const val HEIGHT = 54
    private const val MAX_CALLBACK_MS = 1_800L
    private val main = Handler(Looper.getMainLooper())

    private class Request(
        val owner: String,
        val testId: Int,
        val bitmap: Bitmap,
    ) {
        var timeout: Runnable? = null
    }

    private var pending: Request? = null

    fun capture(owner: String, testId: Int, window: Window?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { capture(owner, testId, window) }
            return
        }
        val host = ExperimentalCarVideoMonitor.state.value
        val visual = CarVideoPatternProbe.state.value
        if (!host.active || !host.enabled || host.owner != owner ||
            !visual.visible || visual.owner != owner || visual.testId != testId) return

        if (pending != null) {
            CarVideoPatternProbe.windowReadback(owner, testId,
                "Previous car-window readback still pending")
            return
        }
        if (window?.decorView?.isAttachedToWindow != true) {
            CarVideoPatternProbe.windowReadback(owner, testId,
                "Car window not attached for pattern readback")
            return
        }
        val bitmap = try {
            Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            CarVideoPatternProbe.windowReadback(owner, testId,
                "Pattern readback unavailable: " + e.javaClass.simpleName)
            return
        }
        val request = Request(owner, testId, bitmap)
        pending = request
        CarVideoPatternProbe.windowReadback(owner, testId,
            "Reading the synthetic car window pattern…")
        request.timeout = Runnable {
            if (pending === request) {
                CarVideoPatternProbe.windowReadback(owner, testId,
                    "Pattern window readback timed out (inconclusive)")
                AppLog.add("CAR", "Synthetic car-window PixelCopy timed out")
                // Do not recycle or enable new requests until PixelCopy returns.
            }
        }.also { main.postDelayed(it, MAX_CALLBACK_MS) }

        try {
            PixelCopy.request(
                window, bitmap,
                PixelCopy.OnPixelCopyFinishedListener { code ->
                    request.timeout?.let { main.removeCallbacks(it) }
                    try {
                        if (pending === request && code == PixelCopy.SUCCESS) {
                            val samples = IntArray(WIDTH * HEIGHT)
                            bitmap.getPixels(samples, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                            val result = CarVideoPatternPixelPolicy.analyze(samples, WIDTH, HEIGHT)
                            CarVideoPatternProbe.windowReadback(owner, testId, result.assessment)
                            AppLog.add("CAR", "Synthetic car-window bars matched=" +
                                result.matched + "/" + result.samples +
                                " (does not prove physical car display)")
                        } else if (pending === request) {
                            CarVideoPatternProbe.windowReadback(owner, testId,
                                "Pattern PixelCopy returned code $code (inconclusive)")
                        }
                    } catch (e: Exception) {
                        if (pending === request) CarVideoPatternProbe.windowReadback(owner, testId,
                            "Pattern pixel analysis unavailable: " + e.javaClass.simpleName)
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
            CarVideoPatternProbe.windowReadback(owner, testId,
                "Pattern readback unavailable: " + e.javaClass.simpleName)
        }
    }
}
