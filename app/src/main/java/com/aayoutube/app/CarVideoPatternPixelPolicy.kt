package com.aayoutube.app

/**
 * Checks the known synthetic seven-color pattern, never arbitrary video.
 * Samples three scanlines through the top half of the existing Compose car
 * window. No image data survives this method.
 */
internal data class CarVideoPatternPixelResult(
    val matched: Int,
    val samples: Int,
    val assessment: String,
) {
    val matchPercent: Int get() = if (samples == 0) 0 else matched * 100 / samples
}

internal object CarVideoPatternPixelPolicy {
    private const val TOLERANCE = 48
    private val expected = intArrayOf(
        0x00FFFFFF, // white
        0x00FFFF00, // yellow
        0x0000FFFF, // cyan
        0x0000FF00, // green
        0x00FF00FF, // magenta
        0x00FF0000, // red
        0x000000FF, // blue
    )
    private val rows = intArrayOf(18, 30, 42)

    fun analyze(pixels: IntArray, width: Int, height: Int): CarVideoPatternPixelResult {
        if (width < 14 || height < 20 || pixels.size.toLong() < width.toLong() * height) {
            return CarVideoPatternPixelResult(0, 0, "Window pattern sample unavailable")
        }
        var matched = 0
        expected.forEachIndexed { bar, reference ->
            val x = ((bar * 2L + 1L) * width / 14L).toInt().coerceIn(0, width - 1)
            rows.forEach { percentage ->
                val y = (height * percentage / 100).coerceIn(0, height - 1)
                val pixel = pixels[y * width + x]
                if (close(pixel, reference)) matched++
            }
        }
        val total = expected.size * rows.size
        return CarVideoPatternPixelResult(matched, total, when {
            matched >= 17 ->
                "Color-bar pattern detected in app window buffer (not head-unit proof)"
            matched <= 3 ->
                "Color-bar pattern absent from window sample (inconclusive)"
            else ->
                "Partial color-bar pattern in window sample (inconclusive)"
        })
    }

    private fun close(actual: Int, expected: Int): Boolean {
        val channels = intArrayOf(16, 8, 0)
        return channels.all { shift ->
            kotlin.math.abs(((actual ushr shift) and 255) -
                ((expected ushr shift) and 255)) <= TOLERANCE
        }
    }
}
