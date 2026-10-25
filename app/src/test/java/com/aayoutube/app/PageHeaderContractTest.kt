package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lightweight source-level regression guard for the mRadio header contract.
 * Visual layout and playback behavior still require Android UI validation.
 */
class PageHeaderContractTest {
    private fun source(): String {
        val relative = "com/aayoutube/app/Screens.kt"
        return listOf(File("src/main/java", relative), File("app/src/main/java", relative))
            .firstOrNull { it.isFile }?.readText()
            ?: error("Missing app UI source: $relative")
    }

    @Test fun pageHeaderHasLeadingIconAndRightSideAppName() {
        val text = source()
        assertTrue(text.contains("private fun AAYoutubePageHeader("))
        val header = text.substringAfter("private fun AAYoutubePageHeader(")
            .substringBefore("fun AppRoot(")
        assertTrue("Missing page icon in header", header.contains("tab.icon"))
        assertTrue("Missing application name in header", header.contains("\"AAYoutube\""))
    }

    @Test fun appTitleIsNotProvidedBySharedUpdater() {
        val header = source().substringAfter("private fun AAYoutubePageHeader(")
            .substringBefore("fun AppRoot(")
        assertFalse("App header should not duplicate updater title", header.contains("N2K02UpdateTitle("))
    }
}
