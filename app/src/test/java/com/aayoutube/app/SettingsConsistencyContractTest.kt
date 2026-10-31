package com.aayoutube.app
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class SettingsConsistencyContractTest {
    private fun src(p: String) = listOf(File(p), File("../$p")).first { it.isFile }.readText()
    @Test fun settingsHaveLyricsAndUpdatesWithoutInAppLogs() {
        val ui = src("app/src/main/java/com/aayoutube/app/Screens.kt")
        assertTrue(ui.contains("listOf(Tab.Features, Tab.Lyrics, Tab.Info, Tab.Update)"))
        assertTrue(ui.contains("if (destination == Tab.Update) HorizontalDivider()"))
        assertFalse(ui.contains("Tab.Logs"))
    }
    @Test fun logcatRemainsAvailableAcrossProcesses() {
        val app = src("app/src/main/java/com/aayoutube/app/AAYoutubeApp.kt")
        val log = src("app/src/main/java/com/aayoutube/app/AppLog.kt")
        assertTrue(app.contains("AppLog.init(this)"))
        assertTrue(log.contains("Log.i(\"AAYouTube-CAR\""))
        assertFalse(log.contains("appendText("))
        assertFalse(src("app/src/main/AndroidManifest.xml").contains("CrashJournalProvider"))
    }
}
