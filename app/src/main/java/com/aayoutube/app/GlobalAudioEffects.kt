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
    // Off until the page reports the in-player Web Audio DSP cannot attach,
    // so the curve is never applied twice.
    private var nativeAllowed = false
    private var attempted = false
    private var attachedSession: Int? = null
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
        main.postDelayed(applyTask, 100L)
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

    fun nativeFallback(allowed: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { nativeFallback(allowed) }
            return
        }
        if (nativeAllowed == allowed) return
        nativeAllowed = allowed
        main.removeCallbacks(applyTask)
        main.post(applyTask)
    }

    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { release() }
            return
        }
        main.removeCallbacks(applyTask)
        playing = false
        // Preserve the opt-in preference across WebView recreation. Only
        // retire the native effect handles and playback state.
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
        attachedSession = null
    }

    private fun <T : android.media.audiofx.AudioEffect> create(
        factory: () -> T,
    ): T? = runCatching {
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
        if (!nativeAllowed) {
            releaseEffects()
            _status.value = AudioFxStatus(
                message = "mRadio DSP runs inside the YouTube player; native effects not used",
            )
            return
        }
        if (!playing) {
            // Release handles on pause, not just disable them. Otherwise
            // session-0 effects can keep ownership across car/phone handoff.
            releaseEffects()
            _status.value = AudioFxStatus(
                message = "Waiting for YouTube audio playback",
            )
            return
        }
        // Native fallback only: Chromium does not expose its audio session,
        // so effects attach to the legacy global output mix (session 0).
        val selectedSession = 0
        if (attachedSession != selectedSession) releaseEffects()
        if (!attempted) {
            attempted = true
            attachedSession = selectedSession
            equalizer = create { Equalizer(0, selectedSession) }
            bassBoost = create { BassBoost(0, selectedSession) }
            virtualizer = create { Virtualizer(0, selectedSession) }
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

        val bassOkay = if (!eqOkay || bassBoost == null) false else runCatching {
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
        val virtualOkay = if (!eqOkay || virtualizer == null) false else runCatching {
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
                "Legacy output-mix EQ initialized. It may affect other apps. " +
                    "Actual Chromium/Android Auto routing is not verified."
            else
                "Legacy output-mix equalizer unavailable or not controllable " +
                    "on this device. WebView offers no supported PCM attachment.",
        )
        if (!eqOkay && eq != null) runCatching { eq.enabled = false }
        if (!bassOkay) runCatching { bassBoost?.enabled = false }
        if (!virtualOkay) runCatching { virtualizer?.enabled = false }
    }
}
