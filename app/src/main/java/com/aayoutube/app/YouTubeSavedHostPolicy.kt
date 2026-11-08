package com.aayoutube.app

/** Restrict restored/history YouTube URLs to an exact domain boundary.
 * A simple endsWith("youtube.com") also accepts evilyoutube.com. */
internal object YouTubeSavedHostPolicy {
    fun matches(host: String?): Boolean {
        val normalized = host?.lowercase() ?: return false
        return normalized == "youtube.com" || normalized.endsWith(".youtube.com")
    }
}
