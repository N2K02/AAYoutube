package com.aayoutube.app

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * mRadio-inspired 5-band controls and preset curves. The mRadio PCM
 * SoundProcessor cannot attach to a Chromium WebView's private audio path.
 * This policy only maps the same five controls onto Android AudioEffect
 * bands, when the platform permits an output-mix effect.
 */
internal enum class SoundSpaceMode(
    val label: String,
    val blurb: String,
    val systemApproximation: Boolean,
) {
    OFF("Off", "Plain stereo, as YouTube sends it.", true),
    WIDE("Wide", "Approximate widening using Android's virtualizer, if available.", true),
    HEADPHONE_3D("Headphone 3D",
        "Approximate headphone virtualization, when supported by Android.", true),
    HALL("Concert hall", "mRadio's PCM reverb is unavailable for WebView playback.", false),
    CINEMA("Cinema", "mRadio's PCM spatial DSP is unavailable for WebView playback.", false),
    ORBIT_8D("8D orbit", "mRadio's moving PCM audio effect is unavailable for WebView playback.", false),
}

internal data class SoundEqSettings(
    /** Explicit opt-in: the deprecated output-mix effect can affect other apps. */
    val enabled: Boolean = false,
    val bands: List<Float> = List(5) { 0f },
    val bass: Float = 0f,
    val mode: SoundSpaceMode = SoundSpaceMode.OFF,
    val intensity: Float = 0.6f,
    val preset: String = "Flat",
)

internal object SoundEqPolicy {
    val bandHz = intArrayOf(60, 230, 910, 3600, 14000)
    val presets: List<Pair<String, List<Float>>> = listOf(
        "Flat" to listOf(0f, 0f, 0f, 0f, 0f),
        "Bass boost" to listOf(6f, 4f, 0f, 0f, 0f),
        "Vocal" to listOf(-2f, 0f, 3f, 3f, 0f),
        "Pop" to listOf(-1f, 2f, 4f, 2f, -1f),
        "Rock" to listOf(4f, 2f, -1f, 3f, 4f),
        "Dance" to listOf(6f, 3f, 0f, 2f, 3f),
        "Hip-hop" to listOf(5f, 3f, 0f, 1f, 3f),
        "Jazz" to listOf(3f, 2f, -2f, 2f, 4f),
        "Classical" to listOf(4f, 3f, -2f, 3f, 4f),
        "Treble boost" to listOf(0f, 0f, 0f, 3f, 6f),
    )

    fun safe(value: SoundEqSettings): SoundEqSettings = value.copy(
        bands = List(bandHz.size) {
            value.bands.getOrElse(it) { 0f }.takeIf(Float::isFinite)
                ?.coerceIn(-12f, 12f) ?: 0f
        },
        bass = value.bass.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
        intensity = value.intensity.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.6f,
        mode = value.mode.takeIf { it.systemApproximation } ?: SoundSpaceMode.OFF,
        preset = value.preset.take(32),
    )

    fun setBand(value: SoundEqSettings, index: Int, db: Float): SoundEqSettings {
        if (index !in bandHz.indices) return safe(value)
        val current = safe(value)
        return current.copy(
            bands = current.bands.toMutableList().also {
                it[index] = db.takeIf(Float::isFinite)?.coerceIn(-12f, 12f) ?: 0f
            },
            preset = "Custom",
        )
    }

    fun preset(value: SoundEqSettings, name: String): SoundEqSettings {
        val curve = presets.firstOrNull { it.first == name } ?: return safe(value)
        return safe(value).copy(bands = curve.second, preset = name)
    }

    fun reset(value: SoundEqSettings): SoundEqSettings =
        SoundEqSettings(enabled = value.enabled)

    /** Interpolate 5 user-visible mRadio bands across variable OEM EQ centers. */
    fun gainAtHz(frequencyHz: Int, bands: List<Float>): Float {
        val clean = safe(SoundEqSettings(bands = bands)).bands
        if (frequencyHz <= bandHz.first()) return clean.first()
        if (frequencyHz >= bandHz.last()) return clean.last()
        val upper = bandHz.indexOfFirst { it >= frequencyHz }
        val lower = upper - 1
        val ratio = ((ln(frequencyHz.toDouble()) - ln(bandHz[lower].toDouble())) /
            (ln(bandHz[upper].toDouble()) - ln(bandHz[lower].toDouble()))).toFloat()
        return clean[lower] + (clean[upper] - clean[lower]) * ratio
    }

    fun millibels(db: Float, minimum: Int, maximum: Int): Short =
        (db.coerceIn(-12f, 12f) * 100f).roundToInt()
            .coerceIn(minimum.coerceAtLeast(Short.MIN_VALUE.toInt()),
                maximum.coerceAtMost(Short.MAX_VALUE.toInt())).toShort()

    fun strength(value: Float): Short =
        ((value.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f) * 1000f)
            .roundToInt().toShort()
}
