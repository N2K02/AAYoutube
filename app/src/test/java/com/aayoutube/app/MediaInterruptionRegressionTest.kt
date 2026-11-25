package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.aayoutube.app.OtherMediaHoldPolicy.Step

/** Review follow-up: focus flapping, calls, Bluetooth disconnects, wake locks. */
class MediaInterruptionRegressionTest {
    private fun read(p: String) = listOf(File(p), File("../$p")).first { it.isFile }.readText()

    @Test fun rapidFocusFlappingNeverResumesWhileOtherMediaBlips() {
        var quiet = 0L
        // other app audible, silent for 1 s, audible again: quiet timer must restart
        quiet = OtherMediaHoldPolicy.quietSince(quiet, true, 1_000L)
        quiet = OtherMediaHoldPolicy.quietSince(quiet, false, 2_000L)
        assertEquals(Step.KEEP_HOLDING, OtherMediaHoldPolicy.step(false, 0L, quiet, 3_000L))
        quiet = OtherMediaHoldPolicy.quietSince(quiet, true, 3_500L)
        assertEquals(0L, quiet)
        quiet = OtherMediaHoldPolicy.quietSince(quiet, false, 4_000L)
        assertEquals(Step.KEEP_HOLDING, OtherMediaHoldPolicy.step(false, 0L, quiet, 5_500L))
        assertEquals(Step.RESUME, OtherMediaHoldPolicy.step(false, 0L, quiet, 6_000L))
    }

    @Test fun incomingCallHoldsEvenWithoutMusicStream() {
        for (mode in listOf(1, 2, 3, 4)) assertTrue(OtherMediaHoldPolicy.othersActive(false, mode))
        assertFalse(OtherMediaHoldPolicy.othersActive(false, 0))
        assertFalse(OtherMediaHoldPolicy.othersActive(false, -1)) // MODE_CURRENT / invalid
    }

    @Test fun heldPlaybackReleasesTheCpuWakeLock() {
        val v = Snap(id = "abc")
        assertTrue(ForegroundDemotePolicy.wantsWakeLock(v.copy(playing = true), false))
        assertTrue(ForegroundDemotePolicy.wantsWakeLock(v, true))
        assertFalse(ForegroundDemotePolicy.wantsWakeLock(v.copy(held = true), true))
        assertFalse(ForegroundDemotePolicy.wantsWakeLock(v, false))
        // Foreground is still kept while held, so playback can restart from background.
        assertEquals(null, ForegroundDemotePolicy.delayMs(v.copy(held = true), true, 0L, 1L))
        assertEquals("held-other-media", ForegroundDemotePolicy.phase(v.copy(held = true), true))
    }

    @Test fun bluetoothOrHeadphoneDisconnectPausesOnlyRealPlayback() {
        val a = NoisyAudioPolicy.ACTION
        assertTrue(NoisyAudioPolicy.shouldPause(a, playing = true, quitting = false))
        assertFalse(NoisyAudioPolicy.shouldPause(a, playing = false, quitting = false))
        assertFalse(NoisyAudioPolicy.shouldPause(a, playing = true, quitting = true))
        assertFalse(NoisyAudioPolicy.shouldPause("other", playing = true, quitting = false))
        assertEquals(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY, a)
    }

    @Test fun wiringWaitsForPlaybackStateAndWakesOnChanges() {
        val web = read("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("main.postDelayed(droppedResumeJudge, OtherMediaHoldPolicy.SELF_SILENCE_MS)"))
        assertTrue(web.contains("am.registerAudioPlaybackCallback(cb, main)"))
        assertTrue(web.contains("am.addOnModeChangedListener(app.mainExecutor, l)"))
        assertTrue(web.contains("holdWake.acquire(OtherMediaHoldPolicy.WAKE_CHECK_MS)"))
        assertTrue(web.contains("held = otherMediaHeldSince != 0L && !incoming.playing"))
        val service = read("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        assertTrue(service.contains("ACTION_AUDIO_BECOMING_NOISY"))
        assertTrue(service.contains("ForegroundDemotePolicy.wantsWakeLock(s, recoveringPlayback)"))
        val protocol = read("app/src/main/java/com/aayoutube/app/Protocol.kt")
        assertTrue(protocol.contains("putBoolean(\"held\", held)"))
    }

    @Test fun delayedMediaHoldCannotInterruptRecoveredOrBufferingPlayback() {
        val web = read("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("if (generation != webGeneration) return@post"))
        assertTrue(web.contains("!current.playing && !current.buffering && !current.userPaused"))
        assertTrue(web.contains("current.id.isNotBlank() && playbackWanted && otherMediaActive()"))
        assertTrue(web.contains("else if (otherMediaHeldSince == 0L)"))
        assertTrue(web.contains("window.__aay.mediaHold(false)"))
        assertEquals(2, Regex("s.playing \\|\\| s.buffering \\|\\| !playbackWanted").findAll(web).count())
    }

    @Test fun noisyRouteCancelsRecoveryEvenWithoutActivePlayback() {
        val action = NoisyAudioPolicy.ACTION
        assertTrue(NoisyAudioPolicy.shouldPause(action, false, false, recovering = true))
        assertFalse(NoisyAudioPolicy.shouldPause(action, false, true, recovering = true))
        assertFalse(NoisyAudioPolicy.shouldPause("other", false, false, recovering = true))
        assertFalse(NoisyAudioPolicy.shouldPause(action, false, false, recovering = false))
    }

    @Test fun overloadFallbackIsWired() {
        val js = read("app/src/main/assets/aay.js")
        assertTrue(js.contains("try { overloadCheck(getVideo()); } catch (e) {}"))
        assertTrue(js.contains("var q = capForDevice(savedQuality());"))
    }
    @Test fun staleRecoveryIsCancelledWhenVideoOrRendererChanges() {
        val web = read("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("cancelOtherMediaWork(\"Renderer display changed\")"))
        assertTrue(web.contains("incoming.buffering || incoming.id != previous.id || !playbackWanted"))
        assertTrue(web.contains("incoming.playing || incoming.id != previous.id || !playbackWanted"))
    }
}
