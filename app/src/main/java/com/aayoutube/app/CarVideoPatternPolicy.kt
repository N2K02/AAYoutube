package com.aayoutube.app

/**
 * Guard for manual, temporary test pattern shown on the existing legacy
 * Android Auto Compose window. No new renderer, media session or permission.
 */
internal object CarVideoPatternPolicy {
    const val DURATION_MS = 3_000L

    fun canShow(
        requestedOwner: String?,
        activeOwner: String,
        hostActive: Boolean,
        optedIn: Boolean,
        videoPlaying: Boolean,
        currentlyVisible: Boolean,
    ): Boolean = requestedOwner != null && requestedOwner == activeOwner &&
        hostActive && optedIn && !videoPlaying && !currentlyVisible
}
