package com.aayoutube.app

/** Choose a playable media selection even when the :web renderer has been
 * destroyed by Stop, memory pressure or the Android Auto host. */
internal data class ResumeTarget(val id: String, val sec: Long)

internal object MediaResumeTarget {
    fun select(current: Snap, saved: Library.Last?): ResumeTarget? {
        if (current.id.isNotBlank())
            return ResumeTarget(current.id, current.posMs.coerceAtLeast(0L) / 1_000L)
        return saved?.id?.takeIf { it.isNotBlank() }?.let {
            ResumeTarget(it, saved.posSec.coerceAtLeast(0L))
        }
    }
}
