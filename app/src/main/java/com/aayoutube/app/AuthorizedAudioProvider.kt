package com.aayoutube.app

import org.json.JSONObject
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/** An opt-in HTTPS source of separately licensed audio, indexed by video ID. */
internal class AuthorizedAudioProvider private constructor(
    private val endpoint: String,
    private val fetch: (String) -> String?,
    private val now: () -> Long,
) : StreamProvider {
    override val id = "https-resolver"
    override val authorized = true // operator configuration, not proof of rights

    override suspend fun resolve(videoId: String): StreamResult {
        if (!Regex("[A-Za-z0-9_-]{1,128}").matches(videoId))
            return StreamResult.Unavailable(UnavailableReason.ERROR, "invalid-id")
        val body = fetch(endpoint + "?videoId=" + videoId)
            ?: return StreamResult.Unavailable(UnavailableReason.NO_AUDIO)
        return parseManifest(videoId, body, now())
    }

    companion object {
        private const val MAX_BYTES = 131072
        private val audioTypes = setOf("audio/mp4", "audio/webm", "audio/mpeg", "audio/aac", "audio/opus", "audio/ogg", "audio/flac", "audio/ac3", "audio/eac3")
        private fun httpsUri(value: String): URI? = runCatching { URI(value) }.getOrNull()
            ?.takeIf { it.scheme.equals("https", true) && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null }

        fun normalizeEndpoint(value: String): String? =
            value.trim().takeIf { it.length in 1..2048 }?.let(::httpsUri)
                ?.takeIf { it.rawQuery == null }?.toASCIIString()

        fun create(value: String, fetch: (String) -> String? = ::fetchManifest,
                   now: () -> Long = System::currentTimeMillis): AuthorizedAudioProvider? =
            normalizeEndpoint(value)?.let { AuthorizedAudioProvider(it, fetch, now) }

        private fun fetchManifest(url: String): String? {
            val conn = URI(url).toURL().openConnection() as HttpsURLConnection
            try {
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code == 404 || code == 410) return null
                check(code == 200)
                return conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        check(out.size() + n <= MAX_BYTES)
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray().toString(Charsets.UTF_8)
                }
            } finally { conn.disconnect() }
        }

        fun parseManifest(videoId: String, body: String, nowMs: Long): StreamResult {
            if (body.toByteArray(Charsets.UTF_8).size > MAX_BYTES)
                return StreamResult.Unavailable(UnavailableReason.ERROR, "manifest-too-large")
            return try {
                val json = JSONObject(body)
                if (json.optString("videoId") != videoId) return StreamResult.Unavailable(UnavailableReason.ERROR, "video-id-mismatch")
                if (!json.optBoolean("authorized", false)) return StreamResult.Unavailable(UnavailableReason.NOT_AUTHORIZED)
                if (json.optBoolean("live", false)) return StreamResult.Unavailable(UnavailableReason.LIVE)
                val array = json.optJSONArray("audio") ?: return StreamResult.Unavailable(UnavailableReason.NO_AUDIO)
                val streams = mutableListOf<AudioStream>()
                for (i in 0 until minOf(array.length(), 32)) {
                    val a = array.optJSONObject(i) ?: continue
                    val sabr = a.optString("sabrMpd").takeIf { it.isNotBlank() }
                    val url = if (sabr == null) a.optString("url") else "https://example.invalid/aay.sabr.mpd"
                    val mime = a.optString("mimeType").substringBefore(';').trim().lowercase()
                    val codecs = a.optString("codecs")
                    val bitrate = a.optInt("bitrate")
                    val rate = a.optInt("sampleRate")
                    val channels = a.optInt("channels", 2)
                    val expires = a.optLong("expiresAtMs")
                    if (url.length > 8192 || httpsUri(url) == null || mime !in audioTypes || bitrate !in 1..10000000 ||
                        rate !in 0..384000 || channels !in 1..8 || codecs.length > 128 ||
                        expires < 0L || (expires != 0L && expires <= nowMs + 30000L)) continue
                    if (sabr != null && !SabrManifestPolicy.valid(sabr, mime, codecs)) continue
                    streams += AudioStream(url, mime, codecs, bitrate, rate, channels, expires, sabrManifest = sabr)
                }
                if (streams.isEmpty()) StreamResult.Unavailable(UnavailableReason.NO_AUDIO)
                else StreamResult.Resolved(ResolvedStreams(videoId, streams, durationMs = json.optLong("durationMs").coerceAtLeast(0L)))
            } catch (_: Exception) { StreamResult.Unavailable(UnavailableReason.ERROR, "invalid-manifest") }
        }
    }
}
