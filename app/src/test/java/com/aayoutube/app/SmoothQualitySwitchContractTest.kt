package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quality changes must not double-switch the source, and reloads keep the saved tier. */
class SmoothQualitySwitchContractTest {
    private fun read(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    private val js get() = read("app/src/main/assets/aay.js")

    @Test fun userMenuPickIsNotReForcedAfterYouTubeApplies() {
        val click = js.substringAfter("var q = menuQuality(").substringBefore("}, true);")
        assertFalse(click.contains("later(applyPreferredQuality"))
        assertFalse(click.contains("setPlaybackQualityRange"))
        assertTrue(click.contains("qualityApply.key = qualityVideoId() + ':' + q"))
    }

    @Test fun sameVideoReloadReappliesSavedQualityWithoutLoop() {
        assertTrue(js.contains("['emptied', 'loadstart'].forEach"))
        assertTrue(js.contains("if (Date.now() - qualityApply.at < 3000) return;"))
        assertTrue(js.contains("qualityApply.at = Date.now();"))
    }

    @Test fun ineffectiveGraphicsLogFilterRemoved() {
        assertFalse(read("app/src/main/java/com/aayoutube/app/AppLog.kt").contains("LogNoisePolicy"))
    }

    @Test fun healthIsTimerDrivenAndDemotionIsBounded() {
        val media = read("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        assertTrue(media.contains("handler.postDelayed(healthTick, 60_000L)"))
        assertTrue(media.contains("scheduleDemoteIfNeeded(snapshot)"))
        assertTrue(media.contains("ForegroundDemotePolicy.delayMs("))
        assertFalse(media.contains("!resumePrefs.getBoolean(\"active\", false)) {"))
    }
}
