package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LegacyProjectionControlsContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun projectionHasCenteredNavigationAndSeparateVideoArea() {
        val code = source("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        // Floating, centred, icons-only pill shared with the phone.
        assertTrue(code.contains("FloatingVideoBar("))
        assertTrue(code.contains("car = true,"))
        assertTrue(code.contains("Modifier.align(ControlsSidePolicy.alignment(flags.controlsOnLeft))"))
        assertFalse("no text labels on the car controls", code.contains("CarControl("))
        assertTrue(code.contains("Modifier.fillMaxSize().background(Color.Black)"))
        assertTrue(code.contains("TopMenuPolicy.timeoutMillis(flags.topMenuTimeoutSeconds)"))
        assertTrue(code.contains("delay(timeout)"))
        assertTrue(code.contains("AnimatedVisibility("))
        assertTrue(code.contains(".revealControlsOnTouch { controlsVisible = true; controlsVersion++ }"))
        assertFalse(code.contains("            }\n            }\n            if (!controlsVisible) {"))
        assertTrue(code.contains("controlsVisible = true"))
        assertTrue(code.contains("onBack = { carBackToMini(); controlsVersion++ }"))
        assertTrue(code.contains("onHome = { carHomeToMini(); controlsVersion++ }"))
        assertTrue(code.contains("WebHost.enterMini()"))
        assertTrue(code.contains("BrowseHost.goBack()"))
        assertTrue(code.contains("if (mini && browsing) key(browseRevision)"))
        assertTrue(code.contains(".padding(horizontal = 12.dp).zIndex(20f)"))
        assertTrue(code.contains("detectDragGestures(onDragEnd"))
        assertTrue(code.contains("val miniVideoModifier = Modifier.offset {"))
        assertTrue(code.contains("override fun onBackPressed()"))
        assertTrue(code.contains("onPrevious = { WebHost.cmd(\"prev\"); controlsVersion++ }"))
        assertTrue(code.contains("onNext = { WebHost.cmd(\"next\"); controlsVersion++ }"))
        assertTrue(code.contains("WebHost.cmd(if (playing.playing) \"pause\" else \"play\")"))
    }

    @Test fun videoAndMusicAreIndependentCarServices() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:name=\".LegacyProjectionService\""))
        assertTrue(manifest.contains("android:name=\".PlaybackService\""))
        assertTrue(manifest.contains("android:label=\"AAYT Video\""))
        assertTrue(manifest.contains("android:label=\"AAYT Music\""))
        assertTrue(manifest.contains("android:label=\"AAYoutube\""))
        assertFalse(manifest.contains("android:name=\".CarVideoService\""))
    }

    @Test fun phoneAndCarShareFloatingIconOnlyBar() {
        val bar = source("app/src/main/java/com/aayoutube/app/FloatingVideoBar.kt")
        assertTrue(bar.contains("internal fun FloatingVideoBar("))
        assertTrue(bar.contains("shape = RoundedCornerShape(50)"))
        assertFalse("icons only", bar.contains("Text("))
        for (d in listOf("\"Back\"", "\"YouTube home\"", "\"Pause\"", "\"Play\"", "\"Previous video\"", "\"Next video\""))
            assertTrue("accessible description $d", bar.contains(d))
        assertTrue(bar.contains("val Car = BarSize(button = 52.dp, icon = 26.dp)"))
        assertTrue(bar.contains("vertical: Boolean = true"))
        val phone = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        val watch = phone.substringAfter("// App-owned watch header overlays the WebView").substringBefore("if (lyricsOnScreen) {")
        assertTrue(watch.contains("FloatingVideoBar("))
        assertFalse("watch overlay no longer shows the text header", watch.contains("AAYoutubePageHeader("))
        assertTrue(watch.contains("Icons.Filled.MusicNote"))
        assertTrue(watch.contains("enabled = canLyrics,"))
        assertTrue(watch.contains("BarIcon(Icons.Filled.Settings, \"Settings\""))
        assertTrue(watch.contains("N2K02UpdateBadge("))
        assertTrue(watch.contains("onHome = { goYouTubeHome(); topMenuEpoch++ }"))
        assertTrue(watch.contains("onPrevious = { WebHost.cmd(\"prev\"); topMenuEpoch++ }"))
        assertTrue(phone.contains(".padding(horizontal = 8.dp).zIndex(20f)"))
        // Settings keeps its one standard header inside an overlay. The
        // playing WebView must not move when switching from Watch to Settings.
        val root = phone.substringAfter("fun AppRoot(").substringBefore("// ---------------------------------------------------------------------------- web")
        assertTrue(root.contains("WebPane(Modifier.fillMaxSize())"))
        assertTrue(root.contains("visible = tab != Tab.Watch.ordinal && !inPip"))
        assertTrue(root.contains("AAYoutubePageHeader("))
        assertTrue(root.contains("Crossfade("))
        assertFalse(root.contains("Scaffold("))
        assertFalse("Settings has no Home bottom bar any more", phone.contains("onHome = goYouTubeHome,"))
    }
}
