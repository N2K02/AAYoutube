package com.aayoutube.app

/** Allowed values for the player options on the Features page (also sent to aay.js). */
internal object PlayerPrefsPolicy {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    val seekSeconds = listOf(5, 10, 15, 30)
    val captions = listOf("default", "on", "off")

    /** YouTube's own double-tap length; at this value the page gesture is left untouched. */
    const val YOUTUBE_SEEK_SECONDS = 10

    fun normalizeSpeed(value: Float): Float =
        if (!value.isFinite()) 1f else speeds.minByOrNull { kotlin.math.abs(it - value) } ?: 1f

    fun normalizeSeek(value: Int): Int = if (value in seekSeconds) value else YOUTUBE_SEEK_SECONDS

    fun normalizeCaptions(value: String?): String = value?.takeIf { it in captions } ?: "default"

    fun speedLabel(value: Float): String =
        (if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()) + "x"
}
