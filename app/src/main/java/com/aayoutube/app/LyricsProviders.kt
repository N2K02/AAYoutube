package com.aayoutube.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONObject
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Normalize video titles before querying song-lyric services, without changing the playback metadata.
 * Official-video suffixes and redundant "Artist - Track" prefixes often return unrelated versions. */
internal object LyricsQuery {
    private val videoSuffix = Regex("""\s*[\[(](?:official\s+)?(?:music\s+)?(?:video|audio|lyrics?|visualizer|4k|hd)(?:\s+(?:video|audio))?[\])]\s*""", RegexOption.IGNORE_CASE)
    fun title(title: String, artist: String): String {
        var result = title.replace(videoSuffix, " ").trim()
        val prefix = artist.trim()
        if (prefix.isNotBlank() && result.startsWith("$prefix - ", ignoreCase = true))
            result = result.substring(prefix.length + 3)
        return result.trim().ifBlank { title.trim() }
    }
    fun cleaned(snap: Snap): Snap = snap.copy(title = title(snap.title, snap.artist))
}

/** Independent public lyric endpoints. A failure never prevents the next provider being tried. */
internal enum class LyricsProvider(val title: String) {
    RMM("RMM Revival"), LRCLIB("LRCLIB"), NETEASE("NetEase"), SIMPMUSIC("SimpMusic"), BLYRICS("bLyrics"), BINIMUM("BiniLyrics"), LYRICS_OVH("Lyrics.ovh")
}

