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
        assertTrue(code.contains("horizontalArrangement = Arrangement.Center"))
        assertTrue(code.contains("height(64.dp)"))
        assertTrue(code.contains("Box(Modifier.fillMaxSize().background(Color.Black))"))
        assertTrue(code.contains("TopMenuPolicy.timeoutMillis(flags.topMenuTimeoutSeconds)"))
        assertTrue(code.contains("delay(timeout)"))
        assertTrue(code.contains("AnimatedVisibility("))
        assertTrue(code.contains("            if (!controlsVisible) {"))
        assertTrue(code.contains(".revealTopMenuOnSwipeDown { controlsVisible = true; controlsVersion++ }"))
        assertFalse(code.contains("            }\n            }\n            if (!controlsVisible) {"))
        assertTrue(code.contains("controlsVisible = true"))
        assertTrue(code.contains("height(28.dp)"))
        assertTrue(code.contains("CarControl(\"‹ Back\")"))
        assertTrue(code.contains("CarControl(\"⌂ Home\")"))
        assertTrue(code.contains("CarControl(\"⏭ Next\")"))
        assertTrue(code.contains("WebHost.cmd(if (playing.playing) \"pause\" else \"play\")"))
    }

    @Test fun videoAndMusicAreIndependentCarServices() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:name=\".LegacyProjectionService\""))
        assertTrue(manifest.contains("android:name=\".PlaybackService\""))
        assertTrue(manifest.contains("android:label=\"AAYouTube Music\""))
        assertFalse(manifest.contains("android:name=\".CarVideoService\""))
    }
}
