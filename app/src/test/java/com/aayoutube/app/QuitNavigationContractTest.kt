package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protect Quit access without changing this app's established navigation style. */
class QuitNavigationContractTest {
    @Test fun quitRemainsAvailableAndStopsTheApp() {
        val relative = "app/src/main/java/com/aayoutube/app/Screens.kt"
        val source = listOf(File(relative), File("../$relative"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
        assertTrue(source.contains("MenuItem(\"Quit\""))
        assertTrue(source.contains("activity.quit()"))
        assertTrue(source.contains("Text(\"Quit\")"))
        assertTrue(source.contains("onQuit = { menuOpen = false; activity.quit() }"))
    }
}
