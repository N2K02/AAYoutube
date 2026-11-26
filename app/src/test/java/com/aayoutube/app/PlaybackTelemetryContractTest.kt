package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Protect lightweight, privacy-safe player performance diagnostics. */
class PlaybackTelemetryContractTest {
    private fun read(path: String) = sequenceOf(File(path), File("../$path"))
        .first { it.isFile }.readText()

    @Test fun pageReportsBoundedMetricsOnlyWhilePlaying() {
        val js = read("app/src/main/assets/aay.js")
        assertTrue(js.contains("var perfSample = function () {\n      var now = Date.now(), span = now - perf.lastAt;"))
        assertTrue(js.contains("if (playing && N && typeof N.perf === 'function')"))
        assertTrue(js.contains("sourceLoads: perf.sourceLoads"))
        assertTrue(js.contains("layout: perf.layoutChanges"))
        assertTrue(js.contains("droppedPct: droppedPct"))
        assertTrue(js.contains("longTasks: perf.longTasks"))
        assertTrue(js.contains("codec: codec, tier: tier"))
        assertTrue(js.contains("lastNonCriticalDomAt >= 8000"))
        assertFalse(js.contains("rendererCpuPct:"))
        assertFalse(js.contains("videoId: qualityVideoId()"))
    }

    @Test fun nativeSamplesItsOwnProcessAndMarksRendererCpuAsExternal() {
        val host = read("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(host.contains("fun onJsPerformance(payload: String, generation: Int)"))
        assertTrue(host.contains("if (payload.length > 1024) return"))
        assertTrue(host.contains("generation != webGeneration"))
        assertTrue(host.contains("android.os.Process.getElapsedCpuTime()"))
        assertTrue(host.contains("rendererCpuPct=adb-only"))
        assertTrue(host.contains("recentWebCpu.size > 10"))
        assertTrue(host.contains("w.addOnLayoutChangeListener"))
        assertTrue(host.contains("resizeCount="))
        assertTrue(host.contains("fun perf(payload: String, token: String?)"))
    }

    @Test fun playbackUsesNativeWebAudioOutput() {
        val host = read("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(!host.contains("aay_eq.js"))
        assertTrue(!host.contains("AAYSoundFx"))
    }

    @Test fun mediaCpuHasBoundedAverageAndPeak() {
        val service = read("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        assertTrue(service.contains("recentMediaCpu.size > 10"))
        assertTrue(service.contains("avgCpuPct="))
        assertTrue(service.contains("peakCpuPct="))
        // Health keeps its own timer; screen off lengthens it (adaptive diagnostics sampling).
        assertTrue(service.contains("handler.postDelayed(this, AdaptivePerformancePolicy.healthIntervalMs(PerformanceMonitor.screenOn()))"))
    }
}
