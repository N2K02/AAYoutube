package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Details taken from fcaronte/AABrowser (MainCarActivity, BrowserScreen, ForegroundService). */
class AABrowserParityContractTest {
    private fun src(name: String): String =
        listOf(File("app/src/main/java/com/aayoutube/app/$name"), File("../app/src/main/java/com/aayoutube/app/$name"))
            .first { it.isFile }.readText()
    private val host = src("WebHost.kt")

    @Test fun webViewSettingsMatchAABrowser() {
        val configure = host.substringAfter("private fun configure(w: WebView) {").substringBefore("internal fun userAgent")
        for (line in listOf(
            "s.databaseEnabled = true", "s.allowFileAccess = true", "s.allowContentAccess = true",
            "s.setSupportZoom(true)", "s.builtInZoomControls = true", "s.displayZoomControls = false",
            "s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE",
            "w.isLongClickable = true", "w.isClickable = true", "w.isFocusableInTouchMode = true",
        )) assertTrue(line, configure.contains(line))
    }

    @Test fun carGetsARendererCreatedInItsOwnWindow() {
        // AABrowser creates its WebView inside the car window and never brings one from the phone.
        assertEquals("display", PlayerDisplayPolicy.carRecreateReason(0, 7, null, 11))
        assertEquals("window", PlayerDisplayPolicy.carRecreateReason(7, 7, 11, 12))
        assertNull("same car window keeps its renderer", PlayerDisplayPolicy.carRecreateReason(7, 7, 11, 11))
        assertTrue("and the phone never shows a car-born one", PlayerDisplayPolicy.shouldRecreate(7, 0))
        val bind = host.substringAfter("fun attachToCarWindow(context: Context, frame: ViewGroup): WebView {").substringBefore("fun claimCarHost")
        assertTrue(bind.contains("if (reason != null) recreateRenderer(context, targetDisplay ?: -1, reason)"))
    }

    @Test fun foregroundServiceLivesAsLongAsTheCarScreen() {
        val car = src("LegacyProjectionActivity.kt")
        val create = car.substringAfter("override fun onCreate(bundle: Bundle?) {").substringBefore("private fun findCarInputManager")
        assertTrue(create.contains("PlaybackService.carAttached(this, true)"))
        val destroy = car.substringAfter("override fun onDestroy() {")
        assertTrue(destroy.contains("PlaybackService.carAttached(this, false)"))
        val service = src("PlaybackService.kt")
        assertTrue("paused playback keeps the foreground in the car",
            service.contains("if (foreground && !snapshot.playing && !recoveringPlayback && !carAttached)"))
        assertTrue("and the service is not stopped for idleness",
            service.contains("!quitRequested && !carAttached) stopSelf()"))
        assertTrue(service.contains("ACTION_CAR_DETACHED -> {"))
    }
}
