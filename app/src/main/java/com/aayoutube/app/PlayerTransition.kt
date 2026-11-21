package com.aayoutube.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Rectangle in px, independent of Android/Compose types so it is unit-testable. */
internal data class PxRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Full player <-> mini player animation, like the YouTube app.
 * The WebView is resized ONCE (per-frame resizing stalls Chromium's decoder and
 * translucency drops its video overlay path). A captured frame of the video
 * travels from the old to the new bounds above it, then fades out.
 */
internal object PlayerTransitionPolicy {
    const val MOVE_MS = 280
    const val FADE_MS = 140
    const val CAPTURE_TIMEOUT_MS = 120L
    const val MINI_CORNER_DP = 12f

    /** PiP, car screens and the hidden player keep their own (system) transitions. */
    fun animates(inPip: Boolean, onCar: Boolean, projectedToCar: Boolean, hidden: Boolean): Boolean =
        !inPip && !onCar && !projectedToCar && !hidden

    fun lerp(from: PxRect, to: PxRect, t: Float): PxRect {
        val f = t.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * f
        return PxRect(mix(from.left, to.left), mix(from.top, to.top),
            mix(from.width, to.width), mix(from.height, to.height))
    }

    /** Rounded corners grow towards the mini player and shrink when expanding. */
    fun cornerDp(toMini: Boolean, t: Float): Float {
        val f = t.coerceIn(0f, 1f)
        return MINI_CORNER_DP * if (toMini) f else 1f - f
    }

    /** Video area of the full player: top 16:9 band in portrait, everything in landscape. */
    fun videoBand(width: Float, height: Float): PxRect =
        PxRect(0f, 0f, width, if (width >= height) height else minOf(height, width * 9f / 16f))

    /** Capture at reduced size: it is shown for a fraction of a second. */
    fun captureSize(width: Int, height: Int, maxWidth: Int = 720): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        if (width <= maxWidth) return width to height
        return maxWidth to (height.toLong() * maxWidth / width).toInt().coerceAtLeast(1)
    }
}

/** A copy of what is on screen in [rect] (window coordinates), or null. Never blocks long. */
internal suspend fun captureWindowRegion(window: Window, rect: android.graphics.Rect): Bitmap? {
    val (w, h) = PlayerTransitionPolicy.captureSize(rect.width(), rect.height())
    if (w <= 0 || h <= 0) return null
    return withTimeoutOrNull(PlayerTransitionPolicy.CAPTURE_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val bmp = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
            if (bmp == null) { cont.resume(null); return@suspendCancellableCoroutine }
            runCatching {
                PixelCopy.request(window, rect, bmp, { result ->
                    if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) bmp else null)
                }, Handler(Looper.getMainLooper()))
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }
}
