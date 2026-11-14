package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Playback quality is persistent; UI transitions must not relayout the decoder. */
class PlaybackQualityAndTransitionTest {
    private fun read(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun defaultsTo720pAndRejectsInvalidPreferences() {
        assertEquals("hd720", VideoQualityPolicy.normalize(null))
        assertEquals("hd720", VideoQualityPolicy.normalize("144p"))
        assertEquals("hd720", VideoQualityPolicy.normalize("unknown"))
        assertEquals("hd1080", VideoQualityPolicy.normalize("hd1080"))
        assertEquals("auto", VideoQualityPolicy.normalize("auto"))
        assertEquals("tiny", VideoQualityPolicy.normalize("tiny"))
        assertFalse(VideoQualityPolicy.isSupported("javascript:evil"))
    }

    @Test fun nativePreferencesAndBridgeCarryAnExplicitChoice() {
        val flags = read("app/src/main/java/com/aayoutube/app/Features.kt")
        assertTrue(flags.contains("val preferredQuality: String = \"hd720\""))
        assertTrue(flags.contains(".put(\"preferredQuality\", preferredQuality)"))
        assertTrue(flags.contains(".putString(\"preferredQuality\", n.preferredQuality)"))
        assertTrue(flags.contains("prefs.getString(\"preferredQuality\", d.preferredQuality)"))
        assertTrue(flags.contains("qualityPolicyV3"))
        assertTrue(flags.contains("val lowQualityInBackground: Boolean = true"))
        val web = read("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("fun quality(value: String)"))
        assertTrue(web.contains("generation != webGeneration"))
        assertTrue(web.contains("Features.update { it.copy(preferredQuality = quality) }"))
    }

    @Test fun settingsCrossfadeWithoutResizingThePlayingWebView() {
        val ui = read("app/src/main/java/com/aayoutube/app/Screens.kt")
        val root = ui.substringAfter("fun AppRoot(").substringBefore("// ---------------------------------------------------------------------------- web")
        assertFalse("Scaffold header reflows WebPane", root.contains("Scaffold("))
        assertTrue(root.contains("WebPane(Modifier.fillMaxSize())"))
        assertTrue(root.contains("AnimatedVisibility("))
        assertTrue(root.contains("Crossfade("))
        assertTrue(root.contains("AAYoutubePageHeader("))
        assertTrue(root.contains("lastPanel"))
        val pane = ui.substringAfter("fun WebPane(").substringBefore("// ---------------------------------------------------------------------------- settings")
        assertTrue(pane.contains("key(version)"))
        assertTrue(pane.contains("}.size(miniW, miniH).clip(RoundedCornerShape(12.dp))"))
        assertFalse(pane.contains("scaleX = 1f +"))
    }

    @Test fun pauseInterceptionAvoidsPerCallExceptionStackTraces() {
        val js = read("app/src/main/assets/aay.js")
        val paused = js.substringAfter("HTMLMediaElement.prototype.pause = function ()")
            .substringBefore("window.addEventListener('pause'")
        assertFalse(paused.contains("new Error().stack"))
        assertTrue(paused.contains("ignored % 250"))
        assertTrue(js.contains("qualityApply.player === p && qualityApply.key === key"))
        assertTrue(js.contains("if (BROWSE) return"))
        assertTrue(js.contains("CFG.preferredQuality = q"))
    }
}
