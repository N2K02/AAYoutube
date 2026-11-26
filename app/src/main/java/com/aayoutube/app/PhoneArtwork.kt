package com.aayoutube.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Video artwork for the phone while the car owns the single player: shown when
 * no live preview frame exists (audio-only, PiP, capture blocked). Trusted image
 * hosts only, bounded size, tiny in-memory cache; never a second video decoder.
 */
internal object PhoneArtwork {
    private const val MAX_BYTES = 768_000
    private val cache = object : LruCache<String, Bitmap>(4) {}

    fun cached(url: String): Bitmap? = if (url.isBlank()) null else cache.get(url)

    suspend fun load(url: String): Bitmap? {
        if (url.isBlank()) return null
        cache.get(url)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 4_000
                conn.readTimeout = 4_000
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val out = ByteArrayOutputStream()
                    conn.inputStream.use { input ->
                        val chunk = ByteArray(8_192)
                        while (out.size() <= MAX_BYTES) {
                            val n = input.read(chunk)
                            if (n <= 0) break
                            out.write(chunk, 0, n)
                        }
                    }
                    if (out.size() > MAX_BYTES) null else out.toByteArray().let {
                        BitmapFactory.decodeByteArray(it, 0, it.size)
                    }
                } finally { conn.disconnect() }
            }.getOrNull()
        }
        if (bitmap != null) cache.put(url, bitmap)
        return bitmap
    }
}

@Composable
internal fun rememberArtwork(snap: Snap): Bitmap? {
    val url = MediaArtworkLoader.urlFor(snap.art, snap.id)
    val art by produceState(PhoneArtwork.cached(url), url) { value = PhoneArtwork.load(url) }
    return art
}
