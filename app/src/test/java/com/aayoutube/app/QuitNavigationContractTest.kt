package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quit lives on the floating video rail (phone and Android Auto), not in Settings. */
class QuitNavigationContractTest {
    private fun src(p: String) = listOf(File(p), File("../$p")).first { it.isFile }.readText()

    @Test fun quitIsOnBothFloatingRails() {
        val phone = src("app/src/main/java/com/aayoutube/app/Screens.kt")
        val rail = phone.substringAfter("// App-owned watch header overlays the WebView").substringBefore("if (lyricsOnScreen) {")
        assertTrue(rail.contains("BarIcon(Icons.Rounded.PowerSettingsNew, \"Quit AAYoutube\", BarSize.Phone,"))
        assertTrue(rail.contains("onClick = { activity.quit() })"))
        val car = src("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        assertTrue(car.contains("Icons.Rounded.PowerSettingsNew,"))
        assertTrue(car.contains("onClick = { quitFromCar() },"))
        val quit = car.substringAfter("private fun quitFromCar() {").substringBefore("    /**")
        // Same clean shutdown as the phone: media process first, intent fallback.
        assertTrue(quit.contains("mediaController?.command(Commands.EXIT)"))
        assertTrue(quit.contains(".setAction(PlaybackService.ACTION_QUIT)"))
        val main = src("app/src/main/java/com/aayoutube/app/MainActivity.kt")
        assertTrue(main.contains("compatConnection?.command(Commands.EXIT)"))
        assertTrue(main.contains("PlaybackService.ACTION_QUIT"))
        assertTrue(main.contains("private val quitPhone: () -> Unit"))
        assertTrue(main.contains("if (WebHost.onQuit === quitPhone) WebHost.onQuit = null"))
        val web = src("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("val close = onQuit ?: onCarQuit"))
        assertTrue(car.contains("WebHost.onCarQuit = carQuitCallback"))
        assertTrue(car.contains("private fun shutdownCarOnlySession()"))
        assertTrue(car.contains("WebHost.release()"))
        assertTrue(car.contains("android.os.Process.killProcess(android.os.Process.myPid())"))
        assertTrue(car.contains("if (WebHost.onCarQuit === carQuitCallback) WebHost.onCarQuit = null"))
    }

    @Test fun settingsHasNoBottomBarAndHeaderHasNoGear() {
        val phone = src("app/src/main/java/com/aayoutube/app/Screens.kt")
        val menu = phone.substringAfter("private fun AAYoutubeSettingsMenu(")
            .substringBefore("// ---------------------------------------------------------------------------- features")
        assertFalse(menu.contains("NavigationBar("))
        assertFalse(menu.contains("Triple(\"Quit\""))
        assertTrue(menu.contains("listOf(Tab.Features, Tab.Lyrics, Tab.Info, Tab.Update)"))
        val header = phone.substringAfter("private fun AAYoutubePageHeader(").substringBefore("fun AppRoot(")
        assertFalse("no settings gear in the page header", header.contains("Icons.Filled.Settings"))
        assertFalse(header.contains("onSettings"))
        assertFalse(header.contains("N2K02UpdateBadge("))
        assertTrue(header.contains("N2K02AppIcon"))
        assertTrue(header.contains("\"AAYoutube\""))
    }
}
