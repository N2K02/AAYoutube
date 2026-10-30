package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CarPlayerLayoutContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

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

    @Test fun carViewportIsBasedOnHostVisibleAreaRatherThanFullSurface() {
        val viewport = source("app/src/main/java/com/aayoutube/app/CarViewport.kt")
        assertTrue(viewport.contains("visible?.clipped(w, h) ?: stable?.clipped(w, h)"))
        val service = source("app/src/main/java/com/aayoutube/app/CarVideoService.kt")
        assertTrue(service.contains("onVisibleAreaChanged"))
        assertTrue(service.contains("frame.layoutParams = FrameLayout.LayoutParams(area.width, area.height)"))
    }
}
