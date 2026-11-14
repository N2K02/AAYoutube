package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class ExperimentalCarVideoMonitorTest {
    @Test fun preservesNewestCarSessionWhenOldHostStops() {
        ExperimentalCarVideoMonitor.begin("car-old", true)
        ExperimentalCarVideoMonitor.begin("car-new", false)
        ExperimentalCarVideoMonitor.end("car-old")
        assertTrue(ExperimentalCarVideoMonitor.state.value.active)
        assertEquals("Standard video", ExperimentalCarVideoMonitor.state.value.state)
        ExperimentalCarVideoMonitor.end("car-new")
        assertFalse(ExperimentalCarVideoMonitor.state.value.active)
    }

    @Test fun recordsOnlyGeometryAndBoundsWithoutMedia() {
        ExperimentalCarVideoMonitor.begin("car-geometry", true)
        ExperimentalCarVideoMonitor.check(
            owner = "car-geometry", state = "Healthy WebView geometry",
            checks = 3, repairs = 1, displayId = 323,
            frameWidth = 800, frameHeight = 480,
            playerWidth = 800, playerHeight = 480,
            visibleArea = CarVideoBounds(0, 0, 800, 480),
        )
        ExperimentalCarVideoMonitor.decoder(
            owner = "car-geometry", decoded = 245, dropped = 3,
            width = 1920, height = 1080, paused = false,
        )
        val d = ExperimentalCarVideoMonitor.state.value
        assertEquals(245, d.decodedFrames)
        assertEquals(3, d.droppedFrames)
        assertEquals("1920×1080", d.decoderSize)
        assertEquals(false, d.decoderPaused)
        assertTrue(d.active)
        assertTrue(d.enabled)
        assertEquals(3, d.checks)
        assertEquals(1, d.repairs)
        assertEquals("800×480", d.frameSize)
        assertEquals("800×450", d.referenceVideoArea.substringAfter(" "))
        assertEquals(323, d.displayId)
    }

    @Test fun frameHealthTracksLivePlaybackAndResetsOnNewConnection() {
        ExperimentalCarVideoMonitor.begin("frame-host", true)
        ExperimentalCarVideoMonitor.decoder(
            owner = "frame-host", decoded = 30, dropped = 0,
            width = 1280, height = 720, paused = false,
            monotonicMs = 1_000L, positionSeconds = 5.0,
        )
        assertEquals("awaiting baseline", ExperimentalCarVideoMonitor.state.value.frameHealth)
        ExperimentalCarVideoMonitor.decoder(
            owner = "frame-host", decoded = 160, dropped = 0,
            width = 1280, height = 720, paused = false,
            monotonicMs = 7_000L, positionSeconds = 11.0,
        )
        assertEquals("frames advancing", ExperimentalCarVideoMonitor.state.value.frameHealth)
        ExperimentalCarVideoMonitor.decoder(
            owner = "stale-host", decoded = 160, dropped = 0,
            width = 1280, height = 720, paused = false,
            monotonicMs = 14_000L, positionSeconds = 11.0,
        )
        assertEquals("frames advancing", ExperimentalCarVideoMonitor.state.value.frameHealth)
        ExperimentalCarVideoMonitor.begin("fresh-host", true)
        assertEquals("Not measured", ExperimentalCarVideoMonitor.state.value.frameHealth)
        ExperimentalCarVideoMonitor.fallback("fresh-host", "rebind budget")
        ExperimentalCarVideoMonitor.decoder(
            owner = "fresh-host", decoded = 240, dropped = 0,
            width = 1280, height = 720, paused = false,
            monotonicMs = 20_000L, positionSeconds = 20.0,
        )
        assertEquals("Monitoring disabled after fallback",
            ExperimentalCarVideoMonitor.state.value.frameHealth)
    }

    @Test fun fallbackAndStaleDiagnosticsCannotOverwriteActiveHost() {
        ExperimentalCarVideoMonitor.begin("car-current", true)
        ExperimentalCarVideoMonitor.fallback("car-old", "irrelevant")
        assertEquals("Experimental surface checks enabled", ExperimentalCarVideoMonitor.state.value.state)
        ExperimentalCarVideoMonitor.fallback("car-current", "repair budget")
        assertTrue(ExperimentalCarVideoMonitor.state.value.state.contains("Standard fallback"))
        ExperimentalCarVideoMonitor.end("car-current")
    }
}
