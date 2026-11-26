package com.aayoutube.app

/**
 * Authorized audio/video source abstraction for the experimental native audio engine.
 *
 * A provider resolves a video id to separate streams. Only providers whose source is licensed
 * for this use may be registered ([StreamProvider.authorized]); the app ships none, so the
 * native engine reports [UnavailableReason.NO_PROVIDER] and the WebView engine keeps playing.
 * Pure Kotlin: no Android or network types.
 */
internal data class AudioStream(
    val url: String,
    /** Container MIME, e.g. `audio/mp4` or `audio/webm`. */
    val mimeType: String,
    /** RFC 6381 codecs string, e.g. `mp4a.40.2` or `opus`. */
    val codecs: String,
    val bitrate: Int,
    val sampleRate: Int = 0,
    val channels: Int = 2,
    /** Wall-clock expiry of [url]; 0 = does not expire. */
    val expiresAtMs: Long = 0L,
    val headers: Map<String, String> = emptyMap(),
) {
    /** Codec MIME understood by MediaCodec / AudioTrack. */
    val codecMime: String get() = StreamSelection.codecMime(mimeType, codecs)
}

internal data class VideoStream(
    val url: String,
    val mimeType: String,
    val codecs: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    /** Muxed stream (audio included); separate audio makes video unnecessary for audio-only play. */
    val hasAudio: Boolean = false,
)

internal data class ResolvedStreams(
    val videoId: String,
    val audio: List<AudioStream>,
    val video: List<VideoStream> = emptyList(),
    val durationMs: Long = 0L,
    val live: Boolean = false,
) {
    /** Separate audio exists: video need not be downloaded or decoded for audio playback. */
    val separateAudio: Boolean get() = audio.isNotEmpty()
}

internal enum class UnavailableReason { NO_PROVIDER, NOT_AUTHORIZED, NO_AUDIO, UNSUPPORTED_CODEC, LIVE, EXPIRED, ERROR }

internal sealed class StreamResult {
    data class Resolved(val streams: ResolvedStreams) : StreamResult()
    data class Unavailable(val reason: UnavailableReason, val detail: String = "") : StreamResult()
}

internal interface StreamProvider {
    /** Short identifier for logs. */
    val id: String
    /** True only for sources licensed for direct playback; unauthorized providers are never used. */
    val authorized: Boolean
    /** Called off the main thread. */
    suspend fun resolve(videoId: String): StreamResult
}

/** The shipped default: no source, the WebView engine stays in charge. */
internal object NoStreamProvider : StreamProvider {
    override val id = "none"
    override val authorized = false
    override suspend fun resolve(videoId: String): StreamResult = StreamResult.Unavailable(UnavailableReason.NO_PROVIDER)
}

internal object StreamProviders {
    @Volatile private var registered: StreamProvider = NoStreamProvider

    val current: StreamProvider get() = registered

    /** Registers [provider] only if it is authorized; returns whether it was accepted. */
    fun register(provider: StreamProvider): Boolean {
        if (!provider.authorized) return false
        registered = provider
        return true
    }

    fun reset() { registered = NoStreamProvider }

    /** Resolves through the current provider, refusing anything an unauthorized provider returns. */
    suspend fun resolve(videoId: String, provider: StreamProvider = current): StreamResult {
        if (provider === NoStreamProvider) return StreamResult.Unavailable(UnavailableReason.NO_PROVIDER)
        if (!provider.authorized) return StreamResult.Unavailable(UnavailableReason.NOT_AUTHORIZED, provider.id)
        return runCatching { provider.resolve(videoId) }
            .getOrElse { StreamResult.Unavailable(UnavailableReason.ERROR, it.javaClass.simpleName) }
    }
}

/** Picks the audio stream for the device's decoders and offload support. */
internal object StreamSelection {
    const val AAC = "audio/mp4a-latm"
    const val OPUS = "audio/opus"
    const val VORBIS = "audio/vorbis"
    const val MP3 = "audio/mpeg"
    const val FLAC = "audio/flac"
    const val AC3 = "audio/ac3"
    const val EAC3 = "audio/eac3"

    fun codecMime(mimeType: String, codecs: String): String {
        val c = codecs.lowercase().trim().trim('"')
        val m = mimeType.lowercase().substringBefore(';').trim()
        return when {
            c.startsWith("opus") -> OPUS
            c.startsWith("mp4a") || c == "aac" -> AAC
            c.startsWith("vorbis") -> VORBIS
            c.startsWith("ec-3") || c.startsWith("eac3") -> EAC3
            c.startsWith("ac-3") || c.startsWith("ac3") -> AC3
            c.startsWith("flac") -> FLAC
            c.startsWith("mp3") || m == "audio/mpeg" -> MP3
            m == "audio/mp4" || m == "audio/aac" -> AAC
            m == "audio/opus" -> OPUS
            else -> m
        }
    }

    /**
     * Best playable stream: decodable at all, then offload-capable when offload is wanted,
     * then hardware decoding, then bitrate. Expired URLs are skipped. Null = nothing playable.
     */
    fun bestAudio(streams: List<AudioStream>, caps: AudioCapabilities, preferOffload: Boolean, nowMs: Long): AudioStream? =
        streams
            .filter { it.url.isNotBlank() && (it.expiresAtMs == 0L || it.expiresAtMs > nowMs) }
            .filter { caps.supports(it.codecMime) }
            .maxWithOrNull(compareBy<AudioStream>(
                { if (preferOffload && caps.offloadSupported(it.codecMime)) 1 else 0 },
                { if (caps.hardware(it.codecMime)) 1 else 0 },
                { it.bitrate },
            ))

    /** Why nothing was selected, for logs and the fallback decision. */
    fun unavailable(resolved: ResolvedStreams, caps: AudioCapabilities, nowMs: Long): UnavailableReason? = when {
        resolved.live -> UnavailableReason.LIVE
        !resolved.separateAudio -> UnavailableReason.NO_AUDIO
        resolved.audio.all { it.expiresAtMs != 0L && it.expiresAtMs <= nowMs } -> UnavailableReason.EXPIRED
        bestAudio(resolved.audio, caps, false, nowMs) == null -> UnavailableReason.UNSUPPORTED_CODEC
        else -> null
    }
}
