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

    const val HIGH_FPS = 60.0

    /** Achievable rate (vendor-measured) wins over the advertised one when published. */
    fun meetsRate(advertised: Boolean, achievableUpper: Double?, fps: Double): Boolean =
        if (achievableUpper != null) achievableUpper >= fps * 0.95 else advertised

    /** Hardware decoder can do [w]x[h] at [fps] (achievable rate when the vendor publishes it). */
    fun supports(info: MediaCodecInfo, mime: String, w: Int, h: Int, fps: Double = MIN_FPS): Boolean = runCatching {
        val vc = info.getCapabilitiesForType(mime).videoCapabilities ?: return false
        if (!vc.isSizeSupported(w, h)) return false
        val achievable = runCatching { vc.getAchievableFrameRatesFor(w, h) }.getOrNull()
        meetsRate(vc.areSizeAndRateSupported(w, h, fps), achievable?.upper, fps)
    }.getOrDefault(false)

    /** 10-bit profiles YouTube uses for HDR: VP9 Profile 2, AV1 Main10. */
    fun tenBitProfiles(codec: String): Set<Int> = when (codec) {
        "vp9" -> setOf(MediaCodecInfo.CodecProfileLevel.VP9Profile2,
            MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR, MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus)
        "av1" -> setOf(MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10, MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus)
        else -> emptySet()
    }

    private fun decodesTenBit(info: MediaCodecInfo, mime: String, codec: String): Boolean = runCatching {
        val wanted = tenBitProfiles(codec)
        info.getCapabilitiesForType(mime).profileLevels.any { it.profile in wanted }
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

    /**
     * codec -> max hardware height at 30 fps (0: no hardware decoder), plus
     * "<codec>_60" (max height at 60 fps) and "<codec>_hdr" (1 if a 10-bit profile
     * decodes in hardware). Computed once per process.
     */
    fun current(): Map<String, Int> = cached ?: buildMap {
        MIMES.forEach { (codec, mime) ->
            val decoders = hardwareDecoders(mime)
            put(codec, decoders.maxOfOrNull { i -> maxHeight { w, h -> supports(i, mime, w, h) } } ?: 0)
            put(codec + "_60", decoders.maxOfOrNull { i -> maxHeight { w, h -> supports(i, mime, w, h, HIGH_FPS) } } ?: 0)
            if (codec != "h264") put(codec + "_hdr", if (decoders.any { decodesTenBit(it, mime, codec) }) 1 else 0)
        }
    }.also {
        cached = it
        AppLog.add("VIDEO", "Hardware decode limits " + it.entries.joinToString { (k, v) -> "$k=$v" })
    }

    fun json(caps: Map<String, Int> = current()): JSONObject =
        JSONObject().apply { caps.forEach { (k, v) -> put(k, v) } }
}
