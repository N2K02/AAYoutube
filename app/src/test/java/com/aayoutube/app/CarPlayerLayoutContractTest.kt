package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CarPlayerLayoutContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun projectedCarSwipeAndMiniPlayerTouchAreasDoNotBlockYoutube() {
        val frame = source("app/src/main/java/com/aayoutube/app/CarFrameLayout.kt")
        val projection = source("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        val js = source("app/src/main/assets/aay.js")
        assertTrue(frame.contains("override fun dispatchTouchEvent(event: MotionEvent)"))
        assertTrue(frame.contains("post { onSwipeDown?.invoke() }"))
        assertTrue(frame.contains("return super.dispatchTouchEvent(event)"))
        assertTrue(projection.contains("frame.onSwipeDown = {"))
        assertTrue(projection.contains("WebHost.isActiveCarHost(carToken)"))
        assertTrue(projection.contains("val minY = 0f"))
        assertTrue(projection.contains("val maxY = with(density) { (maxHeight - miniHeight).toPx() }"))
        assertTrue(projection.contains("val minX = 0f"))
        assertTrue(projection.contains("val maxX = with(density) { (maxWidth - miniWidth).toPx() }"))
        assertTrue(js.contains("pointer-events:none!important"))
        assertTrue(js.contains("pointer-events:auto!important"))
        assertTrue(web.contains("val mini = mode.value == PlayerMode.MINI"))
        assertTrue(js.contains("!A.isBg && !A.hold && !A.isMini"))
    }

    @Test fun youtubeMobilePlayerFillsCarViewportWithoutCroppingVideo() {
        val js = source("app/src/main/assets/aay.js")
        assertTrue(js.contains("html.aay-car ytm-watch-player"))
        assertTrue(js.contains("html.aay-car ytm-player"))
        assertTrue(js.contains("html.aay-car ytm-player-component"))
        assertTrue(js.contains("html.aay-car .player-placeholder"))
        assertTrue(js.contains("aspect-ratio:auto!important"))
        assertTrue(js.contains("object-fit:contain!important"))
        assertTrue(js.contains("html.aay-car ytm-watch-metadata"))
        assertTrue(js.contains("html.aay-car ytm-playlist-panel-renderer"))
        assertFalse(js.contains("html.aay-car video.html5-main-video{object-fit:cover"))
    }

}
