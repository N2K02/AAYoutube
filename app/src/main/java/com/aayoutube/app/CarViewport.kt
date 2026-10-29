package com.aayoutube.app

/**
 * AABrowser-style visible-area handling for the Android Auto navigation surface.
 * The surface dimensions stay fixed; map controls, split-screen panels, and transient
 * action strips change only the area in which our WebView can draw and receive taps.
 *
 * Kept Android-framework-free so all layout and input edge cases can be unit tested.
 */
internal data class CarArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun clipped(width: Int, height: Int): CarArea? {
        val l = left.coerceIn(0, width)
        val t = top.coerceIn(0, height)
        val r = right.coerceIn(0, width)
        val b = bottom.coerceIn(0, height)
        return if (r > l && b > t) CarArea(l, t, r, b) else null
    }
}

internal object CarViewport {
    fun contentArea(width: Int, height: Int, visible: CarArea?, stable: CarArea?): CarArea {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        return visible?.clipped(w, h) ?: stable?.clipped(w, h) ?: CarArea(0, 0, w, h)
    }

    fun localTouch(x: Float, y: Float, area: CarArea): Pair<Float, Float>? =
        if (x >= area.left && x < area.right && y >= area.top && y < area.bottom)
            (x - area.left) to (y - area.top)
        else null
}