internal object LyricsProviders {
    // All integrated sources are accessible without an API key or paid subscription.
    // NetEase uses an unofficial endpoint and is opt-in by default.
    internal const val ORDER = "LRCLIB,SIMPMUSIC,BLYRICS,BINIMUM,RMM,LYRICS_OVH,NETEASE"
    fun ordered(context: Context): List<LyricsProvider> {
        val prefs = context.getSharedPreferences("lyrics", 0)
        return prefs.getString("providers", ORDER).orEmpty().split(",")
            .mapNotNull { id -> LyricsProvider.entries.firstOrNull { it.name == id } }
            .distinct().filter { prefs.getBoolean("provider_" + it.name, it != LyricsProvider.NETEASE) }
    }
    fun reorder(context: Context, source: LyricsProvider, direction: Int) {
        val prefs = context.getSharedPreferences("lyrics", 0)
        val order = prefs.getString("providers", ORDER).orEmpty().split(",")
            .mapNotNull { id -> LyricsProvider.entries.firstOrNull { it.name == id } }.toMutableList()
        val from = order.indexOf(source)
        if (from < 0 || from + direction !in order.indices) return
        java.util.Collections.swap(order, from, from + direction)
        prefs.edit().putString("providers", order.joinToString(",") { it.name }).apply()
        LyricsPrefs.providersChanged()
    }
    /** Prefer a timed match from any enabled source; plain text is the fallback only. */
    suspend fun fetch(context: Context, snap: Snap): Pair<List<LyricLine>, String?> =
        withContext(Dispatchers.IO) {
            val lyricSnap = LyricsQuery.cleaned(snap)
            preferTimed(ordered(context)) { source ->
                try {
                    when (source) {
                        LyricsProvider.LRCLIB -> LyricsLookup.fetch(lyricSnap)
                        LyricsProvider.RMM -> rmm(lyricSnap)
                        LyricsProvider.NETEASE -> netease(lyricSnap)
                        LyricsProvider.SIMPMUSIC -> simpMusic(lyricSnap)
                        LyricsProvider.BLYRICS -> blyrics(lyricSnap)
                        LyricsProvider.BINIMUM -> binimum(lyricSnap)
                        LyricsProvider.LYRICS_OVH -> lyricsOvh(lyricSnap)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
            }
        }

    /** Pure/testable decision order; a plain result never masks later synced lyrics. */
    internal suspend fun preferTimed(
        sources: List<LyricsProvider>,
        lookup: suspend (LyricsProvider) -> Pair<List<LyricLine>, String?>?,
    ): Pair<List<LyricLine>, String?> {
        var plainFallback: String? = null
        for (source in sources) {
            val result = lookup(source) ?: continue
            if (result.first.isNotEmpty()) return result.first to null
            if (plainFallback.isNullOrBlank() && !result.second.isNullOrBlank())
                plainFallback = result.second
        }
        return emptyList<LyricLine>() to plainFallback
    }

    /** Cancellation disconnects blocking network I/O, instead of waiting for all
     * provider timeouts after navigating to another video or pressing Quit. */
    private suspend fun request(url: String): String? = suspendCancellableCoroutine { task ->
        val conn = try { URL(url).openConnection() as HttpURLConnection } catch (error: Exception) {
            task.resumeWithException(error)
            return@suspendCancellableCoroutine
        }
        conn.connectTimeout = 4_500
        conn.readTimeout = 4_500
        conn.setRequestProperty("User-Agent", "AAYoutube/lyrics")
        task.invokeOnCancellation { conn.disconnect() }
        try {
            val result = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4_096)
                    val content = StringBuilder()
                    while (content.length < 512_000) {
                        val n = reader.read(buffer, 0, minOf(buffer.size, 512_000 - content.length))
                        if (n < 0) break
                        content.append(buffer, 0, n)
                    }
                    content.toString()
                }
            } else null
            if (task.isActive) task.resume(result)
        } catch (error: Exception) {
            if (task.isActive) task.resumeWithException(error)
        } finally {
            conn.disconnect()
        }
    }
    private fun ttml(xml: String): Pair<List<LyricLine>, String?>? {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val lines = mutableListOf<LyricLine>()
        var time: Long? = null
        var content = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name == "p") {
                    val begin = (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == "begin" }
                    time = begin?.let { parseTime(parser.getAttributeValue(it)) }
                    content = StringBuilder()
                }
                XmlPullParser.TEXT -> if (time != null) content.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "p") {
                    if (time != null && content.isNotBlank()) lines.add(LyricLine(time!!, content.toString().trim()))
                    time = null
                }
            }
            event = parser.next()
        }
        return if (lines.isEmpty()) null else lines.sortedBy { it.atMs } to null
    }
    private fun parseTime(value: String): Long? {
        if (value.endsWith("ms")) return value.dropLast(2).toDoubleOrNull()?.toLong()
        if (value.endsWith("s")) return value.dropLast(1).toDoubleOrNull()?.times(1000)?.toLong()
        val parts = value.split(":")
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return ((hours * 3600 + minutes * 60 + seconds) * 1000).toLong()
    }
    private suspend fun blyrics(snap: Snap): Pair<List<LyricLine>, String?>? {
        if (snap.artist.isBlank()) return null
        val title = URLEncoder.encode(snap.title, "UTF-8")
        val artist = URLEncoder.encode(snap.artist, "UTF-8")
        val url = "https://lyrics-api.boidu.dev/getLyrics?s=$title&a=$artist" +
            if (snap.durMs > 0) "&d=" + snap.durMs / 1000 else ""
        val xml = JSONObject(request(url) ?: return null).optString("ttml")
        return if (xml.isBlank()) null else ttml(xml)
    }
    private suspend fun binimum(snap: Snap): Pair<List<LyricLine>, String?>? {
        if (snap.artist.isBlank()) return null
        val title = URLEncoder.encode(snap.title, "UTF-8")
        val artist = URLEncoder.encode(snap.artist, "UTF-8")
        val url = "https://lyrics-api.binimum.org/?track=$title&artist=$artist" +
            if (snap.durMs > 0) "&duration=" + snap.durMs / 1000 else ""
        val results = JSONObject(request(url) ?: return null).optJSONArray("results") ?: return null
        val lyricsUrl = results.optJSONObject(0)?.optString("lyricsUrl").orEmpty()
        if (!lyricsUrl.startsWith("https://")) return null
        return ttml(request(lyricsUrl) ?: return null)
    }
    private suspend fun rmm(snap: Snap): Pair<List<LyricLine>, String?>? {
        if (snap.title.isBlank()) return null
        val query = URLEncoder.encode((snap.title + " " + snap.artist).trim(), "UTF-8")
        val results = JSONObject(request("https://itunes.apple.com/search?term=$query&entity=song&limit=5") ?: return null)
            .optJSONArray("results") ?: return null
        for (i in 0 until results.length()) {
            val track = results.optJSONObject(i) ?: continue
            val title = track.optString("trackName")
            if (!title.equals(snap.title, true)) continue
            val id = track.optLong("trackId")
            if (id <= 0) continue
            val root = JSONObject(request("https://lyrics.rmmreviv.al/lyrics?id=$id") ?: continue)
            val lrc = root.optString("lrc").ifBlank { root.optString("syncedLyrics") }
            val lines = LyricsParser.parse(lrc)
            if (lines.isNotEmpty()) return lines to null
        }
        return null
    }
    private suspend fun simpMusic(snap: Snap): Pair<List<LyricLine>, String?>? {
        if (!snap.id.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
        val root = JSONObject(request("https://api-lyrics.simpmusic.org/v1/" + snap.id) ?: return null)
        if (!root.optBoolean("success")) return null
        val entries = root.optJSONArray("data") ?: return null
        val sorted = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
            .sortedByDescending { it.optInt("vote", 0) }
        var plainFallback: String? = null
        for (entry in sorted) {
            val synced = entry.optString("syncedLyrics").ifBlank { entry.optString("richSyncLyrics") }
            val lines = LyricsParser.parse(synced)
            if (lines.isNotEmpty()) return lines to null
            val plain = entry.optString("plainLyric")
            if (plain.isNotBlank() && plainFallback == null) plainFallback = plain
        }
        return if (plainFallback == null) null else emptyList<LyricLine>() to plainFallback
    }
    private suspend fun netease(snap: Snap): Pair<List<LyricLine>, String?>? {
        val q = URLEncoder.encode((snap.title + " " + snap.artist).trim(), "UTF-8")
        val songs = JSONObject(request("https://music.163.com/api/search/get/web?type=1&limit=5&s=$q") ?: return null)
            .optJSONObject("result")?.optJSONArray("songs") ?: return null
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val name = song.optString("name")
            if (name.isBlank() || (!name.contains(snap.title, true) && !snap.title.contains(name, true))) continue
            val id = song.optLong("id")
            if (id <= 0) continue
            val lyric = JSONObject(request("https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1") ?: continue)
                .optJSONObject("lrc")?.optString("lyric").orEmpty()
            val lines = LyricsParser.parse(lyric)
            if (lines.isNotEmpty()) return lines to null
        }
        return null
    }
    private suspend fun lyricsOvh(snap: Snap): Pair<List<LyricLine>, String?>? {
        if (snap.artist.isBlank()) return null
        val artist = URLEncoder.encode(snap.artist, "UTF-8")
        val title = URLEncoder.encode(snap.title, "UTF-8")
        val lyric = JSONObject(request("https://api.lyrics.ovh/v1/$artist/$title") ?: return null)
            .optString("lyrics")
        return if (lyric.isBlank()) null else emptyList<LyricLine>() to lyric
    }
}
