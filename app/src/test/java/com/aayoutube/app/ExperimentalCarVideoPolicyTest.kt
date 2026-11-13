package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ExperimentalCarVideoPolicyTest {
    private fun repair(
        enabled: Boolean = true,
        active: Boolean = true,
        attached: Boolean = true,
        frameW: Int = 1280,
        frameH: Int = 720,
        frameDisplay: Int = 323,
        playerAttached: Boolean = true,
        playerW: Int = 1280,
        playerH: Int = 720,
        playerDisplay: Int = 323,
    ) = ExperimentalCarVideoPolicy.needsRebind(
        enabled, active, attached, frameW, frameH, frameDisplay,
        playerAttached, playerW, playerH, playerDisplay,
    )

    @Test fun standardVideoIsNeverModified() {
        assertFalse(repair(enabled = false, playerAttached = false))
        assertFalse(repair(enabled = false, playerDisplay = 0))
        assertFalse(repair(active = false, playerAttached = false))
        assertFalse(repair(attached = false, playerAttached = false))
    }

    @Test fun invalidHostGeometryNeverTriggersRebind() {
        assertFalse(repair(frameW = 0, playerAttached = false))
        assertFalse(repair(frameH = -1, playerAttached = false))
        assertFalse(repair(frameDisplay = -1, playerAttached = false))
    }

    @Test fun onlyInvalidOrMissingRendererRequestsRecovery() {
        assertFalse(repair())
        assertTrue(repair(playerAttached = false))
        assertTrue(repair(playerW = 0))
        assertTrue(repair(playerH = 0))
        assertTrue(repair(playerDisplay = 0))
        assertFalse(repair(playerW = 640, playerH = 360))
        assertEquals(4, ExperimentalCarVideoPolicy.MAX_CHECKS)
    }

    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun carRecoveryBudgetCannotResetOnResumeOrFrameReattachment() {
        val car = source("app/src/main/java/com/aayoutube/app/CarVideoPresentation.kt")
        assertTrue(car.contains("private var experimentalRecoveryStarted = false"))
        val schedule = car.substringAfter("private fun scheduleExperimentalRecovery() {")
            .substringBefore("    /** Frames decoded")
        assertTrue(schedule.contains("experimentalRecoveryStarted) return"))
        assertTrue(schedule.contains("experimentalRecoveryStarted = true"))
        assertTrue(schedule.contains("frame?.isAttachedToWindow != true"))
        assertFalse(schedule.contains("experimentalChecks = 0"))
        assertFalse(schedule.contains("experimentalRepairs = 0"))
        assertTrue(car.contains("experimentalRepairs >= ExperimentalCarVideoPolicy.MAX_REBINDS"))
    }

    @Test fun toggleIsOptInPersistedAndDoesNotChangeMusicOrPhoneRendering() {
        val features = source("app/src/main/java/com/aayoutube/app/Features.kt")
        val screens = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        val projection = source("app/src/main/java/com/aayoutube/app/CarVideoPresentation.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(features.contains("val experimentalVideo: Boolean = false"))
        assertTrue(features.contains("prefs.getBoolean(\"experimentalVideo\", d.experimentalVideo)"))
        assertTrue(features.contains(".putBoolean(\"experimentalVideo\", n.experimentalVideo)"))
        assertTrue(screens.contains("ExperimentalVideo(\"Experimental video\""))
        assertTrue(screens.contains("ExperimentalVideoPage()"))
        assertTrue(screens.contains("it.copy(experimentalVideo = enabled)"))
        assertTrue(projection.contains("experimentalVideoEnabled = Features.current.experimentalVideo"))
        assertTrue(projection.contains("handler.removeCallbacks(experimentalRecovery)"))
        assertTrue(projection.contains("WebHost.isActiveCarHost(carToken)"))
        assertTrue(projection.contains("WebHost.fullscreenView.value != null"))
        assertTrue(projection.contains("bindVideoToCarFrame(frame)"))
        assertEquals(1, Regex("androidx[.]car[.]app[.]CarAppService").findAll(manifest).count())
        assertFalse(manifest.contains("Shizuku"))
    }
}
