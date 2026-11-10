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
    val sessionScoped: Boolean = false,
    val message: String = "Equalizer is off",
)

internal object GlobalAudioEffects {
    private val main = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow(AudioFxStatus())
    val status: StateFlow<AudioFxStatus> = _status

    private var settings = SoundEqSettings()
    private var playing = false
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

    /** Called only when the own-UID system dump yields a unique active session. */
    fun onVerifiedSessionChanged(sessionId: Int?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onVerifiedSessionChanged(sessionId) }
            return
        }
        if (!settings.enabled || !playing || !AudioSessionDiscovery.hasPermission()) return
        if (attachedSession != sessionId) {
            releaseEffects()
            main.removeCallbacks(applyTask)
            main.post(applyTask)
        }
    }

    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { release() }
            return
        }
        main.removeCallbacks(applyTask)
        AudioSessionDiscovery.stop()
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
            AudioSessionDiscovery.stop()
            releaseEffects()
            _status.value = AudioFxStatus(message = "Equalizer is off")
            return
        }
        if (!playing) {
            AudioSessionDiscovery.stop()
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
        // With ADB-granted DUMP we target ONLY a uniquely verified active
        // AAYouTube session. If discovery is ambiguous or inaccessible,
        // fail closed instead of silently falling back to system-wide EQ.
        // Without the optional grant retain the user's legacy mode.
        val targeting = AudioSessionDiscovery.hasPermission()
        val selectedSession = if (targeting) {
            AudioSessionDiscovery.start()
            AudioSessionDiscovery.status.value.sessionId
        } else {
            AudioSessionDiscovery.stop()
            0
        }
        if (selectedSession == null) {
            releaseEffects()
            _status.value = AudioFxStatus(
                message = AudioSessionDiscovery.status.value.message,
            )
            return
        }
        if (attachedSession != selectedSession) releaseEffects()
        if (!attempted) {
            attempted = true
            attachedSession = selectedSession
            equalizer = create { Equalizer(0, selectedSession) }
            bassBoost = create { BassBoost(0, selectedSession) }
            virtualizer = create { Virtualizer(0, selectedSession) }
            AppLog.add("AUDIO",
                "Experimental audio effects mode=" +
                    if (targeting) "verified app session" else "legacy global mix")
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
            sessionScoped = targeting,
            message = if (eqOkay && targeting)
                "Audio effects attached to verified AAYouTube session. " +
                    "Audible effect and Bluetooth/Android Auto route still need testing."
            else if (eqOkay)
                "Legacy output-mix EQ initialized. It may affect other apps. " +
                    "Actual Chromium/Android Auto routing is not verified."
            else if (targeting)
                "AAYouTube session identified, but native audio effects were " +
                    "not controllable on this route."
            else
                "Legacy output-mix equalizer unavailable or not controllable " +
                    "on this device. WebView offers no supported PCM attachment.",
        )
        if (!eqOkay && eq != null) runCatching { eq.enabled = false }
        if (!bassOkay) runCatching { bassBoost?.enabled = false }
        if (!virtualOkay) runCatching { virtualizer?.enabled = false }
    }
}
