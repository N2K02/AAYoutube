package com.aayoutube.app

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class LyricLine(val atMs: Long, val text: String)

internal object LyricsParser {
    private val stamp = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]""")
    fun parse(input: String): List<LyricLine> = input.lineSequence().flatMap { line ->
        val text = line.replace(stamp, "").trim()
        if (text.isEmpty()) emptySequence() else stamp.findAll(line).map { m ->
            val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            LyricLine(m.groupValues[1].toLong() * 60000L + m.groupValues[2].toLong() * 1000L + fraction, text)
        }
    }.sortedBy { it.atMs }.toList()
    fun current(lines: List<LyricLine>, position: Long): Int =
        lines.indexOfLast { it.atMs <= position }
}

internal object LyricsPrefs {
    private const val FILE = "lyrics"
    fun enabled(context: Context) = context.getSharedPreferences(FILE, 0).getBoolean("enabled", true)
    fun show(context: Context) = context.getSharedPreferences(FILE, 0).getBoolean("show", true)
    fun set(context: Context, key: String, value: Boolean) =
        context.getSharedPreferences(FILE, 0).edit().putBoolean(key, value).apply()
}

internal object LyricsLookup {
    suspend fun fetch(snap: Snap): Pair<List<LyricLine>, String?> = withContext(Dispatchers.IO) {
        if (snap.title.isBlank() || snap.live || snap.ad) return@withContext emptyList<LyricLine>() to null
        try {
            val query = URLEncoder.encode((snap.title + " " + snap.artist).trim(), "UTF-8")
            val conn = (URL("https://lrclib.net/api/search?q=$query").openConnection() as HttpURLConnection)
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.setRequestProperty("User-Agent", "AAYoutube/lyrics (https://github.com/N2K02/AAYoutube)")
            try {
                if (conn.responseCode !in 200..299) return@withContext emptyList<LyricLine>() to null
                val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                val normalized = snap.title.lowercase().trim()
                for (i in 0 until arr.length().coerceAtMost(20)) {
                    val item = arr.getJSONObject(i)
                    val name = item.optString("trackName").lowercase()
                    if (name != normalized && !normalized.contains(name) && !name.contains(normalized)) continue
                    val synced = item.optString("syncedLyrics")
                    if (synced.isNotBlank() && synced != "null") {
                        val lines = LyricsParser.parse(synced)
                        if (lines.isNotEmpty()) return@withContext lines to null
                    }
                    val plain = item.optString("plainLyrics")
                    if (plain.isNotBlank() && plain != "null") return@withContext emptyList<LyricLine>() to plain
                }
                emptyList<LyricLine>() to null
            } finally { conn.disconnect() }
        } catch (_: Exception) { emptyList<LyricLine>() to null }
    }
}

@Composable
internal fun LyricsSettingsPage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(LyricsPrefs.enabled(context)) }
    var shown by remember { mutableStateOf(LyricsPrefs.show(context)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Lyrics", style = MaterialTheme.typography.headlineSmall)
        ListItem(headlineContent = { Text("Enable lyrics") },
            supportingContent = { Text("Search for lyrics when a video plays.") },
            trailingContent = { Switch(enabled, onCheckedChange = { enabled = it; LyricsPrefs.set(context, "enabled", it) }) })
        ListItem(headlineContent = { Text("Show lyrics overlay") },
            supportingContent = { Text("Show lyrics over the video. You can also toggle them from the player.") },
            trailingContent = { Switch(shown, onCheckedChange = { shown = it; LyricsPrefs.set(context, "show", it) }) })
        Text("Provider: LRCLIB. Timestamped lyrics are synchronized to playback; plain lyrics are shown without highlighting. Other providers require separate compatible integrations.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun LyricsOverlay(snap: Snap, visible: Boolean, onToggle: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(LyricsPrefs.enabled(context)) }
    var lines by remember { mutableStateOf(emptyList<LyricLine>()) }
    var plain by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(snap.id, snap.title, snap.artist, enabled, visible) {
        lines = emptyList(); plain = null; loaded = false
        if (enabled && visible && snap.id.isNotBlank()) {
            val result = LyricsLookup.fetch(snap)
            lines = result.first; plain = result.second; loaded = true
        }
    }
    LaunchedEffect(snap.id, snap.posMs, snap.playing, snap.rate) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            now = snap.posMs + if (snap.playing) ((SystemClock.elapsedRealtime() - start) * snap.rate).toLong() else 0L
            delay(200)
        }
    }
    if (!visible || !enabled || snap.id.isBlank()) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 72.dp)
            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(18.dp))
            .clickable { onToggle() }.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Lyrics  ×", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        val index = LyricsParser.current(lines, now)
        when {
            lines.isNotEmpty() -> {
                for (i in (index - 1).coerceAtLeast(0)..(index + 2).coerceAtMost(lines.lastIndex)) {
                    Text(lines[i].text, color = if (i == index) Color.White else Color.LightGray,
                        style = if (i == index) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 5.dp))
                }
            }
            plain != null -> Text(plain!!.take(1500), color = Color.White, textAlign = TextAlign.Center)
            !loaded -> Text("Finding lyrics…", color = Color.White)
            else -> Text("Lyrics not found", color = Color.White)
        }
    }
}
