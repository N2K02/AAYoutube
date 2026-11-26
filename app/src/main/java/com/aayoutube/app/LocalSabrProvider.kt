package com.aayoutube.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import kotlin.coroutines.resume

/** Local session resolver, enabled only by an explicit setting for authorized playback. */
internal class LocalSabrProvider(
    private val fetch: suspend (String) -> String? = { id ->
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(3000L) {
                suspendCancellableCoroutine<String?> { c ->
                    WebHost.requestLocalAudio(id) { value -> if (c.isActive) c.resume(value) }
                }
            }
        }
    },
    private val now: () -> Long = System::currentTimeMillis,
) : StreamProvider {
    override val id = "local-sabr"
    override val authorized = true // user opt-in; does not prove rights to every video

    override suspend fun resolve(videoId: String): StreamResult {
        val body = fetch(videoId) ?: return StreamResult.Unavailable(UnavailableReason.NO_AUDIO, "local-session-unavailable")
        return parse(videoId, body, now())
    }

    companion object {
        private fun cdn(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
            it.scheme == "https" && it.host?.endsWith(".googlevideo.com") == true &&
                it.userInfo == null && it.fragment == null && (it.port == -1 || it.port == 443)
        }
        private fun expiry(url: URI): Long {
            val seconds = url.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("expire=") }
                ?.substringAfter('=')?.toLongOrNull() ?: return 0L
            return seconds.takeIf { it in 1..9_000_000_000L }?.times(1000L) ?: -1L
        }
        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
        private fun range(f: JSONObject, name: String): String? {
            val r = f.optJSONObject(name) ?: return null
            val a = r.optString("start").toLongOrNull() ?: return null
            val b = r.optString("end").toLongOrNull() ?: return null
            return if (a >= 0 && b >= a && b - a < 2_000_000L) "$a-$b" else null
        }
        fun parse(id: String, body: String, nowMs: Long): StreamResult {
            if (body.length > 131072) return StreamResult.Unavailable(UnavailableReason.ERROR, "local-descriptor-limit")
            return try {
                val j = JSONObject(body)
                if (j.optString("videoId") != id) return StreamResult.Unavailable(UnavailableReason.ERROR, "local-id-mismatch")
                val duration = j.optLong("durationMs")
                if (duration !in 1..86_400_000L) return StreamResult.Unavailable(UnavailableReason.NO_AUDIO)
                val endpoint = cdn(j.optString("serverAbrStreamingUrl"))
                val uc = j.optString("ustreamerConfig")
                val configOk = uc.length in 1..16384 && uc.matches(Regex("[A-Za-z0-9_+/=-]+"))
                val a = j.optJSONArray("audio") ?: return StreamResult.Unavailable(UnavailableReason.NO_AUDIO)
                val candidates = mutableListOf<AudioStream>()
                for (i in 0 until minOf(a.length(), 16)) {
                    val f = a.optJSONObject(i) ?: continue
                    val rawMime = f.optString("mimeType")
                    val mime = rawMime.substringBefore(';').trim()
                    val codec = Regex("""codecs\s*=\s*"([^"]+)"""").find(rawMime)?.groupValues?.get(1) ?: continue
                    if (mime !in setOf("audio/mp4", "audio/webm") || !codec.matches(Regex("[A-Za-z0-9.]+"))) continue
                    val rate = f.optInt("audioSampleRate"); val channels = f.optInt("audioChannels"); val bitrate = f.optInt("bitrate")
                    val itag = f.optInt("itag"); val xtags = f.optString("xtags")
                    if (rate !in 8000..192000 || channels !in 1..2 || bitrate !in 1..2000000 ||
                        itag !in 1..99999 || xtags.length > 256) continue
                    val direct = cdn(f.optString("url"))
                    if (direct != null) {
                        val expires = expiry(direct)
                        if (expires == 0L || expires > nowMs + 30_000L)
                            candidates += AudioStream(direct.toASCIIString(), mime, codec, bitrate, rate, channels, expires)
                        continue
                    }
                    if (endpoint == null || !configOk) continue
                    val expires = expiry(endpoint)
                    if (expires != 0L && expires <= nowMs + 30_000L) continue
                    val init = range(f, "initRange") ?: continue
                    val index = range(f, "indexRange") ?: continue
                    val length = f.optString("contentLength").toLongOrNull()?.takeIf { it > 0L } ?: continue
                    val format = JSONObject().put("itag", itag).put("mimeType", rawMime)
                        .put("xtags", xtags).put("lastModified", f.optString("lastModified"))
                        .put("contentLength", length.toString()).put("bitrate", bitrate)
                        .put("audioChannels", channels).put("approxDurationMs", duration.toString())
                    val payload = JSONObject().put("adaptiveFormats", org.json.JSONArray().put(format))
                        .put("serverAbrStreamingUrl", endpoint.toASCIIString()).put("ustreamerConfig", uc)
                    val encoded = Base64.getEncoder().encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
                    val key = URLEncoder.encode("$itag:$xtags", "UTF-8").replace("+", "%20")
                    val mpd = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" minBufferTime="PT1S" mediaPresentationDuration="PT${duration / 1000}.${(duration % 1000).toString().padStart(3, '0')}S"><SupplementalProperty schemeIdUri="urn:youtube:sabr" value="$encoded"/><Period><AdaptationSet contentType="audio" mimeType="$mime"><Representation id="$itag" codecs="$codec" bandwidth="$bitrate" audioSamplingRate="$rate"><BaseURL>sabr://audio?key=${escape(key)}</BaseURL><SegmentBase indexRange="$index" indexRangeExact="true"><Initialization range="$init"/></SegmentBase></Representation></AdaptationSet></Period></MPD>"""
                    if (SabrManifestPolicy.valid(mpd, mime, codec))
                        candidates += AudioStream("https://example.invalid/aay.sabr.mpd", mime, codec, bitrate, rate, channels, expires, sabrManifest = mpd)
                }
                if (candidates.isEmpty()) StreamResult.Unavailable(UnavailableReason.NO_AUDIO, "local-session-incomplete")
                else StreamResult.Resolved(ResolvedStreams(id, candidates, durationMs = duration))
            } catch (_: Exception) { StreamResult.Unavailable(UnavailableReason.ERROR, "local-descriptor-invalid") }
        }
    }
}
