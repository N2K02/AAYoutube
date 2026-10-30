package com.aayoutube.app

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class LyricLine(val atMs: Long, val text: String)

/** The latest resolved lyrics belong to a specific video and title/artist combination. */
internal data class LyricsContent(
    val key: String = "",
    val lines: List<LyricLine> = emptyList(),
    val plain: String? = null,
) {
    fun foundFor(currentKey: String): Boolean =
        key == currentKey && (lines.isNotEmpty() || !plain.isNullOrBlank())
}


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

internal object LyricsCache {
    private val values = mutableMapOf<String, Pair<List<LyricLine>, String?>>()
    fun key(snap: Snap) = listOf(snap.id, snap.title.trim().lowercase(), snap.artist.trim().lowercase()).joinToString("|")
    @Synchronized fun get(key: String) = values[key]
    @Synchronized fun clear() { values.clear() }
    @Synchronized fun put(key: String, value: Pair<List<LyricLine>, String?>) {
        if (value.first.isNotEmpty() || !value.second.isNullOrBlank()) {
            if (values.size >= 40) values.remove(values.keys.first())
            values[key] = value
        }
    }
}

internal object LyricsSync {
    fun position(baseMs: Long, playing: Boolean, buffering: Boolean, rate: Float, elapsedMs: Long, offsetMs: Long): Long {
        val advanced = if (playing && !buffering) (elapsedMs.coerceAtLeast(0L) * rate.coerceAtLeast(0f)).toLong() else 0L
        return (baseMs + advanced + offsetMs).coerceAtLeast(0L)
    }
}

/** Direct video-element position, sampled only while synchronized lyrics are visible.
 * Unlike a media-session snapshot, this handles seeking and buffering within 250 ms. */
internal data class LyricsClock(
    val videoId: String = "",
    val posMs: Long = 0L,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val rate: Float = 1f,
    val sampledAtMs: Long = 0L,
) {
    fun positionFor(id: String, nowMs: Long, offsetMs: Long): Long? =
        if (id.isBlank() || videoId != id) null
        else LyricsSync.position(posMs, playing, buffering, rate, nowMs - sampledAtMs, offsetMs)
}

internal object LyricsPrefs {
    private const val FILE = "lyrics"
    private val _enabled = MutableStateFlow(true)
    private val _shown = MutableStateFlow(true)
    private val _providersVersion = MutableStateFlow(0)
    val enabledState: StateFlow<Boolean> = _enabled
    val shownState: StateFlow<Boolean> = _shown
    val providersVersion: StateFlow<Int> = _providersVersion

    /** Initialize once in the web process, before composing any screen. */
    fun init(context: Context) {
        _enabled.value = enabled(context)
        _shown.value = show(context)
    }
    fun enabled(context: Context) = context.getSharedPreferences(FILE, 0).getBoolean("enabled", true)
    fun show(context: Context) = context.getSharedPreferences(FILE, 0).getBoolean("show", true)
    fun position(context: Context, key: String, fallback: Float) = context.getSharedPreferences(FILE, 0).getFloat(key, fallback)
    fun setPosition(context: Context, key: String, value: Float) = context.getSharedPreferences(FILE, 0).edit().putFloat(key, value).apply()
    fun set(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(FILE, 0).edit().putBoolean(key, value).apply()
        when (key) {
            "enabled" -> _enabled.value = value
            "show" -> _shown.value = value
        }
    }
    fun providersChanged() {
        LyricsCache.clear()
        _providersVersion.value++
    }
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
                var plainFallback: String? = null
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
                    if (plain.isNotBlank() && plain != "null" && plainFallback == null) plainFallback = plain
                }
                emptyList<LyricLine>() to plainFallback
            } finally { conn.disconnect() }
        } catch (_: Exception) { emptyList<LyricLine>() to null }
    }
}

