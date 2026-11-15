package com.aayoutube.app

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import org.json.JSONObject

/**
 * Which resolution each video codec can decode IN HARDWARE on this device, from
 * Android's codec list (authoritative, unlike a browser guess). The page uses it
 * to hide only the codec/resolution pairs the device cannot play, so 8K stays
 * available through AV1 when the AV1 decoder handles it but VP9 does not.
 */
internal object DecoderCapabilities {
    /** YouTube's tiers, largest first (height in px). */
    val TIERS = intArrayOf(4320, 2880, 2160, 1440, 1080, 720)
    const val MIN_FPS = 30.0

    private val MIMES = linkedMapOf(
        "vp9" to "video/x-vnd.on2.vp9",
        "av1" to "video/av01",
        "h264" to "video/avc",
    )

    /** Largest tier for which [supports] (width, height) is true, else 0. 16:9 and its portrait form are tried. */
    fun maxHeight(supports: (Int, Int) -> Boolean): Int {
        for (h in TIERS) {
            val w = h * 16 / 9
            if (supports(w, h) || supports(h, w)) return h
        }
        return 0
    }

    /** Hardware decoder can do [w]x[h] at >= 30 fps (achievable rate when the vendor publishes it). */
    fun supports(info: MediaCodecInfo, mime: String, w: Int, h: Int): Boolean = runCatching {
        val vc = info.getCapabilitiesForType(mime).videoCapabilities ?: return false
        if (!vc.isSizeSupported(w, h)) return false
        val achievable = runCatching { vc.getAchievableFrameRatesFor(w, h) }.getOrNull()
        if (achievable != null) achievable.upper >= MIN_FPS
        else vc.areSizeAndRateSupported(w, h, MIN_FPS)
    }.getOrDefault(false)

    private fun hardwareDecoders(mime: String): List<MediaCodecInfo> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } &&
                !info.name.endsWith(".secure") &&
                (if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
                 else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android."))
        }
    }.getOrDefault(emptyList())

    @Volatile private var cached: Map<String, Int>? = null

    /** codec -> max hardware height (0: no hardware decoder). Computed once per process. */
    fun current(): Map<String, Int> = cached ?: MIMES.mapValues { (_, mime) ->
        hardwareDecoders(mime).maxOfOrNull { info -> maxHeight { w, h -> supports(info, mime, w, h) } } ?: 0
    }.also {
        cached = it
        AppLog.add("VIDEO", "Hardware decode limits " + it.entries.joinToString { (k, v) -> "$k=${if (v > 0) "${v}p" else "none"}" })
    }

    fun json(caps: Map<String, Int> = current()): JSONObject =
        JSONObject().apply { caps.forEach { (k, v) -> put(k, v) } }
}
