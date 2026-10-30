package com.aayoutube.app

import kotlin.math.abs

/**
 * Coalesces frequent WebView position reports before publishing them to MediaCompat.
 *
 * Android Auto can discard metadata changes when the session is updated too often.
 * Position still advances on the receiver using the published playback speed.
 * This class has no Android dependencies so the timing policy is unit-testable.
 */
internal class MediaUpdateGate {
    private data class Metadata(
        val id: String,
        val title: String,
        val artist: String,
        val artwork: String,
        val durationMs: Long,
    )

    private data class Playback(
        val state: Int,
        val actions: Long,
        val positionMs: Long,
        val speed: Float,
        val atMs: Long,
    )

    private var metadata: Metadata? = null
    private var playback: Playback? = null

    fun metadataChanged(id: String, title: String, artist: String, artwork: String, durationMs: Long): Boolean {
        val next = Metadata(id, title, artist, artwork, durationMs)
        if (metadata == next) return false
        metadata = next
        return true
    }

    fun playbackChanged(state: Int, actions: Long, positionMs: Long, speed: Float, atMs: Long): Boolean {
        val previous = playback
        val expected = previous?.let {
            it.positionMs + ((atMs - it.atMs).coerceAtLeast(0L) * it.speed).toLong()
        }
        val changed = previous == null ||
            previous.state != state ||
            previous.actions != actions ||
            previous.speed != speed ||
            (speed == 0f && positionMs != previous.positionMs) ||
            (expected != null && abs(positionMs - expected) > 1_500L) ||
            (speed > 0f && atMs - previous.atMs >= 10_000L)

        if (changed) playback = Playback(state, actions, positionMs, speed, atMs)
        return changed
    }
}
