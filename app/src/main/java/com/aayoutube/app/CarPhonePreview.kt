package com.aayoutube.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.Window
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** A bounded, best-effort preview of our own projected window; no second player
 * and no second decoder. Secure/protected buffers are never bypassed.
 */
internal object CarPhonePreview {
    private data class Source(val owner: String, val window: WeakReference<Window>, val view: WeakReference<View>)
    private var source: Source? = null
    private var inFlightSince = 0L
    // Lazy: the pure pacing helpers must stay usable without an Android main looper.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    const val INTERVAL_MS = 500L
    private const val TIMEOUT_MS = 250L

    fun intervalMs(compact: Boolean, failures: Int): Long =
        if (failures >= 3) 4000L else if (compact) 1500L else INTERVAL_MS

    fun bind(owner: String, window: Window?, view: View) {
        if (window != null && WebHost.isActiveCarHost(owner))
            source = Source(owner, WeakReference(window), WeakReference(view))
    }

    fun unbind(owner: String) {
        if (source?.owner == owner) {
            source = null
            inFlightSince = 0L
        }
    }

    fun allowed(projected: Boolean, playing: Boolean, visible: Boolean, selected: Boolean): Boolean =
        projected && playing && visible && selected

    /** A frame for the phone, or null (not projected, timeout, or a black/blank frame). */
    suspend fun frame(compact: Boolean = false): Bitmap? {
        val bitmap = capture(PreviewFramePolicy.captureWidth(compact)) ?: return null
        val dark = darkness(bitmap)
        if (!PreviewFramePolicy.accept(dark)) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    /** Near-black share of the car video region, for renderer health checks. */
    suspend fun darkPercent(): Int? {
        val bitmap = capture(64) ?: return null
        return try { darkness(bitmap) } finally { bitmap.recycle() }
    }

    private fun darkness(bitmap: Bitmap): Int {
        // A coarse grid is enough and keeps the check cheap.
        val cols = 16
        val rows = 9
        val pixels = IntArray(cols * rows)
        for (y in 0 until rows) for (x in 0 until cols) {
            pixels[y * cols + x] = bitmap.getPixel(
                (x * bitmap.width / cols).coerceAtMost(bitmap.width - 1),
                (y * bitmap.height / rows).coerceAtMost(bitmap.height - 1))
        }
        return FrameDarkness.percent(pixels)
    }

    private suspend fun capture(maxWidth: Int): Bitmap? {
        val s = source ?: return null
        val now = SystemClock.elapsedRealtime()
        // A PixelCopy callback that never arrived must not stop all later previews.
        if (inFlightSince != 0L && !PreviewFramePolicy.requestStuck(inFlightSince, now)) return null
        if (!WebHost.carShown || !WebHost.isActiveCarHost(s.owner)) return null
        val window = s.window.get() ?: return null
        val view = s.view.get() ?: return null
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        val (w, h) = PlayerTransitionPolicy.captureSize(rect.width(), rect.height(), maxWidth)
        if (w <= 0 || h <= 0) return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
                if (bitmap == null) { cont.resume(null); return@suspendCancellableCoroutine }
                val started = SystemClock.elapsedRealtime()
                inFlightSince = started
                try {
                    PixelCopy.request(window, rect, bitmap, { result ->
                        if (inFlightSince == started) inFlightSince = 0L
                        if (result == PixelCopy.SUCCESS && cont.isActive &&
                            source === s && WebHost.isActiveCarHost(s.owner)) {
                            cont.resume(bitmap)
                        } else {
                            // Timed out or superseded: the late bitmap is never shown.
                            bitmap.recycle()
                            if (cont.isActive) cont.resume(null)
                        }
                    }, main)
                } catch (_: Exception) {
                    inFlightSince = 0L
                    bitmap.recycle()
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }
}
