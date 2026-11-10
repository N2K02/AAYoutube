package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CarRenderCorrelationPolicyTest {
    private fun evidence(
        active: Boolean = true,
        experimental: Boolean = true,
        windowBuffer: Boolean? = null,
        windowSeen: Boolean? = null,
        surfaceBuffer: Boolean? = null,
        surfaceSeen: Boolean? = null,
    ) = CarRenderEvidence(
        active = active, experimental = experimental,
        windowBuffer = windowBuffer, windowSeen = windowSeen,
        surfaceBuffer = surfaceBuffer, surfaceSeen = surfaceSeen,
        surfaceFrames = 115, frameHealth = "frames advancing",
        checks = 4, repairs = 1, frameSize = "1280×720",
        playerSize = "1280×720",
    )

    @Test fun distinguishesDisconnectedDisabledAndMissingUserEvidence() {
        assertEquals(CarRenderFinding.DISCONNECTED,
            CarRenderCorrelationPolicy.assess(evidence(active = false)).finding)
        assertEquals(CarRenderFinding.DISABLED,
            CarRenderCorrelationPolicy.assess(evidence(experimental = false)).finding)
        assertEquals(CarRenderFinding.AWAITING_COMPOSE_OBSERVATION,
            CarRenderCorrelationPolicy.assess(evidence(windowBuffer = true)).finding)
    }

    @Test fun identifiesHostTransferAndIndependentSurfaceLayerSuspects() {
        val missingCompose = CarRenderCorrelationPolicy.assess(
            evidence(windowBuffer = true, windowSeen = false))
        assertEquals(CarRenderFinding.COMPOSE_PRESENTATION_SUSPECTED,
            missingCompose.finding)
        assertTrue(missingCompose.nextStep.contains("host transfer"))

        val absentSurface = CarRenderCorrelationPolicy.assess(
            evidence(windowBuffer = true, windowSeen = true,
                surfaceBuffer = true, surfaceSeen = false))
        assertEquals(CarRenderFinding.SECONDARY_LAYER_PRESENTATION_SUSPECTED,
            absentSurface.finding)
        assertTrue(absentSurface.summary.contains("SurfaceView"))
    }

    @Test fun neverClaimsSyntheticSuccessProvesActualVideoFrames() {
        val observed = CarRenderCorrelationPolicy.assess(
            evidence(windowBuffer = true, windowSeen = true,
                surfaceBuffer = true, surfaceSeen = true))
        assertEquals(CarRenderFinding.SYNTHETIC_LAYERS_OBSERVED, observed.finding)
        assertTrue(observed.nextStep.contains("physical video paint remains unverified"))
        assertFalse(observed.summary.contains("YouTube works"))
    }

    @Test fun suspectedDecoderStallsRemainSeparateFromCarLayerSuccess() {
        val stalled = evidence(windowBuffer = true, windowSeen = true,
            surfaceBuffer = true, surfaceSeen = true).copy(
            frameHealth = "decoder stall suspected")
        val report = CarRenderCorrelationPolicy.assess(stalled)
        assertEquals(CarRenderFinding.DECODER_STALL_SUSPECTED, report.finding)
        assertTrue(report.nextStep.contains("Repeat Check decoder health"))
        val playbackStall = CarRenderCorrelationPolicy.assess(
            stalled.copy(frameHealth = "playback stall suspected"))
        assertEquals(CarRenderFinding.DECODER_STALL_SUSPECTED,
            playbackStall.finding)
    }

    @Test fun negativePixelCopyWithVisibleCarImageIsInconclusive() {
        val result = CarRenderCorrelationPolicy.assess(
            evidence(windowBuffer = false, windowSeen = true))
        assertEquals(CarRenderFinding.BUFFER_READBACK_MISMATCH, result.finding)
        assertTrue(result.nextStep.contains("PixelCopy"))

        val mixed = CarRenderCorrelationPolicy.assess(
            evidence(windowSeen = false, surfaceSeen = true))
        assertEquals(CarRenderFinding.INCONSISTENT_HUMAN_OBSERVATIONS, mixed.finding)
    }

    @Test fun suggestsRemainingDirectTestWhenComposeTestWasSuccessful() {
        val result = CarRenderCorrelationPolicy.assess(
            evidence(windowBuffer = true, windowSeen = true))
        assertEquals(CarRenderFinding.AWAITING_DIRECT_SURFACE_OBSERVATION, result.finding)
        assertTrue(result.nextStep.contains("Test direct surface"))
    }

    @Test fun reportContainsOnlySanitizedAggregateResults() {
        val bad = evidence(windowSeen = true, surfaceSeen = false).copy(
            frameSize = "1280×720 https://private.example",
            playerSize = "640×360 secret-key=password",
            frameHealth = "frames advancing bearer aaaaa.bbbbb.ccccc",
        )
        val result = CarRenderCorrelationPolicy.report(bad)
        assertTrue(result.contains("Compose bars seen on car: yes"))
        assertTrue(result.contains("Moving surface seen on car: no"))
        assertTrue(result.contains("Direct surface submitted frames: 115"))
        assertTrue(result.contains("Finding: SECONDARY_LAYER_PRESENTATION_SUSPECTED"))
        assertFalse(result.contains("https://"))
        assertFalse(result.contains("password"))
        assertFalse(result.contains("bearer"))
        assertFalse(result.contains("private.example"))
        assertTrue(result.contains("Decoder health: unrecognized"))
        assertTrue(result.contains("Frame: unknown; WebView: unknown"))
        assertTrue(result.contains("no physical screen or live video success inferred"))
    }

    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun typedEvidenceIsCollectedFromRealUserFeedbackNotStringParsing() {
        val window = source("app/src/main/java/com/aayoutube/app/CarVideoPatternProbe.kt")
        val surface = source("app/src/main/java/com/aayoutube/app/CarDirectSurfaceProbe.kt")
        val winCopy = source("app/src/main/java/com/aayoutube/app/CarVideoPatternReadback.kt")
        val surfaceCopy = source("app/src/main/java/com/aayoutube/app/CarDirectSurfaceView.kt")
        val ui = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        assertTrue(window.contains("val observedVisible: Boolean? = null"))
        assertTrue(surface.contains("val observedVisible: Boolean? = null"))
        assertTrue(window.contains("observedVisible = seen"))
        assertTrue(surface.contains("observedVisible = seen"))
        assertTrue(winCopy.contains("detected = result.matched >= 17"))
        assertTrue(surfaceCopy.contains("detected = result.matched >= 17"))
        val genericCopy = source("app/src/main/java/com/aayoutube/app/CarWindowPixelProbe.kt")
        assertTrue(genericCopy.contains("CarVideoPatternProbe.state.value.visible"))
        assertTrue(genericCopy.contains("CarDirectSurfaceProbe.state.value.visible"))
        assertTrue(window.contains("CarDirectSurfaceProbe.state.value.visible"))
        assertTrue(ui.contains("CarRenderCorrelationPolicy.assess("))
        assertTrue(ui.contains("CarRenderCorrelationPolicy.report("))
        assertTrue(ui.contains("CLIPBOARD_SERVICE"))
        assertFalse(ui.contains("windowReadback.contains(\"detected\")"))
    }
}
