package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.aayoutube.app.OtherMediaHoldPolicy.Step

class OtherMediaHoldPolicyTest {
    @Test fun musicOrCallCountsAsOtherMedia() {
        assertTrue(OtherMediaHoldPolicy.othersActive(true, 0))
        assertTrue(OtherMediaHoldPolicy.othersActive(false, 2)) // in call
        assertTrue(OtherMediaHoldPolicy.othersActive(false, 1)) // ringtone
        assertFalse(OtherMediaHoldPolicy.othersActive(false, 0))
    }

    @Test fun holdsWhileOtherMediaPlaysAndResumesAfterQuiet() {
        assertEquals(Step.KEEP_HOLDING, OtherMediaHoldPolicy.step(true, 0L, 0L, 10_000L))
        assertEquals(Step.KEEP_HOLDING, OtherMediaHoldPolicy.step(false, 0L, 9_000L, 10_000L))
        assertEquals(Step.RESUME, OtherMediaHoldPolicy.step(false, 0L, 8_000L, 10_000L))
    }

    @Test fun quietTimerResetsWheneverOtherMediaIsAudible() {
        assertEquals(500L, OtherMediaHoldPolicy.quietSince(0L, false, 500L))
        assertEquals(500L, OtherMediaHoldPolicy.quietSince(500L, false, 900L))
        assertEquals(0L, OtherMediaHoldPolicy.quietSince(500L, true, 1_000L))
    }

    @Test fun holdIsBounded() {
        val max = OtherMediaHoldPolicy.MAX_HOLD_MS
        assertEquals(Step.GIVE_UP, OtherMediaHoldPolicy.step(true, 1_000L, 0L, 1_000L + max))
        assertEquals(Step.GIVE_UP, OtherMediaHoldPolicy.step(false, 1_000L, 1_000L, 1_000L + max))
    }

    @Test fun nativeAndPageNeverResumeOverAnotherApp() {
        fun read(p: String) = listOf(File(p), File("../$p")).first { it.isFile }.readText()
        val web = read("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("main.postDelayed(droppedResume, OtherMediaHoldPolicy.SETTLE_MS)"))
        assertTrue(web.contains("if (otherMediaActive()) startOtherMediaHold(\"native resume\") else cmd(\"play\")"))
        assertTrue(web.contains("fun otherMediaHold(): Boolean"))
        val js = read("app/src/main/assets/aay.js")
        assertTrue(js.contains("N.otherMediaHold(BRIDGE_TOKEN)"))
        assertTrue(js.contains("if (bgWatch.held) return;"))
        assertTrue(js.contains("A.mediaHold = function (on, giveUp)"))
    }
}
