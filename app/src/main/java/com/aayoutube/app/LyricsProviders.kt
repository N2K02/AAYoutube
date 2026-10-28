package com.aayoutube.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Independent public lyric endpoints. A failure never prevents the next provider being tried. */
internal enum class LyricsProvider(val title: String) {
    LRCLIB("LRCLIB"), NETEASE("NetEase"), LYRICS_OVH("Lyrics.ovh")
}

internal object LyricsProviders {
    private const val ORDER = "LRCLIB,NETEASE,LYRICS_OVH"
    fun ordered(context: Context): List<LyricsProvider> {
        val prefs = context.getSharedPreferences("lyrics", 0)
        return prefs.getString("providers", ORDER).orEmpty().split(",")
            .mapNotNull { id -> LyricsProvider.entries.firstOrNull { it.name == id } }
            .distinct().filter { prefs.getBoolean("provider_" + it.name, true) }
    }
    fun reorder(context: Context, source: LyricsProvider, direction: Int) {
        val prefs = context.getSharedPreferences("lyrics", 0)
        val order = prefs.getString("providers", ORDER).orEmpty().split(",")
            .mapNotNull { id -> LyricsProvider.entries.firstOrNull { it.name == id } }.toMutableList()
        val from = order.indexOf(source)
        if (from < 0 || from + direction !in order.indices) return
        java.util.Collections.swap(order, from, from + direction)
        prefs.edit().putString("providers", order.joinToString(",") { it.name }).apply()
    }
    suspend fun fetch(context: Context, snap: Snap): Pair<List<LyricLine>, String?> =
        withContext(Dispatchers.IO) {
            for (source in ordered(context)) {
                val result = try {
                    when (source) {
                        LyricsProvider.LRCLIB -> LyricsLookup.fetch(snap)
                        LyricsProvider.NETEASE -> netease(snap)
                        LyricsProvider.LYRICS_OVH -> lyricsOvh(snap)
                    }
                } catch (_: Exception) { null }
                if (result != null && (result.first.isNotEmpty() || !result.second.isNullOrBlank()))
                    return@withContext result
            }
            emptyList<LyricLine>() to null
        }

    private fun request(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4500
        conn.readTimeout = 4500
        conn.setRequestProperty("User-Agent", "AAYoutube/lyrics")
        return try {
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally { conn.disconnect() }
    }
    private fun netease(snap: Snap): Pair<List<LyricLine>, String?>? {
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
    private fun lyricsOvh(snap: Snap): Pair<List<LyricLine>, String?>? {
        if (snap.artist.isBlank()) return null
        val artist = URLEncoder.encode(snap.artist, "UTF-8")
        val title = URLEncoder.encode(snap.title, "UTF-8")
        val lyric = JSONObject(request("https://api.lyrics.ovh/v1/$artist/$title") ?: return null)
            .optString("lyrics")
        return if (lyric.isBlank()) null else emptyList<LyricLine>() to lyric
    }
}
