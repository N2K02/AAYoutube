package com.aayoutube.app

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.source.MediaSource
import com.googlevideo.sabr.SabrSession
import com.googlevideo.sabr.datasource.DefaultSabrSegmentFetcher
import com.googlevideo.sabr.datasource.SabrDataSourceFactory
import com.googlevideo.sabr.manifest.SabrManifestParser
import com.googlevideo.sabr.session.SabrSessionManager
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Audio-only SABR transport. Valid authorized session MPD supplied by the configured resolver. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class SabrAudioSource private constructor(
    val source: MediaSource,
    private val client: OkHttpClient,
    private val manager: SabrSessionManager,
) {
    fun updatePosition(positionMs: Long) {
        manager.updatePlaybackMetrics(playerPositionSeconds = positionMs.coerceAtLeast(0L) / 1000.0)
    }
    fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }

    companion object {
        fun create(stream: AudioStream): SabrAudioSource {
            val xml = requireNotNull(stream.sabrManifest)
            require(SabrManifestPolicy.valid(xml, stream.mimeType, stream.codecs))
            val parsed = SabrManifestParser.parse(xml)
            require(parsed.isSabrStream)
            val info = parsed.session.manifestInfo
            val endpoint = URI(requireNotNull(info.serverAbrStreamingUrl))
            require(endpoint.scheme == "https" && !endpoint.host.isNullOrBlank() &&
                endpoint.userInfo == null && endpoint.fragment == null)
            require(!info.ustreamerConfig.isNullOrBlank())
            val format = info.sabrFormats.singleOrNull { f ->
                f.width == null && f.mimeType?.substringBefore(';')?.trim() == stream.mimeType &&
                    info.sabrBaseUrls.singleOrNull()?.let { URI(it).query == "key=${f.itag}:${f.xtags.orEmpty()}" } == true
            } ?: error("missing audio format")
            val session = SabrSession(info.copy(sabrFormats = listOf(format)))
            val manager = SabrSessionManager().apply { applyManifest(session.manifestInfo); updateActiveFormats(null, format) }
            val host = endpoint.host.lowercase()
            val googlevideo = host == "googlevideo.com" || host.endsWith(".googlevideo.com")
            val client = OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val url = chain.request().url
                    if (!url.isHttps || !(url.host == host || (googlevideo && url.host.endsWith(".googlevideo.com"))))
                        throw IOException("SABR destination rejected")
                    chain.proceed(chain.request())
                }.build()
            val chunks = SabrDataSourceFactory(
                delegateFactory = DataSource.Factory { ByteArrayDataSource(ByteArray(0)) },
                sessionManager = manager, sessionProvider = { session },
                segmentFetcher = DefaultSabrSegmentFetcher(callFactory = client, maxFollowUpAttempts = 2))
            val manifest = DataSource.Factory { ByteArrayDataSource(xml.toByteArray(Charsets.UTF_8)) }
            val source = DashMediaSource.Factory(DefaultDashChunkSource.Factory(chunks), manifest)
                .createMediaSource(MediaItem.Builder().setUri("https://example.invalid/aay.sabr.mpd")
                    .setMimeType(MimeTypes.APPLICATION_MPD).build())
            return SabrAudioSource(source, client, manager)
        }
    }
}
