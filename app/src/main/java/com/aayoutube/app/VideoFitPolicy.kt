package com.aayoutube.app

/** Fit / Fill / Stretch for a video that fills the screen, as AABrowser's video-fit. */
internal object VideoFitPolicy {
    val MODES = listOf("fit", "fill", "stretch")

    fun normalize(mode: String?): String = if (mode in MODES) mode!! else "fit"
    fun next(mode: String?): String = MODES[(MODES.indexOf(normalize(mode)) + 1) % MODES.size]
    fun label(mode: String?): String = when (normalize(mode)) {
        "fill" -> "Video: fill (cropped)"
        "stretch" -> "Video: stretch"
        else -> "Video: fit"
    }

    /** Skip buttons as AABrowser's option: 10 s back / forward inside the video. */
    const val SKIP_MS = 10_000L
    fun seekTarget(posMs: Long, durMs: Long, forward: Boolean): Long {
        val target = posMs.coerceAtLeast(0L) + if (forward) SKIP_MS else -SKIP_MS
        return target.coerceIn(0L, if (durMs > 0) durMs else Long.MAX_VALUE)
    }
}
