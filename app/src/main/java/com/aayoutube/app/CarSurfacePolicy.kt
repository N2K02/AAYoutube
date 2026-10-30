package com.aayoutube.app

/** Pure pixel geometry for host-visible and stable content areas. */
data class CarSurfaceBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun clamp(w: Int, h: Int): CarSurfaceBounds? =
        CarSurfaceBounds(left.coerceIn(0, w), top.coerceIn(0, h),
            right.coerceIn(0, w), bottom.coerceIn(0, h))
            .takeIf { it.width > 0 && it.height > 0 }
    fun intersect(other: CarSurfaceBounds): CarSurfaceBounds? =
        CarSurfaceBounds(maxOf(left, other.left), maxOf(top, other.top),
            minOf(right, other.right), minOf(bottom, other.bottom))
            .takeIf { it.width > 0 && it.height > 0 }
    fun contains(x: Float, y: Float) =
        x.isFinite() && y.isFinite() && x >= left && x < right && y >= top && y < bottom
}
object CarSurfacePolicy {
    fun viewport(w: Int, h: Int, visible: CarSurfaceBounds?, stable: CarSurfaceBounds?): CarSurfaceBounds {
        val width = w.coerceAtLeast(1)
        val height = h.coerceAtLeast(1)
        val v = visible?.clamp(width, height)
        val s = stable?.clamp(width, height)
        return when {
            v != null && s != null -> v.intersect(s) ?: v
            v != null -> v
            s != null -> s
            else -> CarSurfaceBounds(0, 0, width, height)
        }
    }
}
