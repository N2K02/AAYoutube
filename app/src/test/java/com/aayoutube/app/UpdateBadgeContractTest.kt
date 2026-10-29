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
        val header = src.substringAfter("private fun AAYoutubePageHeader(")
            .substringBefore("fun AppRoot(")
        assertTrue(header.contains("FilledTonalIconButton(onClick = onSettings)"))
        assertTrue(header.contains("N2K02UpdateBadgedIcon(updateStatus)"))
        val settings = src.substringAfter("private fun AAYoutubeSettingsMenu(")
            .substringBefore("// ---------------------------------------------------------------------------- features")
        assertTrue(settings.contains("if (destination == Tab.Update)"))
        assertTrue(settings.contains("N2K02UpdateBadge(updateStatus)"))
    }
}
