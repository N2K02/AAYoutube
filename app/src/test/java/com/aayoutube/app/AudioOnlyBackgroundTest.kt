package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Background without PiP/mini on screen/car: Chromium stops decoding the unseen video. */
class AudioOnlyBackgroundTest {
    private fun src(name: String): String =
        listOf(File("app/src/main/java/com/aayoutube/app/$name"), File("../app/src/main/java/com/aayoutube/app/$name"))
            .first { it.isFile }.readText()

    private fun eligible(
        enabled: Boolean = true, bgPlay: Boolean = true, visible: Boolean = false,
        pip: Boolean = false, car: Boolean = false, broken: Boolean = false,
    ) = AudioOnlyPolicy.eligible(enabled, bgPlay, visible, pip, car, broken)

    @Test fun onlyWhenNothingCanShowTheVideo() {
        assertTrue(eligible())
        assertFalse("app on screen", eligible(visible = true))
        assertFalse("PiP shows the video", eligible(pip = true))
        assertFalse("Android Auto shows the video", eligible(car = true))
        assertFalse("setting off", eligible(enabled = false))
        assertFalse("background play off", eligible(bgPlay = false))
        assertFalse("circuit breaker tripped", eligible(broken = true))
    }

    @Test fun circuitBreakerOnlyRightAfterSwitching() {
        assertTrue(AudioOnlyPolicy.shouldBreak(true, 5_000L))
        assertTrue(AudioOnlyPolicy.shouldBreak(true, AudioOnlyPolicy.BREAK_WINDOW_MS))
        assertFalse(AudioOnlyPolicy.shouldBreak(true, AudioOnlyPolicy.BREAK_WINDOW_MS + 1))
        assertFalse("video mode: nothing to break", AudioOnlyPolicy.shouldBreak(false, 1_000L))
        assertEquals("switches the moment the app leaves the screen", 0L, AudioOnlyPolicy.DELAY_MS)
    }

    @Test fun defaultOnAndPersisted() {
        assertTrue(FeatureFlags().audioOnlyBackground)
        val f = src("Features.kt")
        assertTrue(f.contains("prefs.getBoolean(\"audioOnlyBg\", d.audioOnlyBackground)"))
        assertTrue(f.contains(".putBoolean(\"audioOnlyBg\", n.audioOnlyBackground)"))
        assertTrue(src("Screens.kt").contains("Toggle(\"Audio only in background\""))
    }

    @Test fun webViewNeverForwardsAndroidVisibility() {
        val w = src("BackgroundWebView.kt")
        val m = w.substringAfter("override fun onWindowVisibilityChanged(visibility: Int) {").substringBefore("\n    }")
        assertFalse(m.contains("super.onWindowVisibilityChanged(visibility)"))
        assertTrue(m.contains("if (audioOnly) super.onWindowVisibilityChanged(View.GONE)"))
        assertTrue(w.contains("fun setAudioOnly(on: Boolean)"))
    }

    @Test fun hostSwitchesOffAtOnceAndOnAfterDelay() {
        val h = src("WebHost.kt")
        val r = h.substringAfter("fun refreshAudioOnly(now: Boolean = false) {").substringBefore("/** Circuit breaker")
        assertTrue(r.indexOf("if (!audioOnlyEligible())") < r.indexOf("w.setAudioOnly(true)"))
        assertTrue(r.contains("if (w.audioOnly) w.setAudioOnly(false)"))
        // Every visibility/PiP/car transition already calls refreshBackgroundQuality().
        assertTrue(h.substringAfter("fun refreshBackgroundQuality() {").substringBefore("\n    }").contains("refreshAudioOnly()"))
        val drop = h.substringAfter("private fun resumeIfDropped(").substringBefore("unexpectedPauseRecoveryUntil = now + 120_000L")
        assertTrue("an unexpected drop restores video", drop.contains("audioOnlyDropped(now)"))
        assertTrue("a user pause never trips it", drop.indexOf("userPausedAt <= 5_000L") < drop.indexOf("audioOnlyDropped(now)"))
    }
}
