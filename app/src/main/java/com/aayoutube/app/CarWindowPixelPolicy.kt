package com.aayoutube.app

/**
 * Aggregate summary of a short-lived Android Window readback.
 * Pixel values and bitmaps are never persisted, transmitted, or logged.
 * Dark scenes and protected video overlays can make a valid window readback
 * appear black; therefore this is NEVER an automatic recovery decision.
 */
internal data class CarWindowPixelStats(
    val total: Int,
    val darkPercent: Int,
    val brightnessRange: Int,
    val assessment: String,
)

internal object CarWindowPixelPolicy {
    fun analyze(pixels: IntArray): CarWindowPixelStats {
        if (pixels.isEmpty()) return CarWindowPixelStats(
            0, 0, 0, "No window pixels available")
        var dark = 0
        var min = 255
        var max = 0
        pixels.forEach { color ->
            val r = (color ushr 16) and 255
            val g = (color ushr 8) and 255
            val b = color and 255
            val brightness = (r * 54 + g * 183 + b * 19) / 256
            if (r <= 16 && g <= 16 && b <= 16) dark++
            min = minOf(min, brightness)
            max = maxOf(max, brightness)
        }
        val darkPercent = (dark.toLong() * 100 / pixels.size).toInt()
        return CarWindowPixelStats(
            pixels.size, darkPercent, max - min,
            when {
                darkPercent >= 95 -> "Window sample predominantly dark (inconclusive)"
                max - min <= 6 -> "Window sample nearly uniform (inconclusive)"
                else -> "Window sample contains varied image data (not head-unit proof)"
            }
        )
    }
}
