package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Header-only lyrics button: source contract; UI/audio playback still needs device checks. */
class LyricsHeaderContractTest {
    private fun read(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun lyricsIconPrecedesSettingsInSingleHeader() {
        val ui = read("app/src/main/java/com/aayoutube/app/Screens.kt")
        val header = ui.substringAfter("private fun AAYoutubePageHeader(").substringBefore("fun AppRoot(")
        assertTrue(header.contains("Icons.Filled.MusicNote"))
        assertTrue(header.contains("enabled = lyricsAvailable"))
        assertTrue(header.contains("onClick = onLyrics"))
        assertTrue(header.indexOf("onClick = onLyrics") < header.indexOf("onClick = onSettings"))
        assertTrue(header.contains("contentDescription = if (lyricsAvailable)"))
        assertFalse(ui.contains("LyricsFloatingButton("))
    }

    @Test fun lookupRunsWithoutShowingOverlayAndOnlyEnablesAfterResult() {
        val ui = read("app/src/main/java/com/aayoutube/app/Screens.kt")
        val root = ui.substringAfter("fun AppRoot(").substringBefore("// ---------------------------------------------------------------------------- web")
        assertTrue(root.contains("LyricsProviders.fetch(context, snap)"))
        assertTrue(root.contains("val lyricsAvailable = lyricsEnabled && !snap.live && !snap.ad"))
        assertTrue(root.contains("foundLyrics.foundFor(lyricsKey)"))
        assertTrue(root.contains("lyricsOnScreen = lyricsAvailable && lyricsShown"))
        assertTrue(root.contains("playerMode == WebHost.PlayerMode.FULL"))
        assertTrue(root.contains("LyricsPrefs.set(context, \"show\", !lyricsShown)"))
        val overlay = read("app/src/main/java/com/aayoutube/app/Lyrics.kt")
        assertFalse(overlay.substringAfter("internal fun LyricsOverlay(").contains("LyricsProviders.fetch(context, snap)"))
        assertFalse(overlay.contains("internal fun LyricsFloatingButton("))
        assertTrue(root.contains("WebHost.setLyricsTracking(lyricsOnScreen && foundLyrics.lines.isNotEmpty())"))
        assertTrue(root.contains("onDispose { WebHost.setLyricsTracking(false) }"))
        assertTrue(overlay.contains("latestClock.positionFor(snap.id, tickAt, offsetMs)"))
        assertTrue(overlay.contains("LaunchedEffect(snap.id, offsetMs)"))
        assertTrue(overlay.contains("Crossfade(targetState = index"))
    }

    @Test fun browserDoesNotInjectAnotherSettingsOrMenuEntry() {
        val js = read("app/src/main/assets/aay.js")
        assertFalse(js.contains("installHtmlMenu"))
        assertFalse(js.contains("aay-html-menu"))
        assertFalse(js.contains("persistentNavigation"))
        assertFalse(js.contains("openMenu()"))
        val web = read("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertFalse(web.contains("htmlMenuTap"))
    }

    @Test fun onlyResolvedLyricsEnableTheIcon() {
        val key = "video|track|artist"
        assertFalse(LyricsContent(key).foundFor(key))
        assertFalse(LyricsContent("other", listOf(LyricLine(0, "lyric"))).foundFor(key))
        assertFalse(LyricsContent(key, plain = "  ").foundFor(key))
        assertTrue(LyricsContent(key, listOf(LyricLine(0, "lyric"))).foundFor(key))
        assertTrue(LyricsContent(key, plain = "plain lyric").foundFor(key))
    }
}
