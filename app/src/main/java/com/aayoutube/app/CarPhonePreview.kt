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

/**
 * Bounded PixelCopy of our own projected car window for verification and low-rate previews.
 * Full phone playback retains PhoneMirror. Secure buffers are never bypassed.
 */
internal object CarPhonePreview {
    private data class Source(val owner: String, val window: WeakReference<Window>, val view: WeakReference<View>)
    private var source: Source? = null
    private var outstanding = 0
    private var lastStartedAt = 0L
    // Lazy: keeps the object usable from plain unit tests.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private const val TIMEOUT_MS = 250L
    private const val SAMPLE_WIDTH = 64

    fun bind(owner: String, window: Window?, view: View) {
        if (window != null && WebHost.isActiveCarHost(owner))
            source = Source(owner, WeakReference(window), WeakReference(view))
    }

    fun unbind(owner: String) {
        if (source?.owner == owner) source = null
    }

    /** Near-black share of the car video region, or null when no capture was possible. */
    suspend fun darkPercent(): Int? {
        val bitmap = capture() ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            FrameDarkness.percent(pixels)
        } finally { bitmap.recycle() }
    }

    suspend fun preview(): Bitmap? {
        if (WebHost.verifyingCrossDisplay) return null // health verification takes priority
        val bitmap = capture(SharedPreviewPolicy.MAX_EDGE) ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        if (FrameDarkness.percent(pixels) >= 98) { bitmap.recycle(); return null }
        return bitmap
    }

    private suspend fun capture(maxEdge: Int = SAMPLE_WIDTH): Bitmap? {
        val s = source ?: return null
        if (!CaptureBudget.mayStart(outstanding, lastStartedAt, SystemClock.elapsedRealtime())) return null
        if (!WebHost.carShown || !WebHost.isActiveCarHost(s.owner)) return null
        val window = s.window.get() ?: return null
        val view = s.view.get() ?: return null
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        val (w, h) = SharedPreviewPolicy.size(rect.width(), rect.height(), maxEdge)
        if (w <= 0 || h <= 0) return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
                if (bitmap == null) { cont.resume(null); return@suspendCancellableCoroutine }
                outstanding++
                lastStartedAt = SystemClock.elapsedRealtime()
                try {
                    PixelCopy.request(window, rect, bitmap, { result ->
                        outstanding = (outstanding - 1).coerceAtLeast(0)
                        if (result == PixelCopy.SUCCESS && cont.isActive &&
                            source === s && WebHost.isActiveCarHost(s.owner)) {
                            cont.resume(bitmap)
                        } else {
                            // Timed out or superseded: the late bitmap is released here.
                            bitmap.recycle()
                            if (cont.isActive) cont.resume(null)
                        }
                    }, main)
                } catch (_: Exception) {
                    outstanding = (outstanding - 1).coerceAtLeast(0)
                    bitmap.recycle()
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }
}
