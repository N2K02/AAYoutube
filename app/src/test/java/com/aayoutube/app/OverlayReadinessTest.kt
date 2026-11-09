package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class OverlayReadinessTest {
    @Test fun transportDisabledBeforeVideoSelection() {
        assertFalse(OverlayTransportPolicy.ready("", "", false, false))
        assertFalse(OverlayTransportPolicy.ready("stale", "", false, false))
    }

    @Test fun transportDisabledWhileNewVideoLoads() {
        assertFalse(OverlayTransportPolicy.ready("old", "new", false, false))
        assertFalse(OverlayTransportPolicy.ready("new", "new", true, false))
        assertFalse(OverlayTransportPolicy.ready("new", "new", false, true))
    }

    @Test fun transportEnabledForReadyPausedOrPlayingVideo() {
        assertTrue(OverlayTransportPolicy.ready("video", "video", false, false))
    }

    @Test fun bothPhoneAndCarApplyReadinessToSharedRail() {
        val phone = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        val car = source("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        val rail = source("app/src/main/java/com/aayoutube/app/FloatingVideoBar.kt")
        assertTrue(phone.contains("transportReady = OverlayTransportPolicy.ready("))
        assertTrue(car.contains("transportReady = OverlayTransportPolicy.ready("))
        assertTrue(rail.contains("val transportEnabled = transportReady && pending == null"))
    }

    @Test fun lyricsStayClosedAtSelectionAndRequireTap() {
        val ui = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        assertTrue(ui.contains("lyricsOpenedForVideo by remember(selectedWatchVideoId) { mutableStateOf(false) }"))
        assertTrue(ui.contains("lyricsShown && lyricsOpenedForVideo"))
        assertTrue(ui.contains("lyricsOpenedForVideo = !lyricsOpenedForVideo"))
    }

    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()
}
