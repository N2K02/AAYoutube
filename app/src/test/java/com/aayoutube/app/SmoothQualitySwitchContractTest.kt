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
        val adopt = js.substringAfter("function adoptUserQuality(q, source) {").substringBefore("A.adoptUserQuality = adoptUserQuality;")
        assertTrue(adopt.contains("current === q && !bgSaverActive()"))
        assertTrue(adopt.contains("qualityApply.key = qualityVideoId() + ':' + q + '-' + q"))
        assertFalse(adopt.contains("later(applyPreferredQuality"))
    }

    @Test fun pickDetectionDoesNotDependOnMenuMarkup() {
        assertTrue(js.contains("adoptUserQuality(q, 'menu');"))
        assertTrue(js.contains("A.adoptUserQuality(q, 'YouTube saved pick')"))
        assertTrue(js.contains("adoptUserQuality(current, 'player change after tap')"))
        assertTrue(js.contains("if (A.qualityTapAt && Date.now() - A.qualityTapAt < 5000 && tapVideo && tapVideo === qualityVideoId())"))
        // Outside menu/sheet markup, a recognized quality label is accepted only
        // in the short window after an explicit player Settings tap.
        assertTrue(js.contains("if (!inQualityMenu && (!recentSettingsTap() || !q)) return;"))
        assertTrue(js.contains("if (target.closest(PLAYER_SETTINGS) && !q)"))
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

    @Test fun unsmoothTiersAreHiddenAndMissingTiersFallBack() {
        assertTrue(js.contains("localStorage.setItem('aay-max-smooth-h'"))
        assertTrue(js.contains("return tooLarge(t);"))
        assertFalse(js.contains("if (c === 'h264')"))
        assertFalse(js.contains("if (c === 'vp9')"))
        assertTrue(js.contains("var chosen = availableQuality(p, desiredQuality());"))
    }

    @Test fun positionRestoreWaitsForYouTubeFirst() {
        assertTrue(js.contains("if (!pending.waited && Date.now() - pending.started < 1000)"))
    }

    @Test fun smallWindowsCapQualityAndPlayStateReconciles() {
        assertTrue(js.contains("if ((A.inPip || A.isMini) && (q === 'auto'"))
        assertTrue(js.contains("var PIP_MAX_QUALITY = 'hd720';"))
        assertTrue(js.contains("lastSent.playing !== (!v.paused && !v.ended)"))
    }
}
