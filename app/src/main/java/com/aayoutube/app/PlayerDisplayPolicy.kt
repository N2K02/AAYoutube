package com.aayoutube.app

/** Recreate Chromium only when both real display IDs are known and differ. */
internal object PlayerDisplayPolicy {
    fun shouldRecreate(created: Int?, target: Int?): Boolean =
        created != null && target != null && created != target

    /**
     * Android Auto renders a projected CarActivity into a Presentation window
     * that the host may replace (new Surface, activity recreate) on the SAME
     * virtual display. A live Chromium renderer moved into that new window
     * keeps decoding audio but shows black. Like AABrowser, which creates one
     * WebView per CarActivity window, a car bind gets a fresh renderer when
     * the display or the car window it was rendering into has changed.
     * Returns the reason, or null to keep the current renderer.
     */
    fun carRecreateReason(
        createdDisplay: Int?, targetDisplay: Int?,
        boundWindow: Int?, targetWindow: Int?,
    ): String? = when {
        shouldRecreate(createdDisplay, targetDisplay) -> "display"
        boundWindow != null && targetWindow != null && boundWindow != targetWindow -> "window"
        else -> null
    }
}

/**
 * Android Auto can run two LegacyProjectionActivity instances at once while it
 * recreates the car activity (old one not yet stopped, new one already shown).
 * Only the most recently claimed host may own the single player WebView, and an
 * old host's teardown must never undo the new host's ownership.
 */
internal class CarHostRegistry {
    private val hosts = LinkedHashMap<String, () -> Unit>()

    val active: String? get() = hosts.keys.lastOrNull()
    val size: Int get() = hosts.size

    /** Claim (or re-claim) ownership; the latest claim is the active host. */
    fun claim(token: String, rebind: () -> Unit) {
        hosts.remove(token)
        hosts[token] = rebind
    }

    /** Release a host. If it was active, returns the next host's rebind action. */
    fun release(token: String): (() -> Unit)? {
        val wasActive = active == token
        if (hosts.remove(token) == null) return null
        return if (wasActive) hosts.values.lastOrNull() else null
    }

    fun isActive(token: String): Boolean = active == token

    fun clear() = hosts.clear()
}
