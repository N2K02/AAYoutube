package com.aayoutube.app

import android.media.AudioDeviceInfo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAudioEngineTest {
    @Test fun backgroundLightweightAudioOnlyWithoutVisibleVideo() {
        assertTrue(PlaybackHandoffPolicy.backgroundWebAudio(true, false, false, false))
        assertFalse(PlaybackHandoffPolicy.backgroundWebAudio(false, false, false, false))
        assertFalse(PlaybackHandoffPolicy.backgroundWebAudio(true, true, false, false))
        assertFalse(PlaybackHandoffPolicy.backgroundWebAudio(true, false, true, false))
        assertFalse(PlaybackHandoffPolicy.backgroundWebAudio(true, false, false, true))
    }

    @Test fun sixAudioVariantsPreferActualOffloadThenHardwareAndBitrate() {
        val formats = listOf(
            AudioStream("https://a/1", "audio/mp4", "mp4a.40.2", 48000, 44100),
            AudioStream("https://a/2", "audio/mp4", "mp4a.40.2", 128000, 44100),
            AudioStream("https://a/3", "audio/webm", "opus", 48000, 48000),
            AudioStream("https://a/4", "audio/webm", "opus", 64000, 48000),
            AudioStream("https://a/5", "audio/webm", "opus", 128000, 48000),
            AudioStream("https://a/6", "audio/webm", "opus", 160000, 48000),
        )
        val caps = AudioCapabilities(listOf(
            AudioDecoderInfo("aac", StreamSelection.AAC, true),
            AudioDecoderInfo("opus", StreamSelection.OPUS, false),
        ))
        assertEquals("https://a/5", StreamSelection.bestForPlayback(formats, caps, 0) { it.url == "https://a/5" }!!.url)
        assertEquals("https://a/2", StreamSelection.bestForPlayback(formats, caps, 0) { false }!!.url)
        val expired = formats.map { if (it.url == "https://a/5") it.copy(expiresAtMs = 1) else it }
        assertEquals("https://a/2", StreamSelection.bestForPlayback(expired, caps, 2) { it.url == "https://a/5" }!!.url)
    }

    @After fun resetProviders() = StreamProviders.reset()

    // ---------- StreamProvider ----------

    private fun aac(bitrate: Int = 128_000, url: String = "https://a/aac", expires: Long = 0L) =
        AudioStream(url, "audio/mp4", "mp4a.40.2", bitrate, 44_100, 2, expires)
    private fun opus(bitrate: Int = 160_000, url: String = "https://a/opus") =
        AudioStream(url, "audio/webm; codecs=\"opus\"", "opus", bitrate, 48_000)

    @Test fun shippedDefaultHasNoProviderAndWebKeepsPlaying() = runBlocking<Unit> {
        assertEquals("none", StreamProviders.current.id)
        assertEquals(StreamResult.Unavailable(UnavailableReason.NO_PROVIDER), StreamProviders.resolve("abc"))
    }

    @Test fun unauthorizedProvidersAreNeverRegisteredOrUsed() = runBlocking<Unit> {
        val rogue = object : StreamProvider {
            override val id = "rogue"
            override val authorized = false
            override suspend fun resolve(videoId: String): StreamResult =
                StreamResult.Resolved(ResolvedStreams(videoId, listOf(aac())))
        }
        assertFalse(StreamProviders.register(rogue))
        assertEquals("none", StreamProviders.current.id)
        val r = StreamProviders.resolve("abc", rogue)
        assertEquals(UnavailableReason.NOT_AUTHORIZED, (r as StreamResult.Unavailable).reason)
    }

    @Test fun authorizedProviderResolvesAndFailuresBecomeUnavailable() = runBlocking<Unit> {
        val ok = object : StreamProvider {
            override val id = "licensed"
            override val authorized = true
            override suspend fun resolve(videoId: String): StreamResult =
                if (videoId == "boom") throw IllegalStateException("x")
                else StreamResult.Resolved(ResolvedStreams(videoId, listOf(aac())))
        }
        assertTrue(StreamProviders.register(ok))
        assertTrue(StreamProviders.resolve("v1") is StreamResult.Resolved)
        val failed = StreamProviders.resolve("boom") as StreamResult.Unavailable
        assertEquals(UnavailableReason.ERROR, failed.reason)
        assertEquals("IllegalStateException", failed.detail)
    }

    @Test fun codecMimeFromContainerAndCodecs() {
        assertEquals(StreamSelection.OPUS, StreamSelection.codecMime("audio/webm; codecs=\"opus\"", "opus"))
        assertEquals(StreamSelection.AAC, StreamSelection.codecMime("audio/mp4", "mp4a.40.2"))
        assertEquals(StreamSelection.AAC, StreamSelection.codecMime("audio/mp4", ""))
        assertEquals(StreamSelection.VORBIS, StreamSelection.codecMime("audio/webm", "vorbis"))
        assertEquals(StreamSelection.EAC3, StreamSelection.codecMime("audio/mp4", "ec-3"))
        assertEquals(StreamSelection.MP3, StreamSelection.codecMime("audio/mpeg", ""))
        assertEquals("audio/x-unknown", StreamSelection.codecMime("audio/x-unknown", ""))
    }

    private val swBoth = AudioCapabilityProbe.fromDecoders(listOf(
        AudioDecoderInfo("c2.android.aac.decoder", "audio/mp4a-latm", false),
        AudioDecoderInfo("c2.android.opus.decoder", "audio/opus", false),
    ))

    @Test fun bestAudioPrefersOffloadThenHardwareThenBitrate() {
        // Software only: highest bitrate.
        assertEquals("https://a/opus", StreamSelection.bestAudio(listOf(aac(), opus()), swBoth, false, 0)!!.url)
        // AAC offload-capable and offload wanted: AAC despite lower bitrate.
        val aacOffload = swBoth.copy(offload = mapOf(StreamSelection.AAC to true))
        assertEquals("https://a/aac", StreamSelection.bestAudio(listOf(aac(), opus()), aacOffload, true, 0)!!.url)
        // Offload not wanted: back to bitrate.
        assertEquals("https://a/opus", StreamSelection.bestAudio(listOf(aac(), opus()), aacOffload, false, 0)!!.url)
        // Hardware AAC beats software Opus.
        val hwAac = AudioCapabilityProbe.fromDecoders(listOf(
            AudioDecoderInfo("c2.exynos.aac", "audio/mp4a-latm", true),
            AudioDecoderInfo("c2.android.opus.decoder", "audio/opus", false)))
        assertEquals("https://a/aac", StreamSelection.bestAudio(listOf(aac(), opus()), hwAac, false, 0)!!.url)
    }

    @Test fun unplayableExpiredOrMissingAudioIsReported() {
        val onlyAac = AudioCapabilityProbe.fromDecoders(listOf(AudioDecoderInfo("aac", "audio/mp4a-latm", true)))
        assertNull(StreamSelection.bestAudio(listOf(opus()), onlyAac, false, 0))
        assertEquals(UnavailableReason.UNSUPPORTED_CODEC,
            StreamSelection.unavailable(ResolvedStreams("v", listOf(opus())), onlyAac, 0))
        assertEquals(UnavailableReason.NO_AUDIO,
            StreamSelection.unavailable(ResolvedStreams("v", emptyList(), listOf(VideoStream("u", "video/mp4", "avc1", 1280, 720, 1, hasAudio = true))), onlyAac, 0))
        assertEquals(UnavailableReason.LIVE, StreamSelection.unavailable(ResolvedStreams("v", listOf(aac()), live = true), onlyAac, 0))
        assertEquals(UnavailableReason.EXPIRED, StreamSelection.unavailable(ResolvedStreams("v", listOf(aac(expires = 100))), onlyAac, 200))
        assertNull(StreamSelection.bestAudio(listOf(aac(expires = 100)), onlyAac, false, 200))
        assertNull(StreamSelection.unavailable(ResolvedStreams("v", listOf(aac())), onlyAac, 0))
        // Separate audio present: video is not needed for audio playback.
        assertTrue(ResolvedStreams("v", listOf(aac()), listOf(VideoStream("u", "video/webm", "vp9", 1920, 1080, 1))).separateAudio)
    }

    // ---------- AudioCapabilityProbe ----------

    @Test fun capabilitiesKeepOnlyAudioFormatsAndSummarise() {
        val caps = AudioCapabilityProbe.fromDecoders(listOf(
            AudioDecoderInfo("c2.android.aac.decoder", "audio/mp4a-latm", false),
            AudioDecoderInfo("c2.qti.aac.hw", "AUDIO/MP4A-LATM", true),
            AudioDecoderInfo("c2.android.opus.decoder", "audio/opus", false),
            AudioDecoderInfo("c2.qti.avc.decoder", "video/avc", true),
        ), mapOf("AUDIO/MP4A-LATM" to true))
        assertTrue(caps.supports(StreamSelection.AAC))
        assertTrue(caps.hardware(StreamSelection.AAC))
        assertFalse(caps.hardware(StreamSelection.OPUS))
        assertTrue(caps.offloadSupported(StreamSelection.AAC))
        assertFalse(caps.supports("video/avc"))
        assertEquals("c2.qti.aac.hw", caps.decoderFor(StreamSelection.AAC)!!.name)
        val summary = caps.summary()
        assertTrue(summary, summary.contains("mp4a-latm=hw+offload"))
        assertTrue(summary, summary.contains("opus=sw"))
        assertTrue(summary, summary.contains("flac=none"))
    }

    // ---------- AudioOffloadPolicy ----------

    private fun inputs(route: AudioRoute = AudioRoute.SPEAKER, codec: String = StreamSelection.AAC,
                       supported: Boolean = true, speed: Float = 1f, allowed: Boolean = true, blocked: Boolean = false) =
        OffloadInputs(route, codec, supported, speed, allowed, blocked)

    @Test fun offloadOnlyWhenEverythingIsCompatible() {
        assertTrue(AudioOffloadPolicy.decide(inputs()).enabled)
        assertTrue(AudioOffloadPolicy.decide(inputs(route = AudioRoute.BLUETOOTH_A2DP, codec = StreamSelection.OPUS)).enabled)
        assertEquals("route=car-projection", AudioOffloadPolicy.decide(inputs(route = AudioRoute.CAR_PROJECTION)).reason)
        assertFalse(AudioOffloadPolicy.decide(inputs(route = AudioRoute.OTHER)).enabled)
        assertFalse(AudioOffloadPolicy.decide(inputs(codec = StreamSelection.VORBIS)).enabled)
        assertEquals("speed=1.5", AudioOffloadPolicy.decide(inputs(speed = 1.5f)).reason)
        assertEquals("device-unsupported", AudioOffloadPolicy.decide(inputs(supported = false)).reason)
        assertEquals("recent-failure", AudioOffloadPolicy.decide(inputs(blocked = true)).reason)
        assertEquals("disabled", AudioOffloadPolicy.decide(inputs(allowed = false)).reason)
    }

    @Test fun routeFromActiveOutputDevice() {
        assertEquals(AudioRoute.CAR_PROJECTION, AudioOffloadPolicy.routeOf(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), carProjection = true))
        assertEquals(AudioRoute.BLUETOOTH_A2DP, AudioOffloadPolicy.routeOf(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), false))
        assertEquals(AudioRoute.BLUETOOTH_LE, AudioOffloadPolicy.routeOf(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET), false))
        assertEquals(AudioRoute.WIRED, AudioOffloadPolicy.routeOf(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES), false))
        assertEquals(AudioRoute.USB, AudioOffloadPolicy.routeOf(listOf(AudioDeviceInfo.TYPE_USB_HEADSET), false))
        assertEquals(AudioRoute.SPEAKER, AudioOffloadPolicy.routeOf(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), false))
        assertEquals(AudioRoute.OTHER, AudioOffloadPolicy.routeOf(emptyList(), false))
    }

    private class MemStore : LongStore {
        val m = mutableMapOf<String, Long>()
        override fun get(key: String) = m[key] ?: 0L
        override fun put(key: String, value: Long) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }

    @Test fun offloadFailureDisablesItForTheCodecAndRouteAutomatically() {
        val store = MemStore()
        val b = OffloadFailureBreaker(store)
        val key = AudioOffloadPolicy.failureKey(StreamSelection.AAC, AudioRoute.BLUETOOTH_A2DP)
        assertFalse(b.isBlocked(key, 1_000))
        b.recordFailure(key, 1_000)
        assertTrue(b.isBlocked(key, 1_000 + OffloadFailureBreaker.BLOCK_MS - 1))
        // Other route or codec unaffected.
        assertFalse(b.isBlocked(AudioOffloadPolicy.failureKey(StreamSelection.AAC, AudioRoute.SPEAKER), 2_000))
        // Survives restarts (same store), expires after a day.
        assertTrue(OffloadFailureBreaker(store).isBlocked(key, 5_000))
        assertFalse(b.isBlocked(key, 1_000 + OffloadFailureBreaker.BLOCK_MS))
        assertTrue(store.m.isEmpty())
        // Clock rolled back (reboot / time change): never blocked forever.
        b.recordFailure(key, 10_000)
        assertFalse(b.isBlocked(key, 5_000))
    }

    // ---------- PlaybackHandoffPolicy ----------

    private val playing = Snap(id = "v", playing = true, posMs = 30_000, durMs = 200_000, rate = 1f, title = "T", artist = "A",
        queue = listOf(QItem("v", "T", "A"), QItem("w", "U", "B")))

    @Test fun positionKeepsMovingWhilePlayingAndStopsWhenPaused() {
        val s = HandoffState.from(playing, 1_000)
        assertEquals(32_000, PlaybackHandoffPolicy.positionAt(s, 3_000))
        assertEquals(34_000, PlaybackHandoffPolicy.positionAt(s.copy(rate = 2f), 3_000))
        assertEquals(30_000, PlaybackHandoffPolicy.positionAt(HandoffState.from(playing.copy(playing = false, userPaused = true), 1_000), 60_000))
        assertEquals(200_000, PlaybackHandoffPolicy.positionAt(s, 10_000_000))
        assertEquals(30_000, PlaybackHandoffPolicy.positionAt(s, 0)) // clock never runs backwards
    }

    @Test fun userIntentAndMetadataTravelWithTheHandoff() {
        val s = HandoffState.from(playing, 0)
        assertTrue(PlaybackHandoffPolicy.shouldResume(s))
        assertFalse(PlaybackHandoffPolicy.shouldResume(HandoffState.from(playing.copy(userPaused = true), 0)))
        assertEquals("T", s.title); assertEquals(2, s.queue.size)
        val merged = PlaybackHandoffPolicy.merge(playing, 45_000, nativePlaying = true, nativeBuffering = false, userPaused = false)
        assertEquals(45_000, merged.posMs)
        assertEquals(playing.queue, merged.queue)
        assertEquals("T", merged.title)
        assertEquals(200_000, PlaybackHandoffPolicy.merge(playing, 999_999, true, false, false).posMs)
        assertTrue(PlaybackHandoffPolicy.merge(playing.copy(playing = false, userPaused = true), 45_000, false, false, true).userPaused)
    }

    @Test fun nativeAudioNeverTakesOverVisibleVideoOrDisabledBackgroundPlayback() {
        assertTrue(PlaybackHandoffPolicy.audioOnlyAllowed(true, true, false, false, false))
        assertFalse(PlaybackHandoffPolicy.audioOnlyAllowed(false, true, false, false, false))
        assertFalse(PlaybackHandoffPolicy.audioOnlyAllowed(true, false, false, false, false))
        assertFalse(PlaybackHandoffPolicy.audioOnlyAllowed(true, true, true, false, false))
        assertFalse(PlaybackHandoffPolicy.audioOnlyAllowed(true, true, false, true, false))
        assertFalse(PlaybackHandoffPolicy.audioOnlyAllowed(true, true, false, false, true))
    }

    @Test fun nativeClockAvoidsFrequentWakeupsWhileDspSleeps() {
        assertEquals(1_000L, PlaybackHandoffPolicy.nativeClockIntervalMs(true, false, false))
        assertEquals(1_000L, PlaybackHandoffPolicy.nativeClockIntervalMs(true, true, false))
        assertEquals(1_000L, PlaybackHandoffPolicy.nativeClockIntervalMs(true, false, true))
        assertEquals(10_000L, PlaybackHandoffPolicy.nativeClockIntervalMs(true, true, true))
        assertNull(PlaybackHandoffPolicy.nativeClockIntervalMs(false, true, true))
    }

    @Test fun handoffOnlyForOrdinaryPlayingVideos() {
        assertTrue(PlaybackHandoffPolicy.canHandToNative(playing, true).allowed)
        assertEquals("feature-off", PlaybackHandoffPolicy.canHandToNative(playing, false).reason)
        assertEquals("live", PlaybackHandoffPolicy.canHandToNative(playing.copy(live = true), true).reason)
        assertEquals("ad", PlaybackHandoffPolicy.canHandToNative(playing.copy(ad = true), true).reason)
        assertEquals("near-end", PlaybackHandoffPolicy.canHandToNative(playing.copy(posMs = 197_000), true).reason)
        assertEquals("not-playing", PlaybackHandoffPolicy.canHandToNative(playing.copy(playing = false, userPaused = true), true).reason)
        assertEquals("no-video", PlaybackHandoffPolicy.canHandToNative(Snap(), true).reason)
        assertEquals("unknown-duration", PlaybackHandoffPolicy.canHandToNative(playing.copy(durMs = 0), true).reason)
    }

    // ---------- PlaybackEngineCoordinator ----------

    private class Events { val list = mutableListOf<String>() }

    private open class FakeEngine(override val id: EngineId, val ev: Events) : PreparableEngine {
        var audible = false
        var pos = 0L
        var released = false
        override fun prepare(stream: AudioStream, positionMs: Long, offload: OffloadDecision) {
            ev.list += "$id.prepare@$positionMs offload=${offload.enabled}"; pos = positionMs; released = false
        }
        override fun silence() { ev.list += "$id.silence"; audible = false }
        override fun start(videoId: String, positionMs: Long, resume: Boolean) {
            ev.list += "$id.start@$positionMs resume=$resume"; if (positionMs >= 0) pos = positionMs; audible = resume
        }
        override fun pause() { ev.list += "$id.pause"; audible = false }
        override fun play() { ev.list += "$id.play"; audible = true }
        override fun seek(positionMs: Long) { ev.list += "$id.seek@$positionMs"; pos = positionMs }
        override fun positionMs() = pos
        override fun release() { ev.list += "$id.release"; audible = false; released = true }
    }

    private var now = 10_000L
    private fun setup(): Triple<PlaybackEngineCoordinator, FakeEngine, FakeEngine> {
        val ev = Events()
        val web = FakeEngine(EngineId.WEB, ev).apply { audible = true; pos = 30_000 }
        val native = FakeEngine(EngineId.NATIVE, ev)
        return Triple(PlaybackEngineCoordinator(web, native, { now }), web, native)
    }
    private fun FakeEngine.events() = ev.list
    private fun assertNeverTwoAudible(web: FakeEngine, native: FakeEngine) =
        assertFalse("duplicate audible playback", web.audible && native.audible)
    private val offloadOn = OffloadDecision(true, "compatible")

    @Test fun preparationIsSilentAndWebKeepsPlayingUntilNativeIsReady() {
        val (c, web, native) = setup()
        assertTrue(c.prepareNative(HandoffState.from(playing, now), aac(), offloadOn))
        assertEquals(PlaybackEngineCoordinator.Phase.PREPARING, c.phase)
        assertEquals(EngineId.WEB, c.owner)
        assertTrue(web.audible); assertFalse(native.audible)
        assertEquals(setOf(EngineId.WEB), c.audible)
        assertFalse("one handoff at a time", c.prepareNative(HandoffState.from(playing, now), aac(), offloadOn))
    }

    @Test fun readyHandoffSilencesWebBeforeNativeStartsAtTheCompensatedPosition() {
        val (c, web, native) = setup()
        c.prepareNative(HandoffState.from(playing, now), aac(), offloadOn)
        now += 1_500
        c.onNativeReady(HandoffState.from(playing.copy(posMs = 31_400), now - 100))
        val ev = web.events()
        assertTrue(ev.indexOf("WEB.silence") < ev.indexOfFirst { it.startsWith("NATIVE.start") })
        assertEquals("NATIVE.start@31500 resume=true", ev.last())
        assertEquals(EngineId.NATIVE, c.owner)
        assertEquals(setOf(EngineId.NATIVE), c.audible)
        assertNeverTwoAudible(web, native)
        assertEquals(1_500L, c.lastTransitionMs)
    }

    @Test fun staleReadinessNeverSilencesTheNewWebVideo() {
        val (c, web, native) = setup()
        c.prepareNative(HandoffState.from(playing, now), aac(), offloadOn)
        c.onNativeReady(HandoffState.from(playing.copy(id = "new"), now))
        assertEquals(EngineId.WEB, c.owner)
        assertTrue(native.released)
        assertTrue(web.audible)
        assertFalse(web.events().contains("WEB.silence"))
    }

    @Test fun navigationOrMissingStateAbortsSilentPreparation() {
        for (id in listOf("new", "")) {
            val (c, web, native) = setup()
            c.prepareNative(HandoffState.from(playing, now), aac(), offloadOn)
            c.onWebReport(id, true)
            c.onNativeReady(HandoffState.from(playing, now))
            assertEquals(PlaybackEngineCoordinator.Phase.WEB, c.phase)
            assertTrue(native.released)
            assertTrue(web.audible)
        }
        val (c, web, native) = setup()
        c.prepareNative(HandoffState.from(playing, now), aac(), offloadOn)
        c.onNativeReady(null)
        assertEquals(EngineId.WEB, c.owner)
        assertTrue(native.released)
        assertTrue(web.audible)
    }

    @Test fun nativeWaitsForActualWebPauseAndIgnoresLateAcknowledgements() {
        val ev = Events()
        var ack: ((Boolean) -> Unit)? = null
        val web = object : FakeEngine(EngineId.WEB, ev) {
            override fun silenceConfirmed(videoId: String, confirmed: (Boolean) -> Unit) { ack = confirmed }
        }.apply { audible = true }
        val native = FakeEngine(EngineId.NATIVE, ev)
        val c = PlaybackEngineCoordinator(web, native, { now })
        c.prepareNative(HandoffState.from(playing, now), aac(), offloadOn)
        c.onNativeReady(HandoffState.from(playing, now))
        assertTrue(web.audible); assertFalse(native.audible)
        assertEquals(EngineId.WEB, c.owner)
        web.silence(); ack!!(true)
        assertEquals(EngineId.NATIVE, c.owner)
        assertNeverTwoAudible(web, native)
        c.returnToWeb("test")
        c.prepareNative(HandoffState.from(playing, now), aac(), offloadOn)
        c.onNativeReady(HandoffState.from(playing, now))
        val late = ack!!
        c.command("pause")
        late(true)
        assertFalse(native.audible)
        assertEquals(EngineId.WEB, c.owner)
    }

    @Test fun explicitPauseDuringPreparationIsKeptByNative() {
        val (c, web, native) = setup()
        c.prepareNative(HandoffState.from(playing, now), aac(), offloadOn)
        c.onNativeReady(HandoffState.from(playing.copy(playing = false, userPaused = true), now))
        assertEquals("NATIVE.start@30000 resume=false", web.events().last())
        assertTrue(c.userPaused)
        assertFalse(native.audible)
    }

    @Test fun timeoutOrErrorDuringPreparationChangesNothingAudible() {
        val (c, web, native) = setup()
        c.prepareNative(HandoffState.from(playing, now), aac(), offloadOn)
        now += PlaybackHandoffPolicy.PREPARE_TIMEOUT_MS + 1
        c.onNativeReady(HandoffState.from(playing, now))
        assertEquals(PlaybackEngineCoordinator.Phase.WEB, c.phase)
        assertTrue(native.released)
        assertFalse(web.events().contains("WEB.silence"))
        assertTrue(web.audible)

        val (c2, web2, native2) = setup()
        c2.prepareNative(HandoffState.from(playing, now), aac(), offloadOn)
        c2.onNativeError("ERROR_CODE_IO_BAD_HTTP_STATUS")
        assertEquals(EngineId.WEB, c2.owner)
        assertTrue(native2.released)
        assertTrue(web2.audible)

        val (c3, _, native3) = setup()
        c3.prepareNative(HandoffState.from(playing, now), aac(), offloadOn)
        now += PlaybackHandoffPolicy.PREPARE_TIMEOUT_MS + 1
        c3.checkTimeout()
        assertTrue(native3.released)
    }

    private fun nativeOwning(): Triple<PlaybackEngineCoordinator, FakeEngine, FakeEngine> {
        val t = setup()
        t.first.prepareNative(HandoffState.from(playing, now), aac(), offloadOn)
        t.first.onNativeReady(HandoffState.from(playing, now))
        return t
    }

    @Test fun transportsGoToNativeWhileItOwnsPlayback() {
        val (c, web, native) = nativeOwning()
        assertTrue(c.command("pause")); assertTrue(c.userPaused); assertFalse(native.audible)
        assertTrue(c.command("play")); assertFalse(c.userPaused); assertTrue(native.audible)
        assertTrue(c.command("seek", 90_000)); assertEquals(90_000, native.pos)
        assertFalse(c.command("rate"))
        assertNeverTwoAudible(web, native)
    }

    @Test fun nextAndPrevReturnTheQueueToTheWeb() {
        val (c, web, native) = nativeOwning()
        native.pos = 80_000
        assertFalse("web performs next itself", c.command("next"))
        assertEquals(EngineId.WEB, c.owner)
        val ev = web.events()
        assertTrue(ev.indexOf("NATIVE.silence") < ev.indexOf("WEB.start@-1 resume=false"))
        assertTrue(native.released)
        assertNeverTwoAudible(web, native)
        assertFalse("native gone: web handles transports again", c.command("play"))
    }

    @Test fun errorReturnsToWebAtTheNativePositionWithTheUsersIntent() {
        val (c, web, native) = nativeOwning()
        native.pos = 75_000
        c.onNativeError("ERROR_CODE_DECODING_FAILED")
        assertEquals("WEB.start@75000 resume=true", web.events().last())
        assertTrue(web.audible); assertFalse(native.audible)

        val (c2, web2, native2) = nativeOwning()
        c2.command("pause")
        native2.pos = 50_000
        c2.onNativeError("x")
        assertEquals("WEB.start@50000 resume=false", web2.events().last())
        assertFalse("explicit pause preserved", web2.audible)
    }

    @Test fun unexpectedWebPlaybackImmediatelyStopsNativeAndNavigationHandsBack() {
        val (c, web, native) = nativeOwning()
        web.audible = true
        assertTrue(c.onWebReport("v", webPlaying = true))
        assertEquals(EngineId.WEB, c.owner)
        assertTrue(native.released)
        assertNeverTwoAudible(web, native)
        val (c2, web2, native2) = nativeOwning()
        c2.onWebReport("other", webPlaying = true)
        assertEquals(EngineId.WEB, c2.owner)
        assertTrue(native2.released)
        assertNeverTwoAudible(web2, native2)
    }

    @Test fun nativePermanentPauseIsPreservedWhenReturningToWeb() {
        val (c, web, native) = nativeOwning()
        native.pause()
        c.onNativeIntent(false)
        c.returnToWeb("visible")
        assertFalse(web.audible)
    }

    @Test fun userTransportCancelsPreparationBeforeSilenceRequest() {
        val (c, web, native) = setup()
        c.prepareNative(HandoffState.from(playing, now), aac(), offloadOn)
        assertFalse(c.command("seek", 50_000))
        assertTrue(native.released)
        assertTrue(web.audible)
        c.onNativeReady(HandoffState.from(playing, now))
        assertEquals(EngineId.WEB, c.owner)
    }

    @Test fun bufferingAndNonDefaultSpeedStayOnWeb() {
        assertFalse(PlaybackHandoffPolicy.canHandToNative(playing.copy(buffering = true), true).allowed)
        assertFalse(PlaybackHandoffPolicy.canHandToNative(playing.copy(rate = 1.5f), true).allowed)
    }

    @Test fun releaseOnQuitStopsNativeAndResetsOwnership() {
        val (c, web, native) = nativeOwning()
        c.release()
        assertTrue(native.released); assertFalse(native.audible)
        assertEquals(EngineId.WEB, c.owner)
        assertEquals(setOf(EngineId.WEB), c.audible)
        assertFalse(web.events().any { it.startsWith("WEB.start") })
        // Release while web owns: nothing to do.
        val (c2, _, native2) = setup()
        c2.release()
        assertFalse(native2.released)
    }

    @Test fun randomTransitionSequencesNeverProduceTwoAudibleEngines() {
        val rnd = java.util.Random(57)
        repeat(300) {
            val (c, web, native) = setup()
            repeat(25) {
                when (rnd.nextInt(10)) {
                    0 -> c.prepareNative(HandoffState.from(playing, now), aac(), offloadOn)
                    1 -> c.onNativeReady(HandoffState.from(playing.copy(userPaused = rnd.nextBoolean()), now))
                    2 -> c.command(listOf("play", "pause", "seek", "next", "prev")[rnd.nextInt(5)], 1_000)
                    3 -> c.onNativeError("e")
                    4 -> { if (c.owner == EngineId.NATIVE) web.audible = rnd.nextBoolean(); c.onWebReport("v", web.audible) }
                    5 -> c.returnToWeb("r")
                    6 -> { now += 9_000; c.checkTimeout() }
                    7 -> c.onWebReport("x", true)
                    8 -> c.release()
                    else -> now += 500
                }
                // Whenever native owns, the web was silenced; web-owned native is released/silent.
                if (c.owner == EngineId.NATIVE) assertFalse(web.audible && native.audible)
                if (c.owner == EngineId.WEB) assertFalse(native.audible)
                assertTrue(c.audible.size <= 1)
            }
        }
    }
}
