package com.aayoutube.app

/**
 * Pure geometry used by the phone's simulated car preview and projection
 * diagnostics. It never resizes or reparents the live WebView.
 */
internal data class CarVideoPreset(val label: String, val width: Int, val height: Int)

internal data class CarVideoBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

internal object CarVideoViewportPolicy {
    val presets = listOf(
        CarVideoPreset("800×480", 800, 480),
        CarVideoPreset("1280×720", 1280, 720),
        CarVideoPreset("1920×1080", 1920, 1080),
        CarVideoPreset("1024×600", 1024, 600),
    )

    /**
     * Diagnostic-only fit within the actually visible part of a car view.
     * Input coordinates are LOCAL to the FrameLayout, not absolute screen
     * coordinates. An absent, clipped-out or inconsistent rectangle falls
     * back to the full view rather than producing negative insets.
     */
    fun fitVisible16by9(
        width: Int, height: Int, visible: CarVideoBounds,
    ): CarVideoBounds {
        val right = visible.left.toLong() + visible.width
        val bottom = visible.top.toLong() + visible.height
        if (width <= 0 || height <= 0 ||
            visible.width <= 0 || visible.height <= 0 ||
            visible.left < 0 || visible.top < 0 ||
            right > width.toLong() || bottom > height.toLong()
        ) return fit16by9(width, height)
        return fit16by9(width, height,
            insetLeft = visible.left,
            insetTop = visible.top,
            insetRight = (width.toLong() - right).toInt(),
            insetBottom = (height.toLong() - bottom).toInt(),
        )
    }

    /** Letterbox a 16:9 video inside the supplied safe viewport. */
    fun fit16by9(
        width: Int,
        height: Int,
        insetLeft: Int = 0,
        insetTop: Int = 0,
        insetRight: Int = 0,
        insetBottom: Int = 0,
    ): CarVideoBounds {
        val left = insetLeft.coerceAtLeast(0)
        val top = insetTop.coerceAtLeast(0)
        val right = insetRight.coerceAtLeast(0)
        val bottom = insetBottom.coerceAtLeast(0)
        val w = (width.toLong() - left - right).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val h = (height.toLong() - top - bottom).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        if (w == 0 || h == 0) return CarVideoBounds(left, top, 0, 0)
        val videoW: Int
        val videoH: Int
        if (w.toLong() * 9 <= h.toLong() * 16) {
            videoW = w
            videoH = (w.toLong() * 9 / 16).toInt()
        } else {
            videoH = h
            videoW = (h.toLong() * 16 / 9).toInt()
        }
        return CarVideoBounds(left + (w - videoW) / 2,
            top + (h - videoH) / 2, videoW, videoH)
    }
}
