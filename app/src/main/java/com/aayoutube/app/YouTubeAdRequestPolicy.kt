package com.aayoutube.app

/**
 * Keep YouTube media requests unmodified. Its player can fail when ad-resource
 * requests are suppressed before the YouTube page's injected script sees them.
 * Parity with AABrowser's AdBlockHost.shouldBlock(pageHost).
 */
internal object YouTubeAdRequestPolicy {
    private val scriptManaged = setOf(
        "youtube.com", "youtu.be", "youtubekids.com", "googlevideo.com", "ytimg.com"
    )
    private val blocked = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com"
    )

    private fun matches(host: String?, hosts: Set<String>): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return hosts.any { h == it || h.endsWith(".$it") }
    }

    fun shouldBlock(resourceHost: String?, pageHost: String?): Boolean {
        if (matches(pageHost, scriptManaged) || matches(resourceHost, scriptManaged)) return false
        return matches(resourceHost, blocked)
    }
}
