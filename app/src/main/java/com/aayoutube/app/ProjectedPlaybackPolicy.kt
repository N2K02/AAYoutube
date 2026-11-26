package com.aayoutube.app

/**
 * Pure (Android-free) decisions for the shared player while Android Auto
 * projects it. Kept separate from WebHost/Compose so they are unit-testable.
 */

/** Drops the same selection arriving again through another callback path
 * (page click bridge, shouldOverrideUrlLoading, doUpdateVisitedHistory). */
internal class SelectionDeduper(private val windowMs: Long = 1_500L) {
    private var lastKey = ""
    private var lastAt = 0L
    private var seen = false

    fun accept(key: String, nowMs: Long): Boolean {
        if (key.isBlank()) return true
        if (seen && key == lastKey && nowMs - lastAt in 0 until windowMs) return false
        seen = true
        lastKey = key
        lastAt = nowMs
        return true
    }

    fun reset() { seen = false; lastKey = ""; lastAt = 0L }

    companion object {
        /** Identity of a selection: video + playlist, or the route for other pages. */
        fun key(path: String, videoId: String?, list: String?): String = when {
            !videoId.isNullOrBlank() -> "v=$videoId|list=${list.orEmpty()}"
            path.startsWith("/shorts/") || path.startsWith("/live/") -> path
            else -> ""
        }
    }
}

/** Whether a newly selected watch URL is the video that is already loaded. */
internal object SelectedVideoReuse {
    fun reuse(currentId: String, onWatchPage: Boolean, selectedId: String?,
              selectedList: String?, currentList: String?, explicitPaused: Boolean): Boolean =
        onWatchPage && !explicitPaused && currentId.isNotBlank() && selectedId == currentId &&
            (selectedList.isNullOrBlank() || selectedList == currentList)
}

/** Navigating a browse page to the address it already shows is a wasted load. */
internal object BrowseNavigation {
    fun sameDestination(current: String?, target: String): Boolean {
        if (current.isNullOrBlank()) return false
        fun norm(u: String) = u.substringBefore('#').trimEnd('/').lowercase()
        return norm(current) == norm(target)
    }
}

/**
 * Car renderer reuse. A live renderer moved between two car windows paints
 * black, so a window change normally gets a fresh renderer. A renderer that was
 * retained for audio after its window closed is not bound to any window: reuse
 * it (no reload, buffers kept) and verify the picture afterwards instead.
 */
internal object CarRendererReusePolicy {
    const val VERIFY_DELAYS_MS = 1_800L
    const val MAX_VERIFY_SAMPLES = 3
    /** Consecutive black samples (while the clock advances) before recovery. */
    const val BLACK_SAMPLES_TO_RECOVER = 2
    const val DARK_PERCENT_BLACK = 98
    const val RECOVERY_COOLDOWN_MS = 60_000L

    fun reason(createdDisplay: Int?, targetDisplay: Int?, boundWindow: Int?, targetWindow: Int?,
               retainedDetached: Boolean, forced: String? = null): String? = when {
        forced != null -> forced
        retainedDetached ->
            if (PlayerDisplayPolicy.shouldRecreate(createdDisplay, targetDisplay)) "display" else null
        else -> PlayerDisplayPolicy.carRecreateReason(createdDisplay, targetDisplay, boundWindow, targetWindow)
    }

    /** A sample counts as black only when the video is genuinely presenting. */
    fun blackSample(darkPercent: Int?, playing: Boolean, buffering: Boolean, advancing: Boolean): Boolean =
        darkPercent != null && darkPercent >= DARK_PERCENT_BLACK && playing && !buffering && advancing

    fun shouldRecover(consecutiveBlack: Int, nowMs: Long, lastRecoveryAtMs: Long): Boolean =
        consecutiveBlack >= BLACK_SAMPLES_TO_RECOVER &&
            (lastRecoveryAtMs == 0L || nowMs - lastRecoveryAtMs >= RECOVERY_COOLDOWN_MS)
}

/** Percentage of near-black pixels in a small ARGB sample. */
internal object FrameDarkness {
    fun percent(pixels: IntArray): Int {
        if (pixels.isEmpty()) return 100
        var dark = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Rec.601 luma, integer form.
            if ((r * 299 + g * 587 + b * 114) / 1000 < 18) dark++
        }
        return dark * 100 / pixels.size
    }
}

/** Phone preview pacing and validity. */
internal object PreviewFramePolicy {
    const val CAPTURE_WIDTH_FULL = 480
    const val CAPTURE_WIDTH_COMPACT = 320
    /** A PixelCopy whose callback never arrived must not block later captures. */
    const val STUCK_REQUEST_MS = 1_000L

