package com.aayoutube.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * mRadio-style settings saved locally in the WebView process.
 * Unlike mRadio's ExoPlayer, Chromium does not supply us PCM buffers or an
 * exposed per-track audio session. The Android output-mix adapter is explicitly
 * optional and defaults to OFF.
 */
internal object AAYSoundFx {
    private var prefs: SharedPreferences? = null
    private val _settings = MutableStateFlow(SoundEqSettings())
    val settings: StateFlow<SoundEqSettings> = _settings

    // Coalesce rapid slider events: log the final curve after inactivity.
    private val diagnosticsHandler = Handler(Looper.getMainLooper())
    private val reportSettings = Runnable {
        AppLog.add("EQ", "settings " +
            EqualizerDiagnosticPolicy.settingSummary(_settings.value))
    }
    private var lastPlayback: Boolean? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("aay_sound_fx", Context.MODE_PRIVATE)
        _settings.value = runCatching {
            val saved = prefs?.getString("v1", null) ?: return@runCatching SoundEqSettings()
            val data = JSONObject(saved)
            val array = data.optJSONArray("bands") ?: JSONArray()
            SoundEqPolicy.safe(SoundEqSettings(
                enabled = data.optBoolean("enabled", false),
                bands = List(5) { array.optDouble(it, 0.0).toFloat() },
                bass = data.optDouble("bass", 0.0).toFloat(),
                mode = runCatching {
                    SoundSpaceMode.valueOf(data.optString("mode", "OFF"))
                }.getOrDefault(SoundSpaceMode.OFF),
                intensity = data.optDouble("intensity", 0.6).toFloat(),
                preset = data.optString("preset", "Custom"),
            ))
        }.getOrDefault(SoundEqSettings())
        AudioSessionDiscovery.init(context)
        AppLog.add("EQ", "initialized process=web " +
            EqualizerDiagnosticPolicy.settingSummary(_settings.value))
        GlobalAudioEffects.refresh(_settings.value)
    }

    fun update(change: (SoundEqSettings) -> SoundEqSettings) {
        val safe = SoundEqPolicy.safe(change(_settings.value))
        if (safe == _settings.value) return
        _settings.value = safe
        prefs?.edit()?.putString("v1", JSONObject()
            .put("enabled", safe.enabled)
            .put("bands", JSONArray(safe.bands.map { it.toDouble() }))
            .put("bass", safe.bass.toDouble())
            .put("mode", safe.mode.name)
            .put("intensity", safe.intensity.toDouble())
            .put("preset", safe.preset)
            .toString())?.apply()
        GlobalAudioEffects.refresh(safe)
        diagnosticsHandler.removeCallbacks(reportSettings)
        diagnosticsHandler.postDelayed(reportSettings, 300L)
    }

    fun setBand(index: Int, db: Float) =
        update { SoundEqPolicy.setBand(it, index, db) }

    fun applyPreset(name: String) =
        update { SoundEqPolicy.preset(it, name) }

    fun reset() = update { SoundEqPolicy.reset(it) }

    /** Called from existing WebView playback reports, never from media service. */
    fun onPlaybackChanged(playing: Boolean) {
        if (lastPlayback != playing) {
            lastPlayback = playing
            AppLog.add("EQ", "chromium playback=$playing")
        }
        GlobalAudioEffects.playback(playing)
    }

    /** Disables output-mix effects when the WebView is stopped or quitting. */
    fun shutdown() {
        diagnosticsHandler.removeCallbacks(reportSettings)
        lastPlayback = null
        AppLog.add("EQ", "web player released; native effects stopped")
        GlobalAudioEffects.release()
    }
}
