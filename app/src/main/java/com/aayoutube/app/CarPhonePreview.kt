package com.aayoutube.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** A bounded, best-effort preview of our own projected window; no second player.
 * Secure/protected buffers are never bypassed. PixelCopy can omit video layers.
 */
internal object CarPhonePreview {
    private data class Source(val owner: String, val window: WeakReference<Window>, val view: WeakReference<View>)
    private var source: Source? = null
    private var inFlight = false
    const val INTERVAL_MS = 500L
    fun intervalMs(compact: Boolean, failures: Int): Long =
        if (failures >= 3) 4000L else if (compact) 1500L else INTERVAL_MS

    fun bind(owner: String, window: Window?, view: View) {
        if (window != null && WebHost.isActiveCarHost(owner))
            source = Source(owner, WeakReference(window), WeakReference(view))
    }

    fun unbind(owner: String) {
        if (source?.owner == owner) source = null
    }

    fun allowed(projected: Boolean, playing: Boolean, visible: Boolean, selected: Boolean): Boolean =
        projected && playing && visible && selected

    suspend fun frame(): Bitmap? {
        val s = source ?: return null
        if (inFlight || !WebHost.carShown || WebHost.carAudioRetained.value || !WebHost.isActiveCarHost(s.owner)) return null
        val window = s.window.get() ?: return null
        val view = s.view.get() ?: return null
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        val (w, h) = PlayerTransitionPolicy.captureSize(rect.width(), rect.height(), 480)
        if (w <= 0 || h <= 0) return null
        return withTimeoutOrNull(180L) {
            suspendCancellableCoroutine { cont ->
                val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
                if (bitmap == null) { cont.resume(null); return@suspendCancellableCoroutine }
                inFlight = true
                try {
                    PixelCopy.request(window, rect, bitmap, { result ->
                        inFlight = false
                        if (result == PixelCopy.SUCCESS && cont.isActive &&
                            source === s && WebHost.isActiveCarHost(s.owner)) {
                            cont.resume(bitmap)
                        } else {
                            bitmap.recycle()
                            if (cont.isActive) cont.resume(null)
                        }
                    }, Handler(Looper.getMainLooper()))
                } catch (_: Exception) {
                    inFlight = false
                    bitmap.recycle()
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }
}
