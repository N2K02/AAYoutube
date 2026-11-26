package com.aayoutube.app

import android.webkit.JavascriptInterface

/**
 * What the mirror page script can call. Every command names the document that sent it (a new
 * document invalidates older ones), carries an increasing sequence number and the video it saw.
 */
internal class MirrorBridge(
    private val token: String,
    private val isHidden: () -> Boolean,
    private val onControl: (action: String, value: Long, videoId: String) -> Unit,
    private val onIntent: (url: String) -> Unit = {},
    private val dispatch: (() -> Unit) -> Unit = { it() },
    private val initialQuality: () -> String = { "" },
    private val canAcceptNavigation: () -> Boolean = { true },
    private val onLinkResult: (doc: Int, request: String, accepted: Boolean) -> Unit = { _, _, _ -> },
    private val dispatchLink: (() -> Unit) -> Unit = dispatch,
    private val isVideoOnly: () -> Boolean = { false },
) {
    private var currentDoc = 0
    private var lastSeq = 0L

    /** A new document asks first: while hidden or suspended it starts stopped and cannot autoplay. */
    @JavascriptInterface
    fun hidden(): Boolean = isHidden()

    @JavascriptInterface
    fun initialCap(): String = initialQuality()

    /** Mini-player / PiP layout from the first frame of a new document (no YouTube header flash). */
    @JavascriptInterface
    fun videoOnly(): Boolean = isVideoOnly()

    /** A new document registers itself; commands from any earlier document are rejected from now on. */
    @JavascriptInterface
    @Synchronized
    fun open(providedToken: String?): Int {
        if (providedToken != token) return 0
        lastSeq = 0L
        return ++currentDoc
    }

    @JavascriptInterface
    @Synchronized
    fun control(action: String, value: Double, providedToken: String?, doc: Int, seq: Double, videoId: String?) {
        if (providedToken != token || action !in PhoneMirrorPolicy.ACTIONS) return
        if (doc == 0 || doc != currentDoc) return
        if (!seq.isFinite() || seq.toLong() <= lastSeq) return
        if (!value.isFinite() || value < 0.0 || value > PhoneMirrorPolicy.MAX_POSITION_MS) return
        lastSeq = seq.toLong()
        dispatch { synchronized(this) {
            if (doc == currentDoc) onControl(action, value.toLong(), videoId.orEmpty())
        } }
    }

    /** The user clicked a link inside the player page: what they chose, not just that they tapped. */
    @JavascriptInterface
    @Synchronized
    fun intent(url: String?, providedToken: String?, doc: Int): Boolean {
        if (providedToken != token || doc == 0 || doc != currentDoc || url == null || url.length > 2_048) return false
        if (!canAcceptNavigation() || !PhoneMirrorPolicy.supportedLink(url)) return false
        return queueIntent(url, doc, "")
    }

    @JavascriptInterface
    @Synchronized
    fun intentRequest(url: String?, providedToken: String?, doc: Int, request: String?): Boolean {
        if (providedToken != token || doc == 0 || doc != currentDoc || url == null || url.length > 2_048) return false
        if (request.isNullOrBlank() || request.length > 64 || !canAcceptNavigation() || !PhoneMirrorPolicy.supportedLink(url)) return false
        return queueIntent(url, doc, request)
    }

    private fun queueIntent(url: String, doc: Int, request: String): Boolean {
        dispatchLink {
            val accepted = synchronized(this) {
                if (doc != currentDoc || !canAcceptNavigation()) false else {
                    onIntent(url)
                    true
                }
            }
            if (request.isNotEmpty()) onLinkResult(doc, request, accepted)
        }
        return true
    }
}

/** At most [max] events inside a sliding window. */
internal class RateGuard(private val max: Int, private val windowMs: Long) {
    private val times = ArrayDeque<Long>()

    /** Records an event; false once more than [max] fall inside the window. */
    fun record(nowMs: Long): Boolean {
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) times.removeFirst()
        if (times.size >= max) return false
        times.addLast(nowMs)
        return true
    }

    fun nextAllowedAt(nowMs: Long): Long =
        if (times.size < max) nowMs else maxOf(nowMs, times.first() + windowMs + 1L)

    fun reset() = times.clear()
}

/** Ignores the same transport command repeated inside a short window (touch + click, double delivery). */
internal class TransportGuard(private val windowMs: Long = 500L) {
    private var lastAction = ""
    private var lastAt = Long.MIN_VALUE / 2

    fun accept(action: String, nowMs: Long): Boolean {
        if (action == lastAction && nowMs - lastAt in 0 until windowMs) return false
        lastAction = action
        lastAt = nowMs
        return true
    }

    fun reset() { lastAction = ""; lastAt = Long.MIN_VALUE / 2 }
}

/**
 * Navigation on the mirror page counts as the user's choice only when it matches what they just
 * tapped, and only once. A link click names its destination; a bare tap is a short fallback.
 */
internal class NavAuthorizer {
    private var intentAt = Long.MIN_VALUE / 2
    private var intentId: String? = null
    private var hasIntent = false

    fun onTap(nowMs: Long) { reset() }

    fun onIntent(destinationId: String?, nowMs: Long) { hasIntent = true; intentId = destinationId; intentAt = nowMs }

    /** [destinationId] is the video ID, or null for a non-video page (Home, Search). Consumed on success. */
    fun authorize(destinationId: String?, nowMs: Long): Boolean {
        val ok = matches(destinationId, nowMs)
        if (ok) reset()
        return ok
    }

    fun matches(destinationId: String?, nowMs: Long): Boolean =
        hasIntent && destinationId != null && nowMs - intentAt in 0..INTENT_MS && intentId == destinationId

    fun reset() { hasIntent = false; intentId = null; intentAt = Long.MIN_VALUE / 2 }

    companion object {
        const val INTENT_MS = 3_000L
    }
}

