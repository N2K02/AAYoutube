package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protect the Settings navigation actions and Quit cleanup hook. */
class QuitNavigationContractTest {
    @Test fun quitRemainsAvailableInsideSettings() {
        val relative = "app/src/main/java/com/aayoutube/app/Screens.kt"
        val source = listOf(File(relative), File("../$relative"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
        val menu = source.substringAfter("private fun AAYoutubeSettingsMenu(")
            .substringBefore("// ---------------------------------------------------------------------------- features")
        assertTrue(menu.contains("Triple(\"Quit\", Icons.Rounded.PowerSettingsNew, onQuit)"))
        assertTrue(menu.contains("Triple(\"Back\", Icons.AutoMirrored.Filled.ArrowBack, onBack)"))
        assertTrue(menu.contains("Triple(\"Home\", Icons.Filled.PlayArrow, onHome)"))
        assertTrue(source.contains("onQuit = { activity.quit() }"))
    }
}
