package com.aayoutube.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Wiring of the experimental native audio engine; the WebView engine stays the default. */
class NativeAudioContractTest {
    private fun read(path: String): String =
        listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }.readText()
    private fun kt(name: String) = read("java/com/aayoutube/app/$name")

    @Test fun disabledByDefaultAndPersisted() {
        assertFalse(FeatureFlags().nativeAudio)
        val features = kt("Features.kt")
        assertTrue(features.contains("nativeAudio = prefs.getBoolean(\"nativeAudio\", d.nativeAudio)"))
        assertTrue(features.contains(".putBoolean(\"nativeAudio\", n.nativeAudio)"))
        // Switching it off hands playback straight back to the web engine.
        assertTrue(kt("Screens.kt").contains("if (!enabled) NativePlayback.returnToWeb(\"feature-off\")"))
    }

    @Test fun mediaEngineIsAudioOnlyWithFocusNoisyAndGatedOffload() {
        val e = kt("NativeAudioEngine.kt")
        assertTrue("no video download/decoding", e.contains(".setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)"))
        assertTrue("audio focus: calls, navigation prompts", e.contains("/* handleAudioFocus = */ true,"))
        assertTrue("Bluetooth/wired disconnect pauses", e.contains(".setHandleAudioBecomingNoisy(true)"))
        assertTrue(e.contains("if (offload) TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED"))
        assertTrue(e.contains("else TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED"))
        // Silent until started: never audible while the web engine still plays.
        val build = e.substringAfter("private fun build(").substringBefore("override fun start(")
        assertTrue(build.contains("p.volume = 0f"))
        assertTrue(build.contains("p.playWhenReady = false"))
        // Real decoder and offload state are logged.
        assertTrue(e.contains("override fun onAudioDecoderInitialized("))
        assertTrue(e.contains("override fun onOffloadedPlayback(offloadedPlayback: Boolean)"))
        assertTrue(e.contains("if (offloadedPlayback) decoderName = \"dsp-offload\""))
        assertTrue(e.contains("override fun onSleepingForOffloadChanged("))
        assertTrue(e.contains("override fun onAudioUnderrun("))
        // Offload failure: retried once without offload at the same position.
        val err = e.substringAfter("override fun onPlayerError(").substringBefore("private val analytics")
        assertTrue(err.contains("if (wasOffloaded && !retriedWithoutOffload)"))
        assertTrue(err.contains("build(s, pos, offload = false)"))
        assertTrue(err.indexOf("listener.onError(offloaded = true") < err.indexOf("build(s, pos, offload = false)"))
        assertTrue(err.contains("listener.onError(offloaded = false"))
    }

    @Test fun offloadFailureIsRecordedAndOtherErrorsFallBackToWeb() {
        val glue = kt("NativePlayback.kt")
        val onError = glue.substringAfter("override fun onError(offloaded: Boolean").substringBefore("override fun log(")
        assertTrue(onError.contains("breaker?.recordFailure(AudioOffloadPolicy.failureKey(codecMime, route)"))
        assertTrue(onError.contains("coordinator?.onNativeError(detail)"))
        assertTrue(glue.contains("blockedByFailure = breaker?.isBlocked(key, System.currentTimeMillis()) == true"))
        assertTrue(glue.contains("deviceSupportsOffload = AudioCapabilityProbe.supportsOffload(application, stream)"))
        assertTrue(glue.contains("StreamSelection.bestForPlayback(streams.audio, caps, wallNow)"))
        assertTrue(glue.contains("val car = WebHost.carProjectionActive()"))
    }

    @Test fun webHostRoutesTransportsAndMergesState() {
        val host = kt("WebHost.kt")
        val cmd = host.substringAfter("    fun cmd(name: String) {").substringBefore("fun scrollBy(")
        assertTrue(cmd.indexOf("NativePlayback.command(name)") < cmd.indexOf("window.__aay.cmd('\$name')"))
        val seek = host.substringAfter("fun seekTo(positionMs: Long) {").substringBefore("fun showOnCar(")
        assertTrue(seek.indexOf("NativePlayback.seek(target)") < seek.indexOf("cmd(\"seek\""))
        assertTrue(host.contains("publish(NativePlayback.mergeWebState(s, webNativeMarker))"))
        assertTrue(host.contains("if (!sessionClearing && NativePlayback.nativeOwns) publish(update(snapshot.value))"))
        // Native audio is not "other media" for the web's focus hold.
        val hold = host.substringAfter("internal fun startOtherMediaHold(reason: String) {").substringBefore("otherMediaHeldSince = SystemClock")
        assertTrue(hold.contains("if (NativePlayback.nativeOwns) return"))
        // Quit / renderer release releases the native engine.
        val release = host.substringAfter("fun release(preserveCarOwners: Boolean = false) {").substringBefore("main.removeCallbacks(freezeSoon)")
        assertTrue(release.contains("NativePlayback.release()"))
        assertTrue(kt("AAYoutubeApp.kt").contains("NativePlayback.init(this)"))
    }

    @Test fun pageSilencesItselfAndStopsVideoLoadingWhileNativeOwns() {
        val js = read("assets/aay.js")
        val on = js.substringAfter("A.nativeAudio = function (on, posSec, resume) {").substringBefore("A.nativeOwned")
        assertTrue(on.contains("bgWatch.explicitPause = true; bgWatch.wanted = false;"))
        assertTrue(on.contains("if (v) enforceExplicitPause(v);"))
        assertTrue(js.contains("if (nativeAudio.owned) return 'tiny';"))
        assertTrue(js.contains("native: !!nativeAudio.owned"))
        assertTrue(kt("WebHost.kt").contains("window.__aay.nativeAudio(\$on,\$sec,\$resume)"))
    }

    @Test fun onlyOneMediaSessionAndMedia3Dependency() {
        // The existing MediaSessionCompat (notification, lock screen, Bluetooth, Android Auto) stays
        // the only session: the native engine reports through WebHost's snapshot.
        val dir = listOf(File("src/main/java/com/aayoutube/app"), File("app/src/main/java/com/aayoutube/app")).first { it.isDirectory }
        dir.listFiles()!!.filter { it.extension == "kt" }.forEach {
            assertFalse(it.name, it.readText().contains("androidx.media3.session"))
        }
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.isFile && it.readText().contains("dependencies") }.readText()
        assertTrue(gradle.contains("androidx.media3:media3-exoplayer:"))
        assertFalse(gradle.contains("newpipe", ignoreCase = true))
    }
}
