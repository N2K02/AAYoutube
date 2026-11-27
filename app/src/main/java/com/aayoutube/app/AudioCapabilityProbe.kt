package com.aayoutube.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

internal data class AudioDecoderInfo(val name: String, val mime: String, val hardware: Boolean)

/** What the device can decode and which formats AudioFlinger accepts for offload (current route). */
internal data class AudioCapabilities(
    val decoders: List<AudioDecoderInfo>,
    /** Codec MIME -> offload playback supported for the route at probe time. */
    val offload: Map<String, Boolean> = emptyMap(),
) {
    fun supports(mime: String): Boolean = decoders.any { it.mime.equals(mime, ignoreCase = true) }
    fun hardware(mime: String): Boolean = decoders.any { it.mime.equals(mime, ignoreCase = true) && it.hardware }
    fun offloadSupported(mime: String): Boolean = offload[mime.lowercase()] == true
    fun decoderFor(mime: String): AudioDecoderInfo? =
        decoders.filter { it.mime.equals(mime, ignoreCase = true) }.maxByOrNull { if (it.hardware) 1 else 0 }

    /** One structured line for logs. */
    fun summary(): String = AudioCapabilityProbe.MIMES.joinToString(" ") { m ->
        val d = decoderFor(m)
        val short = m.removePrefix("audio/")
        "$short=${if (d == null) "none" else if (d.hardware) "hw" else "sw"}${if (offloadSupported(m)) "+offload" else ""}"
    }

    companion object { val NONE = AudioCapabilities(emptyList()) }
}

/** Probes AAC, Opus and the other formats once per route change; cheap enough to cache. */
internal object AudioCapabilityProbe {
    val MIMES = listOf(
        StreamSelection.AAC, StreamSelection.OPUS, StreamSelection.VORBIS, StreamSelection.MP3,
        StreamSelection.FLAC, StreamSelection.AC3, StreamSelection.EAC3,
    )

    /** Pure: builds capabilities from decoder records (tests, and the Android probe below). */
    fun fromDecoders(decoders: List<AudioDecoderInfo>, offload: Map<String, Boolean> = emptyMap()): AudioCapabilities =
        AudioCapabilities(
            decoders.filter { d -> MIMES.any { it.equals(d.mime, ignoreCase = true) } }
                .map { it.copy(mime = it.mime.lowercase()) }
                .distinctBy { it.name to it.mime },
            offload.mapKeys { it.key.lowercase() },
        )

    fun probe(context: Context): AudioCapabilities {
        val decoders = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }
                .flatMap { info -> info.supportedTypes.map { AudioDecoderInfo(info.name, it, isHardware(info)) } }
        }.getOrDefault(emptyList())
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val offload = MIMES.associateWith { offloadSupported(audio, it) }
        return fromDecoders(decoders, offload)
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
        else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")

    private fun encodingOf(mime: String): Int? = when (mime) {
        StreamSelection.AAC -> AudioFormat.ENCODING_AAC_LC
        StreamSelection.OPUS -> if (Build.VERSION.SDK_INT >= 30) AudioFormat.ENCODING_OPUS else null
        StreamSelection.MP3 -> AudioFormat.ENCODING_MP3
        StreamSelection.AC3 -> AudioFormat.ENCODING_AC3
        StreamSelection.EAC3 -> AudioFormat.ENCODING_E_AC3
        else -> null
    }

    /** Exact stream format, rather than the generic 48kHz/stereo capability summary. */
    fun supportsOffload(context: Context, stream: AudioStream): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null || Build.VERSION.SDK_INT < 29 || stream.sampleRate <= 0) return false
        val mask = when (stream.channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> return false // conservative until multichannel routes are validated
        }
        val codec = stream.codecs.lowercase().trim().trim('"')
        val encoding = if (stream.codecMime == StreamSelection.AAC) when (codec) {
            "mp4a.40.2", "aac" -> AudioFormat.ENCODING_AAC_LC
            "mp4a.40.5" -> AudioFormat.ENCODING_AAC_HE_V1
            "mp4a.40.29" -> AudioFormat.ENCODING_AAC_HE_V2
            else -> return false
        } else encodingOf(stream.codecMime) ?: return false
        return checkOffload(encoding, stream.sampleRate, mask)
    }

    private fun checkOffload(encoding: Int, sampleRate: Int, mask: Int): Boolean = runCatching {
        val format = AudioFormat.Builder().setEncoding(encoding).setSampleRate(sampleRate)
            .setChannelMask(mask).build()
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        if (Build.VERSION.SDK_INT >= 31) {
            AudioManager.getPlaybackOffloadSupport(format, attrs) != AudioManager.PLAYBACK_OFFLOAD_NOT_SUPPORTED
        } else {
            @Suppress("DEPRECATION")
            AudioManager.isOffloadedPlaybackSupported(format, attrs)
        }
    }.getOrDefault(false)

    /** AudioManager's own answer for the current route; false when unknown or below API 29. */
    private fun offloadSupported(audio: AudioManager?, mime: String): Boolean {
        if (audio == null || Build.VERSION.SDK_INT < 29) return false
        val encoding = encodingOf(mime) ?: return false
        return runCatching {
            val format = AudioFormat.Builder().setEncoding(encoding).setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            if (Build.VERSION.SDK_INT >= 31) {
                AudioManager.getPlaybackOffloadSupport(format, attrs) != AudioManager.PLAYBACK_OFFLOAD_NOT_SUPPORTED
            } else {
                @Suppress("DEPRECATION")
                AudioManager.isOffloadedPlaybackSupported(format, attrs)
            }
        }.getOrDefault(false)
    }
}
