package com.aayoutube.app

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Glue between the WebView engine (WebHost), the experimental Media3 engine and the existing
 * media session. Off by default ([FeatureFlags.nativeAudio]); with no authorized
 * [StreamProvider] it only logs why the WebView keeps playing.
 *
 * Main thread only, except stream resolution (IO).
 */
internal object NativePlayback {
    private var app: Application? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: NativeAudioEngine? = null
    private var coordinator: PlaybackEngineCoordinator? = null
    private var breaker: OffloadFailureBreaker? = null
    private var capabilities: AudioCapabilities? = null
    private var capabilitiesRoute: AudioRoute? = null
    private var attemptedVideo = ""
    private var resolveSerial = 0
    private var nativePos = 0L
    private var nativePlaying = false
    private var nativeBuffering = false

    fun init(application: Application) {
        app = application
        val prefs = application.getSharedPreferences("aay_native_audio", Context.MODE_PRIVATE)
        breaker = OffloadFailureBreaker(object : LongStore {
            override fun get(key: String) = prefs.getLong(key, 0L)
            override fun put(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
            override fun remove(key: String) { prefs.edit().remove(key).apply() }
        })
    }

    val nativeOwns: Boolean get() = coordinator?.owner == EngineId.NATIVE

    private fun coordinator(): PlaybackEngineCoordinator? {
        coordinator?.let { return it }
        val a = app ?: return null
        val e = NativeAudioEngine(a, engineListener).also { engine = it }
        return PlaybackEngineCoordinator(WebEngine, e, SystemClock::elapsedRealtime) { AppLog.add("NATIVE-AUDIO", it) }
            .also { coordinator = it }
    }

    /**
     * Every web state report passes through here first. Returns the snapshot to publish: the
     * web's own while it owns playback, or the web's metadata with the native clock.
     */
    fun mergeWebState(web: Snap, webNativeMarker: Boolean): Snap {
        val c = coordinator
        if (c != null && c.phase == PlaybackEngineCoordinator.Phase.NATIVE) {
            // The page left the native-owned video or became audible: give way / silence it.
            c.onWebReport(web.id, web.playing && !webNativeMarker)
            if (c.owner == EngineId.NATIVE)
                return PlaybackHandoffPolicy.merge(web, nativePos, nativePlaying, nativeBuffering, c.userPaused)
            return web
        }
        c?.checkTimeout()
        maybeHandoff(web)
        return web
    }

    private fun maybeHandoff(s: Snap) {
        if (!Features.current.nativeAudio) return
        if (s.id == attemptedVideo) return
        val decision = PlaybackHandoffPolicy.canHandToNative(s, featureOn = true)
        if (!decision.allowed) return
        attemptedVideo = s.id
        val serial = ++resolveSerial
        val provider = StreamProviders.current
        scope.launch {
            val result = withContext(Dispatchers.IO) { StreamProviders.resolve(s.id, provider) }
            if (serial != resolveSerial) return@launch
            when (result) {
                is StreamResult.Unavailable -> AppLog.add("NATIVE-AUDIO",
                    "engine=web reason=${result.reason}${if (result.detail.isBlank()) "" else " detail=${result.detail}"} " +
                        "provider=${provider.id} video=${s.id}")
                is StreamResult.Resolved -> startHandoff(result.streams)
            }
        }
    }

    private fun startHandoff(streams: ResolvedStreams) {
        val c = coordinator() ?: return
        val current = WebHost.snapshot.value
        if (current.id != streams.videoId) return
        val now = SystemClock.elapsedRealtime()
        val route = currentRoute()
        val caps = capabilitiesFor(route)
        StreamSelection.unavailable(streams, caps, System.currentTimeMillis())?.let { reason ->
            AppLog.add("NATIVE-AUDIO", "engine=web reason=$reason video=${streams.videoId} caps=[${caps.summary()}]")
            return
        }
        val stream = StreamSelection.bestAudio(streams.audio, caps, preferOffload = true, nowMs = System.currentTimeMillis()) ?: return
        val key = AudioOffloadPolicy.failureKey(stream.codecMime, route)
        val offload = AudioOffloadPolicy.decide(OffloadInputs(
            route = route, codecMime = stream.codecMime,
            deviceSupportsOffload = caps.offloadSupported(stream.codecMime),
            speed = current.rate, blockedByFailure = breaker?.isBlocked(key, System.currentTimeMillis()) == true,
        ))
        AppLog.add("NATIVE-AUDIO", "route=$route caps=[${caps.summary()}] separateAudio=${streams.separateAudio} " +
            "videoStreams=${streams.video.size} videoDecoding=off")
        c.prepareNative(HandoffState.from(current, now), stream, offload)
        main.postDelayed({ coordinator?.checkTimeout() }, PlaybackHandoffPolicy.PREPARE_TIMEOUT_MS + 100L)
    }

    private val engineListener = object : NativeAudioEngine.Listener {
        override fun onReady() {
            coordinator?.onNativeReady(HandoffState.from(WebHost.snapshot.value, SystemClock.elapsedRealtime()))
        }

        override fun onState(playing: Boolean, buffering: Boolean, positionMs: Long, playWhenReady: Boolean) {
            nativePos = positionMs
            nativePlaying = playing
            nativeBuffering = buffering && playWhenReady
            val c = coordinator ?: return
            if (c.owner != EngineId.NATIVE) return
            WebHost.publishFromNative { web ->
                PlaybackHandoffPolicy.merge(web, positionMs, playing, nativeBuffering, c.userPaused || !playWhenReady)
            }
        }

        override fun onEnded() {
            // The web engine owns the queue and autoplay: it takes over at the end.
            coordinator?.returnToWeb("ended", resume = true)
        }

        override fun onError(offloaded: Boolean, codecMime: String, detail: String) {
            if (offloaded) {
                val route = capabilitiesRoute ?: AudioRoute.OTHER
                breaker?.recordFailure(AudioOffloadPolicy.failureKey(codecMime, route), System.currentTimeMillis())
                AppLog.add("NATIVE-AUDIO", "offload=disabled-after-failure codec=$codecMime route=$route detail=$detail retry=no-offload")
                return
            }
            coordinator?.onNativeError(detail)
        }

        override fun log(line: String) = AppLog.add("NATIVE-AUDIO", line)
    }

    /** Transport from the media session, car, headset or phone UI. True = handled natively. */
    fun command(name: String): Boolean = coordinator?.command(name) == true

    fun seek(positionMs: Long): Boolean = coordinator?.command("seek", positionMs) == true

    /** Feature switched off, Quit or renderer released: native gives way at once. */
    fun returnToWeb(reason: String) {
        coordinator?.returnToWeb(reason)
        attemptedVideo = ""
    }

    fun release() {
        resolveSerial++
        coordinator?.release()
        attemptedVideo = ""
        nativePos = 0L; nativePlaying = false; nativeBuffering = false
    }

    private fun capabilitiesFor(route: AudioRoute): AudioCapabilities {
        val a = app ?: return AudioCapabilities.NONE
        capabilities?.takeIf { capabilitiesRoute == route }?.let { return it }
        return AudioCapabilityProbe.probe(a).also { capabilities = it; capabilitiesRoute = route }
    }

    private fun currentRoute(): AudioRoute {
        val a = app ?: return AudioRoute.OTHER
        val car = WebHost.carProjectionActive()
        val types = runCatching {
            val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 33) {
                val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
                am.getAudioDevicesForAttributes(attrs).map { it.type }
            } else {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
                    .sortedBy { if (it == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) 1 else 0 }
            }
        }.getOrDefault(emptyList())
        return AudioOffloadPolicy.routeOf(types, car)
    }

    /** The WebView as a [PlaybackEngine]: silencing pauses it and stops its video loading. */
    private object WebEngine : PlaybackEngine {
        override val id = EngineId.WEB
        override fun silence() = WebHost.nativeAudioOwner(true, -1L, false)
        override fun start(videoId: String, positionMs: Long, resume: Boolean) =
            WebHost.nativeAudioOwner(false, positionMs, resume)
        override fun pause() = WebHost.cmd("pause")
        override fun play() = WebHost.cmd("play")
        override fun seek(positionMs: Long) = WebHost.seekTo(positionMs)
        override fun positionMs(): Long = WebHost.snapshot.value.posMs
        override fun release() = Unit
    }
}
