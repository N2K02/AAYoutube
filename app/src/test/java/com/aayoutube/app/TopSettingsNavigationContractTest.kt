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

    @Test fun headerHasNoGearAndTheMainScreenHasNoAppBottomBar() {
        val text = source()
        val header = text.substringAfter("private fun AAYoutubePageHeader(")
            .substringBefore("fun AppRoot(")
        val root = text.substringAfter("fun AppRoot(")
            .substringBefore("// ---------------------------------------------------------------------------- web")
        assertFalse(header.contains("FilledTonalIconButton(onClick = onSettings)"))
        assertTrue(header.contains("N2K02AppIcon"))
        assertFalse(root.contains("NavigationBar("))
        assertFalse(root.contains("NavigationBarItem("))
        assertFalse(root.contains("ModalBottomSheet("))
        assertFalse(root.contains("BottomCenter).fillMaxWidth()"))
        assertFalse(text.contains("private fun MenuButton("))
        assertFalse(text.contains("private fun MenuItem("))
        assertFalse(text.contains("MiniPlace.rect"))
        assertFalse(text.contains("Fade the floating Back button"))
        assertFalse(text.contains("autoHideMenu"))
    }

    @Test fun settingsKeepItsSubmenusWithoutABottomBar() {
        val text = source()
        val settings = text.substringAfter("private fun AAYoutubeSettingsMenu(")
            .substringBefore("// ---------------------------------------------------------------------------- features")
        assertTrue(settings.contains("listOf(Tab.Features, Tab.Lyrics, Tab.Info, Tab.Update)"))
        assertTrue(settings.contains("Modifier.weight(1f).fillMaxWidth().verticalScroll"))
        assertFalse(settings.contains("NavigationBar("))
        assertFalse(settings.contains("NavigationBarItem("))
        assertTrue(text.contains("BackHandler(enabled = true) { goBack() }"))
    }
}
