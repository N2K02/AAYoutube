package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone Settings, car menu and About & updates use the same updater state. */
class UpdateBadgeContractTest {
    @Test fun settingsAndAboutUpdatesShowBadgeWithoutDuplicateNavigation() {
        val path = "app/src/main/java/com/aayoutube/app/Screens.kt"
        val src = listOf(File(path), File("../$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")
        val phoneMenu = src.substringAfter("if (menuOpen) {")
            .substringBefore("Spacer(Modifier.height(24.dp))")
        assertTrue(phoneMenu.contains("if (item == Tab.Settings)"))
        assertFalse(phoneMenu.contains("if (item == Tab.Update)"))
        assertTrue(phoneMenu.contains("N2K02UpdateBadge(sharedUpdateStatus)"))
        assertTrue(src.contains("badge = t == Tab.Settings && updateAvailable"))
        val settings = src.substringAfter("private fun AAYoutubeSettingsMenu(")
            .substringBefore("// ---------------------------------------------------------------------------- features")
        assertTrue(settings.contains("if (destination == Tab.Update)"))
        assertTrue(settings.contains("N2K02UpdateBadge(updateStatus)"))
    }
}
