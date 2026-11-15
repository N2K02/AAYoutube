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
        assertTrue(js.contains("setInterval(function () {\n      var now = Date.now(), span = now - perf.lastAt;"))
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
        assertTrue(host.contains("fun perf(payload: String)"))
    }

    @Test fun equalizerReportsActiveGraphNotAnInventedCpuValue() {
        val eq = read("app/src/main/assets/aay_eq.js")
        assertTrue(eq.contains("A.eqPerf = function ()"))
        assertTrue(eq.contains("needsRoute() ? 'dsp' : 'bypass'"))
        assertTrue(eq.contains("EQ.ctx ? EQ.ctx.state : 'none'"))
    }

    @Test fun mediaCpuHasBoundedAverageAndPeak() {
        val service = read("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        assertTrue(service.contains("recentMediaCpu.size > 10"))
        assertTrue(service.contains("avgCpuPct="))
        assertTrue(service.contains("peakCpuPct="))
        assertTrue(service.contains("handler.postDelayed(this, 60_000L)"))
    }
}
