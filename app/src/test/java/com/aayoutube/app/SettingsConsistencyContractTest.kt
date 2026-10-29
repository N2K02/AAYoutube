package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protect the mRadio-inspired Settings grouping while retaining app-specific pages. */
class SettingsConsistencyContractTest {
    @Test fun settingsRowsAndUpdatesFollowConsistentOrder() {
        val relative = "app/src/main/java/com/aayoutube/app/Screens.kt"
        val source = listOf(File(relative), File("../$relative"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
        val menu = source.substringAfter("private fun AAYoutubeSettingsMenu(", "")
        assertTrue("Settings section missing", menu.isNotBlank())
        assertTrue("Settings group title is missing", menu.contains("Make watching your own"))
        assertTrue("Lyrics must remain a Settings submenu", menu.contains("listOf(Tab.Features, Tab.Lyrics, Tab.Info, Tab.Logs, Tab.Update)"))
        assertTrue("Updates or diagnostic footer missing", menu.contains("if (destination == Tab.Logs) HorizontalDivider()"))
        assertTrue("Diagnostics must be after ordinary Settings links", menu.contains("listOf(Tab.Features, Tab.Lyrics, Tab.Info, Tab.Logs, Tab.Update)"))
        assertTrue("Divider must appear immediately before the diagnostics and update group", menu.contains("if (destination == Tab.Logs) HorizontalDivider()"))
    }
}
