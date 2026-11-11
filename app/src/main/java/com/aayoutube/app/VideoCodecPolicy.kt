package com.aayoutube.app

import android.media.MediaCodecList
import android.os.Build

/**
 * Which video format to ask YouTube for. All three are hardware-decoded on modern
 * phones; AV1 needs the least data for the same picture (less radio time), H.264 is
 * the safest everywhere.
 */
internal object VideoCodecPolicy {
    val options = listOf("av1", "vp9", "h264")

    fun label(value: String): String = when (value) {
        "av1" -> "AV1"
        "vp9" -> "VP9"
        else -> "H.264"
    }

    fun normalize(value: String?): String = value?.takeIf { it in options } ?: "h264"

    /** Pixel with a hardware AV1 decoder: AV1. Every other phone: H.264. */
    fun default(manufacturer: String, model: String, hardwareAv1: Boolean): String {
        val pixel = manufacturer.equals("Google", ignoreCase = true) && model.contains("Pixel", ignoreCase = true)
        return if (pixel && hardwareAv1) "av1" else "h264"
    }

    fun deviceDefault(): String = default(Build.MANUFACTURER ?: "", Build.MODEL ?: "", hasHardwareDecoder("video/av01"))

    fun hasHardwareDecoder(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                (Build.VERSION.SDK_INT < 29 || (info.isHardwareAccelerated && !info.isSoftwareOnly))
        }
    }.getOrDefault(false)
}
