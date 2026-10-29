package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Avoid regressions that reintroduce duplicate phone app navigation bars or menus. */
class TopSettingsNavigationContractTest {
    private fun source(): String {
        val relative = "app/src/main/java/com/aayoutube/app/Screens.kt"
        return listOf(File(relative), File("../$relative"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
    }

    @Test fun headerOwnsSettingsAndThePhoneHasNoAppBottomBar() {
        val text = source()
        val header = text.substringAfter("private fun AAYoutubePageHeader(")
            .substringBefore("fun AppRoot(")
        val root = text.substringAfter("fun AppRoot(")
            .substringBefore("// ---------------------------------------------------------------------------- menu button")
        assertTrue(header.contains("FilledTonalIconButton(onClick = onSettings)"))
        assertTrue(header.contains("N2K02AppIcon"))
        assertTrue(root.contains("onSettings = {"))
        assertFalse(root.contains("NavigationBar("))
        assertFalse(root.contains("NavigationBarItem("))
        assertFalse(root.contains("ModalBottomSheet("))
        assertFalse(root.contains("BottomCenter).fillMaxWidth()"))
    }

    @Test fun homeBackQuitAreInsideExistingSettingsNotInAnotherMenu() {
        val text = source()
        val settings = text.substringAfter("private fun AAYoutubeSettingsMenu(")
            .substringBefore("// ---------------------------------------------------------------------------- features")
        assertTrue(settings.contains("listOf(Tab.Features, Tab.Lyrics, Tab.Info, Tab.Logs, Tab.Update)"))
        assertTrue(settings.contains("Triple(\"Back\","))
        assertTrue(settings.contains("Triple(\"Home\","))
        assertTrue(settings.contains("Triple(\"Quit\","))
        assertTrue(text.contains("onBack = goBack"))
        assertTrue(text.contains("onQuit = { activity.quit() }"))
    }
}
