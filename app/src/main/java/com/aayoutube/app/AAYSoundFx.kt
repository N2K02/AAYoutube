package com.aayoutube.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * mRadio-style settings saved locally in the WebView process.
 * Primary engine: mRadio's DSP chain as a Web Audio graph on YouTube's own
 * media element (assets/aay_eq.js), so only AAYouTube audio is processed.
 * Android's native effects are a fallback only when that graph cannot attach.
 * Defaults to OFF.
 */
internal object AAYSoundFx {
    private var prefs: SharedPreferences? = null
    private val _settings = MutableStateFlow(SoundEqSettings())
    val settings: StateFlow<SoundEqSettings> = _settings
    private val _webAudio = MutableStateFlow(WebAudioEqState.OFF)
    val webAudio: StateFlow<WebAudioEqState> = _webAudio
    private val _latencyMs = MutableStateFlow<Int?>(null)
    val latencyMs: StateFlow<Int?> = _latencyMs

    fun webAudioJson(): String = WebAudioEqPolicy.params(_settings.value).toJson()

    /** Page script report. Native effects run only if the in-player DSP is unsupported. */
    fun onWebAudioStatus(raw: String) {
        val state = WebAudioEqPolicy.status(raw)
        _webAudio.value = state
        _latencyMs.value = WebAudioEqPolicy.latencyMs(raw)
        GlobalAudioEffects.nativeFallback(WebAudioEqPolicy.nativeFallbackAllowed(state))
    }


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
        GlobalAudioEffects.refresh(_settings.value)
    }

    fun update(change: (SoundEqSettings) -> SoundEqSettings) {
        val before = _settings.value
        val safe = SoundEqPolicy.safe(change(before))
        if (safe == before) return
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
        if (WebAudioEqPolicy.releaseNeedsReload(before, safe, _webAudio.value)) {
            // Hand YouTube audio back to Chromium's normal output path.
            _webAudio.value = WebAudioEqState.OFF
            _latencyMs.value = null
            WebHost.reload()
        } else {
            WebHost.applyEq(WebAudioEqPolicy.params(safe).toJson())
        }
    }

    fun setBand(index: Int, db: Float) =
        update { SoundEqPolicy.setBand(it, index, db) }

    fun applyPreset(name: String) =
        update { SoundEqPolicy.preset(it, name) }

    fun reset() = update { SoundEqPolicy.reset(it) }

    /** Called from existing WebView playback reports, never from media service. */
    fun onPlaybackChanged(playing: Boolean) {
        GlobalAudioEffects.playback(playing)
    }

    /** Disables output-mix effects when the WebView is stopped or quitting. */
    fun shutdown() {
        _webAudio.value = WebAudioEqState.OFF
        GlobalAudioEffects.nativeFallback(false)
        GlobalAudioEffects.release()
    }
}
