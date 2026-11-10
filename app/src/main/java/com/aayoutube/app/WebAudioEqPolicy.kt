package com.aayoutube.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * mRadio's SoundEngine.apply() translated into the numbers the in-page Web Audio
 * graph (assets/aay_eq.js) needs. Chromium then runs the same RBJ peaking bands,
 * 120 Hz low shelf, preamp headroom, mid/side widening, delayed low-passed
 * cross-feed, reverb send, 8D orbit and soft limiter on YouTube's own media
 * element, inside AAYouTube only. Pure Kotlin so every value is unit tested.
 */
internal data class WebAudioEqParams(
    val enabled: Boolean,
    val bands: List<Float>,
    val q: Double,
    val bassDb: Double,
    val bassHz: Double,
    val preamp: Float,
    val sideGain: Float,
    val crossGain: Float,
    val crossNorm: Float,
    val crossDelaySec: Float,
    val crossLowPassHz: Double,
    val reverbWet: Float,
    val reverbFeedback: Float,
    val reverbDamp: Float,
    val reverbPreSec: Float,
    val orbitDepth: Float,
    val orbitPeriodSec: Double,
    val orbitKeep: Float,
    val limiter: Boolean,
) {
    /** Hand-built JSON so unit tests do not depend on Android's org.json stubs. */
    fun toJson(): String = buildString {
        append("{\"enabled\":").append(enabled)
        append(",\"bandHz\":[").append(SoundEqPolicy.bandHz.joinToString(",")).append(']')
        append(",\"bands\":[").append(bands.joinToString(",") { num(it.toDouble()) }).append(']')
        append(",\"q\":").append(num(q))
        append(",\"bassDb\":").append(num(bassDb))
        append(",\"bassHz\":").append(num(bassHz))
        append(",\"preamp\":").append(num(preamp.toDouble()))
        append(",\"sideGain\":").append(num(sideGain.toDouble()))
        append(",\"crossGain\":").append(num(crossGain.toDouble()))
        append(",\"crossNorm\":").append(num(crossNorm.toDouble()))
        append(",\"crossDelay\":").append(num(crossDelaySec.toDouble()))
        append(",\"crossLp\":").append(num(crossLowPassHz))
        append(",\"wet\":").append(num(reverbWet.toDouble()))
        append(",\"feedback\":").append(num(reverbFeedback.toDouble()))
        append(",\"damp\":").append(num(reverbDamp.toDouble()))
        append(",\"pre\":").append(num(reverbPreSec.toDouble()))
        append(",\"orbit\":").append(num(orbitDepth.toDouble()))
        append(",\"orbitPeriod\":").append(num(orbitPeriodSec))
        append(",\"orbitKeep\":").append(num(orbitKeep.toDouble()))
        append(",\"limiter\":").append(limiter)
        append('}')
    }

    private fun num(v: Double): String =
        if (v.isFinite()) "%.5f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
            .ifEmpty { "0" }.let { if (it == "-0") "0" else it }
        else "0"
}

internal object WebAudioEqPolicy {
    const val Q = 0.9
    const val BASS_HZ = 120.0

    fun params(input: SoundEqSettings): WebAudioEqParams {
        val s = SoundEqPolicy.safe(input)
        val on = s.enabled
        val bands = if (on) s.bands.map { if (abs(it) >= 0.1f) it else 0f }
            else List(SoundEqPolicy.bandHz.size) { 0f }
        val bassDb = if (on) (s.bass * 9.0).takeIf { it >= 0.1 } ?: 0.0 else 0.0
        val maxBoost = max(bands.maxOrNull() ?: 0f, bassDb.toFloat()).coerceAtLeast(0f)
        // mRadio: make room for the boost so the loudest parts don't clip.
        val preamp = 10f.pow(-maxBoost * 0.5f / 20f)

        val mode = if (on) s.mode else SoundSpaceMode.OFF
        val i = s.intensity.coerceIn(0f, 1f)
        var side = 1f
        var cross = 0f
        var crossDelay = 0f
        var mix = 0f
        var feedback = 0.84f
        var damp = 0.4f
        var pre = 0f
        var orbit = 0f
        when (mode) {
            SoundSpaceMode.OFF -> {}
            SoundSpaceMode.WIDE -> side = 1f + 1.2f * i
            SoundSpaceMode.HEADPHONE_3D -> {
                side = 1f + 0.5f * i
                cross = 0.25f + 0.35f * i
                crossDelay = 0.00027f
                mix = 0.05f + 0.08f * i
                feedback = 0.80f; damp = 0.5f; pre = 0.010f
            }
            SoundSpaceMode.HALL -> {
                side = 1f + 0.2f * i
                mix = 0.12f + 0.28f * i
                feedback = 0.88f; damp = 0.3f; pre = 0.018f
            }
            SoundSpaceMode.CINEMA -> {
                side = 1f + 0.8f * i
                mix = 0.08f + 0.14f * i
                feedback = 0.84f; damp = 0.4f; pre = 0.012f
            }
            SoundSpaceMode.ORBIT_8D -> {
                orbit = i
                mix = 0.05f + 0.10f * i
                feedback = 0.80f; damp = 0.5f; pre = 0.008f
            }
        }
        val anyEq = bands.any { it != 0f } || bassDb > 0.0
        return WebAudioEqParams(
            enabled = on,
            bands = bands,
            q = Q,
            bassDb = bassDb,
            bassHz = BASS_HZ,
            preamp = if (on) preamp else 1f,
            sideGain = side,
            crossGain = cross,
            crossNorm = 1f / (1f + cross * 0.6f),
            crossDelaySec = crossDelay,
            crossLowPassHz = 700.0,
            reverbWet = mix,
            reverbFeedback = feedback,
            reverbDamp = damp,
            reverbPreSec = pre,
            orbitDepth = orbit,
            orbitPeriodSec = 9.0,
            orbitKeep = 0.5f * (1f - orbit * 0.5f),
            // mRadio bypasses the limiter only when nothing changes the sound.
            limiter = on && (anyEq || mode != SoundSpaceMode.OFF),
        )
    }

    /** Status reported by the page script; anything unknown is treated as not attached. */
    fun status(raw: String?): WebAudioEqState = when (raw) {
        "active" -> WebAudioEqState.ACTIVE
        "waiting" -> WebAudioEqState.WAITING
        "unsupported" -> WebAudioEqState.UNSUPPORTED
        "off" -> WebAudioEqState.OFF
        else -> WebAudioEqState.UNSUPPORTED
    }

    /** Native output-mix/session effects only run when the in-player DSP could not attach. */
    fun nativeFallbackAllowed(state: WebAudioEqState): Boolean =
        state == WebAudioEqState.UNSUPPORTED
}

internal enum class WebAudioEqState { OFF, WAITING, ACTIVE, UNSUPPORTED }
