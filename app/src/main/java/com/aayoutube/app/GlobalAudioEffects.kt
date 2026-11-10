@file:Suppress("DEPRECATION")

package com.aayoutube.app

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Conservative fallback for Chromium-controlled audio: *legacy output mix*
 * audio effects, NOT mRadio's PCM SoundProcessor.
 *
 * Audio session 0 is deprecated and OEM/Android Auto/Bluetooth dependent. It
 * may affect other apps sharing the system mix. We create effects ONLY when the
 * user opts in AND YouTube reports playing. No privileged API, playback capture
 * or second player; unsupported hardware is explicitly reported in the UI.
 */
internal data class AudioFxStatus(
    val running: Boolean = false,
    val equalizerAvailable: Boolean = false,
    val bassAvailable: Boolean = false,
    val virtualizerAvailable: Boolean = false,
    val message: String = "Equalizer is off",
)

internal object GlobalAudioEffects {
    private val main = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow(AudioFxStatus())
    val status: StateFlow<AudioFxStatus> = _status

    private var settings = SoundEqSettings()
    private var playing = false
    private var attempted = false
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private val applyTask = Runnable { applyNow() }

    fun refresh(next: SoundEqSettings) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { refresh(next) }
            return
        }
        settings = SoundEqPolicy.safe(next)
        main.removeCallbacks(applyTask)
        main.postDelayed(applyTask, 45L)
    }

    fun playback(isPlaying: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { playback(isPlaying) }
            return
        }
        if (playing == isPlaying) return
        playing = isPlaying
        main.removeCallbacks(applyTask)
        main.postDelayed(applyTask, if (isPlaying) 50L else 0L)
    }

    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { release() }
            return
        }
        main.removeCallbacks(applyTask)
        playing = false
        settings = settings.copy(enabled = false)
        releaseEffects()
        _status.value = AudioFxStatus(message = "Equalizer stopped")
    }

    private fun releaseEffects() {
        runCatching { equalizer?.enabled = false }
        runCatching { bassBoost?.enabled = false }
        runCatching { virtualizer?.enabled = false }
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        attempted = false
    }

    private fun <T : android.media.audiofx.AudioEffect> create(factory: () -> T): T? =
        runCatching {
            val effect = factory()
            if (effect.hasControl()) effect else {
                effect.release()
                null
            }
        }.getOrNull()

    private fun applyNow() {
        if (!settings.enabled) {
            releaseEffects()
            _status.value = AudioFxStatus(message = "Equalizer is off")
            return
        }
        if (!playing) {
            // Never leave deprecated global-mix effects active after playback
            // stops: they could change audio from unrelated apps.
            runCatching { equalizer?.enabled = false }
            runCatching { bassBoost?.enabled = false }
            runCatching { virtualizer?.enabled = false }
            _status.value = AudioFxStatus(
                equalizerAvailable = equalizer != null,
                bassAvailable = bassBoost != null,
                virtualizerAvailable = virtualizer != null,
                message = "Waiting for YouTube audio playback",
            )
            return
        }
        if (!attempted) {
            attempted = true
            equalizer = create { Equalizer(0, 0) }
            bassBoost = create { BassBoost(0, 0) }
            virtualizer = create { Virtualizer(0, 0) }
            AppLog.add("AUDIO", "Experimental global-mix audio effects availability: " +
                "eq=${equalizer != null}, bass=${bassBoost != null}, " +
                "virtualizer=${virtualizer != null} (route unverified)")
        }

        val eq = equalizer
        val eqOkay = if (eq == null) false else runCatching {
            val range = eq.bandLevelRange
            if (range.size < 2) error("Invalid system EQ range")
            for (band in 0 until eq.numberOfBands.toInt()) {
                val hz = eq.getCenterFreq(band.toShort()) / 1000
                val db = SoundEqPolicy.gainAtHz(hz, settings.bands)
                eq.setBandLevel(band.toShort(),
                    SoundEqPolicy.millibels(db, range[0].toInt(), range[1].toInt()))
            }
            eq.enabled = true
            eq.enabled && eq.hasControl()
        }.getOrDefault(false)

        val bassOkay = if (bassBoost == null) false else runCatching {
            bassBoost?.let {
                it.enabled = false
                if (settings.bass > 0f) {
                    it.setStrength(SoundEqPolicy.strength(settings.bass))
                    it.enabled = true
                }
            }
            bassBoost?.hasControl() == true
        }.getOrDefault(false)

        // Native Virtualizer is only an approximation of mRadio's Wide and
        // Headphone 3D modes. Hall, Cinema and 8D would require PCM DSP and
        // must NOT silently pretend to work on Chromium.
        val spatial = settings.mode in setOf(
            SoundSpaceMode.WIDE, SoundSpaceMode.HEADPHONE_3D)
        val virtualOkay = if (virtualizer == null) false else runCatching {
            virtualizer?.let {
                it.enabled = false
                if (spatial) {
                    val strength = if (settings.mode == SoundSpaceMode.WIDE)
                        settings.intensity * 0.65f else settings.intensity
                    it.setStrength(SoundEqPolicy.strength(strength))
                    it.enabled = true
                }
            }
            virtualizer?.hasControl() == true
        }.getOrDefault(false)

        _status.value = AudioFxStatus(
            running = eqOkay,
            equalizerAvailable = eqOkay,
            bassAvailable = bassOkay,
            virtualizerAvailable = virtualOkay,
            message = if (eqOkay)
                "Android output-mix EQ initialized. Actual Chromium/Android Auto " +
                    "routing and audible effect are not verified."
            else
                "Android output-mix equalizer unavailable or not controllable " +
                    "on this device. WebView offers no supported PCM attachment.",
        )
        if (!eqOkay && eq != null) runCatching { eq.enabled = false }
        if (!bassOkay) runCatching { bassBoost?.enabled = false }
        if (!virtualOkay) runCatching { virtualizer?.enabled = false }
    }
}
