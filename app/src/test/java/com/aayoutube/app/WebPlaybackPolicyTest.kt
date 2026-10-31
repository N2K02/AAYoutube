package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * AABrowser parity is unconditional. No projection/PiP/background setting
 * branch is permitted in the primary WebView's automatic lifecycle path.
 */
class WebPlaybackPolicyTest {
    private fun read(file: String): String =
        sequenceOf(File("app/src/main/java/com/aayoutube/app/$file"),
            File("../app/src/main/java/com/aayoutube/app/$file"))
            .first { it.isFile }.readText()

    @Test fun primaryWebViewIgnoresEveryAutomaticPause() {
        val src = read("BackgroundWebView.kt")
        val method = src.substringAfter("override fun onPause() {").substringBefore("\n    }")
        assertFalse(method.contains("super.onPause()"))
        assertFalse(method.contains("Features.current"))
        assertFalse(method.contains("WebHost."))
        assertFalse(method.contains("if ("))
        assertTrue(src.contains("fun pauseForIdle() = super.onPause()"))
    }

    @Test fun primaryWebViewAlwaysReportsVisibleEvenWhenAndroidHidesIt() {
        val src = read("BackgroundWebView.kt")
        val method = src.substringAfter("override fun onWindowVisibilityChanged(visibility: Int) {")
            .substringBefore("\n    }")
        assertTrue(method.contains("super.onWindowVisibilityChanged(View.VISIBLE)"))
        assertFalse(method.contains("super.onWindowVisibilityChanged(visibility)"))
        assertFalse(method.contains("if (visibility != View.VISIBLE"))
        assertFalse(method.contains("Features.current"))
        assertFalse(method.contains("WebHost."))
    }

    @Test fun deliberateIdleShutdownAndExplicitVideoPauseRemainAvailable() {
        val web = read("WebHost.kt")
        assertTrue(web.contains("(w as? BackgroundWebView)?.pauseForIdle()"))
        assertTrue(web.contains("w.pauseTimers()"))
        assertTrue(web.contains("if (!shown && !snapshot.value.playing) freeze()"))
        assertTrue(web.contains("if (name == \"pause\") userPausedAt"))
        assertTrue(web.contains("cmd(\"pause\")"))
        assertTrue(web.contains("w?.destroy()"))
    }
}
