package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Regression tests for background playback, quality changes and media-process lifecycle. */
class BackgroundQualityRecoveryContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun unexpectedBackgroundPauseRetriesAreBoundedAndRespectExplicitPause() {
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("val background = !shown && !inPip.value"))
        assertTrue(web.contains("now - userPausedAt <= 5_000L"))
        assertTrue(web.contains("resumeTries >= 2"))
        assertTrue(web.contains("now - resumeWindowAt > 60_000L"))
        assertTrue(web.contains("cmd(\"play\")"))
        assertTrue(web.contains("unexpectedPauseRecoveryUntil = now + 120_000L"))
        assertTrue(web.contains("main.postDelayed(freezeSoon, maxOf(45_000L, recoveryMs))"))
        assertTrue(web.contains("if (s.playing) unexpectedPauseRecoveryUntil = 0L"))
        val js = source("app/src/main/assets/aay.js")
        assertTrue(js.contains("setInterval(backgroundWatchdog, 5000)"))
        assertTrue(js.contains("bgWatch.explicitPause = true"))
        assertTrue(js.contains("bgWatch.attempts >= 3"))
    }

    @Test fun mediaServiceKeepsForegroundThroughTransientPause() {
        val media = source("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        assertTrue(media.contains("handler.removeCallbacks(demoteForeground)"))
        assertTrue(media.contains("handler.postDelayed(demoteForeground, 8_000L)"))
        assertTrue(media.contains("!snapshot.playing && !recoveringPlayback"))
        assertTrue(media.contains("handler.removeCallbacksAndMessages(null)"))
    }

    @Test fun switchingQualityKeepsPositionOnSameMediaOnly() {
        val js = source("app/src/main/assets/aay.js")
        assertTrue(js.contains("function rememberSourceChange(v, reason)"))
        assertTrue(js.contains("function restoreAfterSourceChange(v)"))
        assertTrue(js.contains("qualityState.seekAt"))
        assertTrue(js.contains("pending.id !== qualityVideoId()"))
        assertTrue(js.contains("A.restoreAt = function (id, seconds)"))
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("incomingId == outgoingId"))
        assertTrue(web.contains("incomingId == previous.id"))
        assertTrue(web.contains("sameVideoReloadPosition = null"))
        assertTrue(web.contains("JSONObject.quote(videoId)"))
    }
}
