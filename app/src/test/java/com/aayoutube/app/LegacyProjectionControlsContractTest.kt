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
        assertTrue(code.contains("large = true,"))
        assertTrue(code.contains("modifier = Modifier.align(Alignment.TopCenter),"))
        assertFalse("no text labels on the car controls", code.contains("CarControl("))
        assertTrue(code.contains("Box(Modifier.fillMaxSize().background(Color.Black))"))
        assertTrue(code.contains("TopMenuPolicy.timeoutMillis(flags.topMenuTimeoutSeconds)"))
        assertTrue(code.contains("delay(timeout)"))
        assertTrue(code.contains("AnimatedVisibility("))
        assertTrue(code.contains("            if (!controlsVisible) {"))
        assertTrue(code.contains(".revealTopMenuOnSwipeDown { controlsVisible = true; controlsVersion++ }"))
        assertFalse(code.contains("            }\n            }\n            if (!controlsVisible) {"))
        assertTrue(code.contains("controlsVisible = true"))
        assertTrue(code.contains("height(28.dp)"))
        assertTrue(code.contains("onBack = { WebHost.goBack(); controlsVersion++ }"))
        assertTrue(code.contains("onHome = { WebHost.openUrl(\"https://m.youtube.com/\"); controlsVersion++ }"))
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
        for (d in listOf("\"Back\"", "\"YouTube home\"", "\"Pause\"", "\"Play\"", "\"Next video\""))
            assertTrue("accessible description $d", bar.contains(d))
        assertTrue(bar.contains("val button = if (large) 60.dp else 48.dp"))
        val phone = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        val watch = phone.substringAfter("// App-owned watch header overlays the WebView").substringBefore("if (lyricsOnScreen) {")
        assertTrue(watch.contains("FloatingVideoBar("))
        assertFalse("watch overlay no longer shows the text header", watch.contains("AAYoutubePageHeader("))
        assertTrue(watch.contains("Icons.Filled.MusicNote"))
        assertTrue(watch.contains("enabled = canLyrics,"))
        assertTrue(watch.contains("BarIcon(Icons.Filled.Settings, \"Settings\""))
        assertTrue(watch.contains("N2K02UpdateBadge("))
        assertTrue(watch.contains("onHome = { goYouTubeHome(); topMenuEpoch++ }"))
        // Non-video pages keep the single standard header.
        assertTrue(phone.contains("if (!watchTab && headerAllowed) {"))
        assertTrue(phone.contains("onHome = goYouTubeHome,"))
    }
}
