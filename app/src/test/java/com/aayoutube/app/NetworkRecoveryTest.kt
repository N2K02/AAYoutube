package com.aayoutube.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NetworkRecoveryTest {
    @Test fun lossAndValidatedReturnReportTheOutage() {
        val w = NetworkWatch()
        assertNull("first validated network is the baseline", w.onValidated("wifi", 1_000))
        assertTrue(w.online)
        w.onLost("wifi", 5_000)
        assertFalse(w.online)
        assertTrue(w.recentlyDisrupted(100_000))
        assertEquals(7_000L, w.onValidated("wifi", 12_000))
        assertTrue(w.online)
        assertNull("same network again changes nothing", w.onValidated("wifi", 13_000))
    }

    @Test fun networkSwitchCountsAsDisruptionAndOldNetworkLossIsIgnored() {
        val w = NetworkWatch()
        w.onValidated("wifi", 0)
        assertEquals(0L, w.onValidated("cell", 2_000)) // default moved: streams break too
        w.onLost("wifi", 2_100) // the old network going away afterwards
        assertTrue("still online on cellular", w.online)
        assertTrue(w.recentlyDisrupted(2_000 + NetworkRecoveryPolicy.DISRUPTION_WINDOW_MS))
        assertFalse(w.recentlyDisrupted(2_000 + NetworkRecoveryPolicy.DISRUPTION_WINDOW_MS + 1))
    }

    private val playing = Snap(id = "v", playing = true, posMs = 95_000, durMs = 300_000)

    @Test fun resumeOnlyWhatTheUserWantedAndIsNotRunning() {
        val stalled = playing.copy(playing = false, posMs = 0, durMs = 0)
        assertTrue(NetworkRecoveryPolicy.shouldResume(stalled, playbackWanted = true, otherMediaHeld = false))
        assertTrue(NetworkRecoveryPolicy.shouldResume(playing.copy(buffering = true), true, false))
        assertFalse(NetworkRecoveryPolicy.shouldResume(playing, true, false))
        assertFalse(NetworkRecoveryPolicy.shouldResume(stalled.copy(userPaused = true), true, false))
        assertFalse(NetworkRecoveryPolicy.shouldResume(stalled, false, false))
        assertFalse(NetworkRecoveryPolicy.shouldResume(stalled.copy(ended = true), true, false))
        assertFalse(NetworkRecoveryPolicy.shouldResume(stalled, true, otherMediaHeld = true))
    }

    @Test fun positionSurvivesThePlayersResetAfterItsError() {
        val reset = playing.copy(playing = false, posMs = 0, durMs = 0)
        assertEquals(95_000L, NetworkRecoveryPolicy.resumePositionMs(reset, "v", 95_000))
        assertEquals(40_000L, NetworkRecoveryPolicy.resumePositionMs(reset.copy(posMs = 40_000), "v", 95_000))
        assertEquals(0L, NetworkRecoveryPolicy.resumePositionMs(reset.copy(id = "other"), "v", 95_000))
    }

    private fun host() = listOf(File("src/main/java/com/aayoutube/app/WebHost.kt"),
        File("app/src/main/java/com/aayoutube/app/WebHost.kt")).first { it.isFile }.readText()

    @Test fun offlinePausesNeitherSpendRetriesNorStartAnOtherMediaHold() {
        val h = host()
        val resume = h.substringAfter("private fun resumeIfDropped(").substringBefore("private fun cancelOtherMediaWork")
        assertTrue(resume.indexOf("if (!NetworkMonitor.online)") < resume.indexOf("resumeTries++"))
        val judge = h.substringAfter("private val droppedResumeJudge: Runnable = Runnable {").substringBefore("// ----")
        assertTrue(judge.indexOf("NetworkMonitor.recentlyDisrupted()") < judge.indexOf("startOtherMediaHold(\"native resume\")"))
        assertTrue(h.contains("!NetworkMonitor.recentlyDisrupted() && qualityReloadDelay() == 0L && otherMediaActive()"))
        // Reconnect: budget reset, settle, recover the same video at its last good position.
        val restored = h.substringAfter("internal fun onNetworkRestored(outageMs: Long)").substringBefore("private val networkResume")
        assertTrue(restored.contains("resetResumeBudget()"))
        assertTrue(restored.contains("main.postDelayed(networkResume, NetworkRecoveryPolicy.SETTLE_MS)"))
        assertTrue(h.contains("window.__aay.recoverNetwork(\${posMs / 1000.0})"))
        // Monitoring lives with the renderer.
        assertTrue(h.contains("NetworkMonitor.start(app)"))
        assertTrue(h.substringAfter("fun release(preserveCarOwners: Boolean = false) {").substringBefore("main.removeCallbacks(freezeSoon)")
            .contains("NetworkMonitor.stop()"))
    }

    @Test fun userPlayEndsTheHoldButAppRecoveryDoesNot() {
        val h = host()
        val cmd = h.substringAfter("    fun cmd(name: String) {").substringBefore("fun scrollBy(")
        assertTrue(cmd.contains("val userPlay = name == \"play\" && !automaticPlay"))
        assertTrue(cmd.contains("if (userPlay && otherMediaHeldSince != 0L) endOtherMediaHold(\"user pressed Play\")"))
        assertTrue(cmd.contains("window.__aay.cmd('play','user')"))
        // Recovery paths resume as automatic plays (they keep respecting a real hold).
        assertTrue(h.contains("if (active) startOtherMediaHold(\"native resume\") else autoPlay()"))
        assertTrue(h.contains("if (keepRecovering()) autoPlay()"))
        // The media session's Play (notification, car, headset) is the user's.
        assertTrue(h.contains("} else cmd(\"play\")"))
    }
}