@Composable
internal fun LyricsSettingsPage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val enabled by LyricsPrefs.enabledState.collectAsState()
    val shown by LyricsPrefs.shownState.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Lyrics", style = MaterialTheme.typography.headlineSmall)
        ListItem(headlineContent = { Text("Enable lyrics") },
            supportingContent = { Text("Search for lyrics when a video plays.") },
            trailingContent = { Switch(enabled, onCheckedChange = { LyricsPrefs.set(context, "enabled", it) }) })
        ListItem(headlineContent = { Text("Show lyrics overlay") },
            supportingContent = { Text("Show lyrics when found. Use the music icon before Settings in the top header to show or hide them.") },
            trailingContent = { Switch(shown, onCheckedChange = { LyricsPrefs.set(context, "show", it) }) })
        Text("Free lyrics providers (priority order)", style = MaterialTheme.typography.titleMedium)
        var revision by remember { mutableIntStateOf(0) }
        val prefs = context.getSharedPreferences("lyrics", 0)
        val providers = remember(revision) {
            prefs.getString("providers", LyricsProviders.ORDER).orEmpty().split(",")
                .mapNotNull { id -> LyricsProvider.entries.firstOrNull { it.name == id } }
        }
        providers.forEach { provider ->
            var checked by remember(provider, revision) {
                mutableStateOf(prefs.getBoolean("provider_" + provider.name, provider != LyricsProvider.NETEASE))
            }
            ListItem(
                headlineContent = { Text(provider.title) },
                leadingContent = {
                    Row {
                        Text("↑", Modifier.clickable { LyricsProviders.reorder(context, provider, -1); revision++ }.padding(8.dp))
                        Text("↓", Modifier.clickable { LyricsProviders.reorder(context, provider, 1); revision++ }.padding(8.dp))
                    }
                },
                trailingContent = {
                    Switch(checked, onCheckedChange = {
                        checked = it
                        prefs.edit().putBoolean("provider_" + provider.name, it).apply()
                        LyricsPrefs.providersChanged()
                        revision++
                    })
                },
            )
        }
        Text("Providers are tried in order. LRCLIB, SimpMusic, NetEase, RMM Revival, bLyrics and BiniLyrics can return timed lyrics; Lyrics.ovh offers plain text.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun LyricsOverlay(snap: Snap, content: LyricsContent, onToggle: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lines = content.lines
    val plain = content.plain
    val liveClock by WebHost.lyricsClock.collectAsState()
    var verticalOffset by remember { mutableFloatStateOf(LyricsPrefs.position(context, "panel_y", 0f)) }
    var now by remember { mutableLongStateOf(0L) }
    var offsetMs by remember { mutableLongStateOf(LyricsPrefs.position(context, "sync_offset_ms", 0f).toLong()) }
    // The direct WebView clock is authoritative. Media3's snapshot is only a
    // fallback before the first sample arrives or after WebView replacement.
    LaunchedEffect(snap.id, snap.posMs, snap.playing, snap.buffering, snap.rate, liveClock, offsetMs) {
        val snapshotAt = SystemClock.elapsedRealtime()
        while (true) {
            val tickAt = SystemClock.elapsedRealtime()
            now = liveClock.positionFor(snap.id, tickAt, offsetMs)
                ?: LyricsSync.position(snap.posMs, snap.playing, snap.buffering, snap.rate,
                    tickAt - snapshotAt, offsetMs)
            delay(100)
        }
    }
    if (!content.foundFor(LyricsCache.key(snap))) return
    Column(
        Modifier.fillMaxWidth().offset { IntOffset(0, verticalOffset.roundToInt()) }
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = {
                    LyricsPrefs.setPosition(context, "panel_y", verticalOffset)
                }) { change, drag ->
                    change.consume()
                    verticalOffset = (verticalOffset + drag.y).coerceIn(0f, size.height * 9f)
                }
            }
            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics  ↕", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text("×", color = Color.White, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clickable { onToggle() }.padding(horizontal = 8.dp))
        }
        if (lines.isNotEmpty()) {
            Text("Synchronized lyrics", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    offsetMs = (offsetMs - 250L).coerceAtLeast(-5000L)
                    LyricsPrefs.setPosition(context, "sync_offset_ms", offsetMs.toFloat())
                }) { Text("−0.25s") }
                Text("Sync " + (if (offsetMs >= 0) "+" else "") + (offsetMs / 1000.0) + "s",
                    color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = {
                    offsetMs = (offsetMs + 250L).coerceAtMost(5000L)
                    LyricsPrefs.setPosition(context, "sync_offset_ms", offsetMs.toFloat())
                }) { Text("+0.25s") }
            }
        }
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
            !plain.isNullOrBlank() -> {
                Text("Lyrics without timestamps — cannot follow the song",
                    color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                Text(plain.take(1500), color = Color.White, textAlign = TextAlign.Center)
            }
        }
    }
}

