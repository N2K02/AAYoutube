package com.aayoutube.app
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class NoAppLogsContractTest {
    @Test fun noPersistentLogViewer() {
        val p="app/src/main/java/com/aayoutube/app/Screens.kt"
        val ui=listOf(File(p), File("../$p")).first { it.isFile }.readText()
        assertFalse(ui.contains("fun LogsPage()"))
        assertFalse(ui.contains("Tab.Logs"))
        assertTrue(ui.contains("Tab.Lyrics"))
    }
}
