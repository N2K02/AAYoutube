package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Updates are badged in the persistent Settings shortcut and on the Update row. */
class UpdateBadgeContractTest {
    @Test fun headerAndSettingsShareUpdaterStatus() {
        val path = "app/src/main/java/com/aayoutube/app/Screens.kt"
        val src = listOf(File(path), File("../$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")
        // The badge moved from the removed header gear to the video rail's Settings icon.
        val rail = src.substringAfter("// App-owned watch header overlays the WebView").substringBefore("if (lyricsOnScreen) {")
        assertTrue(rail.contains("N2K02UpdateBadge("))
        assertTrue(rail.contains("modifier = Modifier.align(Alignment.TopEnd)"))
        assertTrue(!src.contains("N2K02UpdateBadgedIcon(updateStatus)"))
        val settings = src.substringAfter("private fun AAYoutubeSettingsMenu(")
            .substringBefore("// ---------------------------------------------------------------------------- features")
        assertTrue(settings.contains("if (destination == Tab.Update)"))
        assertTrue(settings.contains("N2K02UpdateBadge(updateStatus)"))
    }
}