    fun captureWidth(compact: Boolean) = if (compact) CAPTURE_WIDTH_COMPACT else CAPTURE_WIDTH_FULL
    /** Keep the last good frame instead of flashing a black one. */
    fun accept(darkPercent: Int) = darkPercent < CarRendererReusePolicy.DARK_PERCENT_BLACK
    fun requestStuck(startedAtMs: Long, nowMs: Long) = startedAtMs != 0L && nowMs - startedAtMs >= STUCK_REQUEST_MS
}

/** Nested enum class: referencing it does not initialise the WebHost object. */
private typealias Mode = WebHost.PlayerMode

/** The phone's own player UI while the car owns (or retains) the single engine. */
internal object ProjectedPhoneNav {
    fun home(hasVideo: Boolean) = if (hasVideo) Mode.MINI else Mode.HIDDEN

    /** Back: full player shrinks; otherwise browse history, then Home. */
    sealed interface Back {
        data object Minimize : Back
        data object BrowseHistory : Back
        data object Home : Back
    }
    fun back(mode: Mode, browseCanGoBack: Boolean): Back = when {
        mode == Mode.FULL -> Back.Minimize
        browseCanGoBack -> Back.BrowseHistory
        else -> Back.Home
    }

    /** Mode to show when the shared video changes or disappears. */
    fun onVideoChanged(mode: Mode, hasVideo: Boolean): Mode = when {
        !hasVideo && mode != Mode.HIDDEN -> Mode.HIDDEN
        hasVideo && mode == Mode.HIDDEN -> Mode.MINI
        else -> mode
    }
}

/**
 * Measures Music<->Video handoffs. "Complete" only when the media clock is
 * really advancing again, never on a transient playing callback.
 */
internal class ModeTransitionMeter(private val timeoutMs: Long = 30_000L) {
    sealed interface Event {
        data class Buffering(val direction: String, val elapsedMs: Long) : Event
        data class Completed(val direction: String, val elapsedMs: Long, val interruptionMs: Long,
                             val bufferingMs: Long, val rendererRecreated: Boolean) : Event
        data class Cancelled(val direction: String, val elapsedMs: Long, val reason: String) : Event
    }

    private var active = false
    private var direction = ""
    private var videoId = ""
    private var startPos = 0L
    private var startedAt = 0L
    private var generation = 0
    private var bufferingSince = 0L
    private var bufferingTotal = 0L
    private var bufferingReported = false
    private var stalledSince = 0L

    val running: Boolean get() = active

    fun start(direction: String, videoId: String, positionMs: Long, generation: Int,
              playing: Boolean, nowMs: Long) {
        active = videoId.isNotBlank()
        this.direction = direction
        this.videoId = videoId
        startPos = positionMs
        startedAt = nowMs
        this.generation = generation
        bufferingSince = 0L
        bufferingTotal = 0L
        bufferingReported = false
        stalledSince = if (playing) 0L else nowMs
    }

    fun cancel() { active = false }

    fun onState(id: String, playing: Boolean, buffering: Boolean, userPaused: Boolean, ended: Boolean,
                positionMs: Long, generation: Int, nowMs: Long): Event? {
        if (!active) return null
        val elapsed = nowMs - startedAt
        if (id != videoId || userPaused || ended || elapsed > timeoutMs) {
            active = false
            val reason = when {
                id != videoId -> "video-changed"
                userPaused -> "user-paused"
                ended -> "ended"
                else -> "timeout"
            }
            return Event.Cancelled(direction, elapsed, reason)
        }
        if (!playing || buffering) { if (stalledSince == 0L) stalledSince = nowMs }
        if (buffering) {
            if (bufferingSince == 0L) bufferingSince = nowMs
            if (!bufferingReported) {
                bufferingReported = true
                return Event.Buffering(direction, elapsed)
            }
            return null
        }
        if (bufferingSince != 0L) {
            bufferingTotal += nowMs - bufferingSince
            bufferingSince = 0L
        }
        if (playing && positionMs > startPos) {
            active = false
            val interruption = if (stalledSince == 0L) 0L else nowMs - stalledSince
            return Event.Completed(direction, elapsed, interruption, bufferingTotal,
                generation != this.generation)
        }
        return null
    }
}

/** androidx.car.app CarConnection types: 0 not connected, 1 native, 2 projection. */
internal object CarConnectionPolicy {
    const val NOT_CONNECTED = 0
    fun disconnected(previous: Int, current: Int): Boolean =
        current == NOT_CONNECTED && previous > NOT_CONNECTED
}
