package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Performance guard: background audio must not require background visual DOM updates. */
class BackgroundDomPerformanceContractTest {
    private val script by lazy {
        listOf(File("app/src/main/assets/aay.js"), File("../app/src/main/assets/aay.js"))
            .first { it.isFile }.readText()
    }

    @Test fun backgroundSkipsVisualWorkButRetainsMediaBinding() {
        val tick = script.substringAfter("function tick() {").substringBefore("function schedTick()")
        assertTrue(tick.contains("if (v) bind(v);"))
        assertTrue(tick.contains("if (v && !BROWSE && A.applyQuality) A.applyQuality();"))
        assertTrue(tick.contains("if (A.isBg && !A.inPip && !A.isCar) return;"))
        assertTrue(tick.indexOf("if (A.isBg && !A.inPip && !A.isCar) return;") < tick.indexOf("skipAd();"))
    }

    @Test fun backgroundSkipsRedundantUiPositionReports() {
        assertTrue(script.contains("if (BROWSE || (A.isBg && !A.inPip && !A.isCar)) return;"))
        assertTrue(script.contains("if (v && !v.paused) schedReport(0);"))
    }

    @Test fun foregroundRechecksDomAndBackgroundRecoveryContinues() {
        assertTrue(script.contains("if (wasBg && !A.isBg) schedTick();"))
        assertTrue(script.contains("setInterval(backgroundWatchdog, 5000)"))
        assertTrue(script.contains("setInterval(sendLyricClock, 250)"))
        assertTrue(script.contains("(A.isBg && !A.inPip && !A.isCar) ? 5000 : 900"))
    }
}
