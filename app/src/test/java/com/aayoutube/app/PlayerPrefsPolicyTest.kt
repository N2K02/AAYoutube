package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPrefsPolicyTest {
    @Test fun valuesAreNormalizedToSupportedOptions() {
        assertEquals(1.25f, PlayerPrefsPolicy.normalizeSpeed(1.3f), 0f)
        assertEquals(2f, PlayerPrefsPolicy.normalizeSpeed(9f), 0f)
        assertEquals(1f, PlayerPrefsPolicy.normalizeSpeed(Float.NaN), 0f)
        assertEquals(10, PlayerPrefsPolicy.normalizeSeek(7))
        assertEquals(30, PlayerPrefsPolicy.normalizeSeek(30))
        assertEquals("default", PlayerPrefsPolicy.normalizeCaptions("weird"))
        assertEquals("off", PlayerPrefsPolicy.normalizeCaptions("off"))
        assertEquals("1x", PlayerPrefsPolicy.speedLabel(1f))
        assertEquals("1.25x", PlayerPrefsPolicy.speedLabel(1.25f))
    }

    @Test fun defaultsLeaveYouTubeBehaviourUntouched() {
        val d = FeatureFlags()
        assertEquals(1f, d.playbackSpeed, 0f)
        assertEquals(10, d.seekSeconds)
        assertEquals("default", d.captions)
        assertFalse(d.loopVideo || d.hideComments || d.hideEndCards)
    }

    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun optionsAreStoredSentToThePageAndApplied() {
        val features = src("app/src/main/java/com/aayoutube/app/Features.kt")
        val host = src("app/src/main/java/com/aayoutube/app/WebHost.kt")
        val ui = src("app/src/main/java/com/aayoutube/app/Screens.kt")
        val js = src("app/src/main/assets/aay.js")
        listOf("speed", "rememberSpeed", "loop", "captions", "seekSeconds", "hideComments", "hideEndCards")
            .forEach { assertTrue(it, features.contains(".put(\"$it\"")) }
        listOf("playbackSpeed", "rememberSpeed", "loopVideo", "captions", "seekSeconds", "hideComments", "hideEndCards")
            .forEach { assertTrue(it, features.contains("(\"$it\"")) }
        assertTrue(host.contains("fun requestSpeedFromBridge(rate: Double, generation: Int)"))
        assertTrue(host.contains("!Features.current.rememberSpeed"))
        assertTrue(ui.contains("Choice(\"Playback speed\""))
        assertTrue(ui.contains("Choice(\"Double-tap seek\""))
        assertTrue(js.contains("function applyPlayerPrefs()"))
        assertTrue(js.contains("if (secs === 10 ||"))
        assertTrue(js.contains("now - userTouchAt > 2500"))
        assertTrue(js.contains("if (CFG.hideComments) css += CSS_COMMENTS;"))
        // Smooth mode changes fade; the WebView size is never animated per frame.
        assertTrue(ui.contains("fade.animateTo(1f, tween(240))"))
        assertTrue(ui.contains("playerMod.graphicsLayer { alpha = fade.value }"))
    }
}
