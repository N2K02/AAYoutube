package com.aayoutube.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

/** Bounded, asynchronous MediaCompat artwork loader owned by PlaybackService. */
internal class MediaArtworkLoader(private val onChanged: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aay-artwork").apply { isDaemon = true }
    }
    private var selected = ""
    private var bitmap: Bitmap? = null
    private var generation = 0
    private var closed = false

    fun request(url: String) {
        if (closed || selected == url) return
        selected = url
        bitmap = null
        val version = ++generation
        if (url.isBlank()) return
        worker.execute {
            val decoded = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 4_000
                conn.readTimeout = 4_000
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val output = ByteArrayOutputStream()
                    conn.inputStream.use { input ->
                        val chunk = ByteArray(8_192)
                        while (output.size() <= 768_000) {
                            val length = input.read(chunk)
                            if (length <= 0) break
                            output.write(chunk, 0, length)
                        }
                    }
                    if (output.size() > 768_000) null else {
                        val bytes = output.toByteArray()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                            BitmapFactory.Options().apply { inSampleSize = 2 })
                    }
                } finally { conn.disconnect() }
            }.getOrNull()
            main.post {
                if (!closed && generation == version && selected == url && decoded != null) {
                    bitmap = decoded
                    onChanged()
                }
            }
        }
    }

    fun bitmapFor(url: String): Bitmap? = if (!closed && url == selected) bitmap else null
    fun shutdown() {
        closed = true
        generation++
        selected = ""
        bitmap = null
        worker.shutdownNow()
    }

    companion object {
        private val trusted = listOf("ytimg.com", "ggpht.com", "googleusercontent.com")
        /** Do not request arbitrary URLs from WebView-controlled metadata. */
        fun urlFor(input: String, videoId: String): String {
            val trusted = allowedUrl(input)
            if (trusted.isNotBlank()) return trusted
            return if (videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            } else ""
        }

        fun allowedUrl(input: String): String = try {
            val uri = URI(input)
            val host = uri.host?.lowercase().orEmpty()
            if (uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
                trusted.any { host == it || host.endsWith(".$it") }) input else ""
        } catch (_: Exception) { "" }
    }
}
