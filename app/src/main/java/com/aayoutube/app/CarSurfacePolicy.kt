package com.aayoutube.app

/** Bounds supplied by an external car host; reject unusable allocation sizes. */
internal object CarSurfacePolicy {
    data class Geometry(val width: Int, val height: Int, val dpi: Int)

    fun geometry(width: Int, height: Int, dpi: Int): Geometry? {
        if (width !in 1..8192 || height !in 1..8192 ||
            width.toLong() * height > 16_777_216L) return null
        return Geometry(width, height, dpi.takeIf { it in 72..960 } ?: 160)
    }

    fun point(x: Float, y: Float, width: Int, height: Int): Pair<Float, Float>? {
        if (width <= 0 || height <= 0 || !x.isFinite() || !y.isFinite()) return null
        return x.coerceIn(0f, (width - 1).toFloat()) to
            y.coerceIn(0f, (height - 1).toFloat())
    }
}
