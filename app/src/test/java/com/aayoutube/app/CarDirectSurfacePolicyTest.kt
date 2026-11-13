package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CarDirectSurfacePolicyTest {
    private fun allowed(
        requested: String? = "car-current",
        active: String = "car-current",
        connected: Boolean = true,
        enabled: Boolean = true,
        playing: Boolean = false,
        competing: Boolean = false,
        visible: Boolean = false,
    ) = CarDirectSurfacePolicy.canStart(
        requested, active, connected, enabled, playing, competing, visible
    )

    @Test fun onlyCurrentOptedInPausedHostCanStart() {
        assertTrue(allowed())
        assertFalse(allowed(requested = null))
        assertFalse(allowed(requested = "old-car"))
        assertFalse(allowed(active = "replacement"))
        assertFalse(allowed(connected = false))
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(playing = true))
        assertFalse(allowed(competing = true))
        assertFalse(allowed(visible = true))
    }

    @Test fun deterministicFrameTimingStatsHaveBoundedOutputs() {
        assertEquals(0, CarDirectSurfacePolicy.estimatedFps(0, 1000L))
        assertEquals(0, CarDirectSurfacePolicy.estimatedFps(100, 0L))
        assertEquals(30, CarDirectSurfacePolicy.estimatedFps(150, 5000L))
        assertEquals(24, CarDirectSurfacePolicy.estimatedFps(120, 5000L))
        assertEquals(33L, CarDirectSurfacePolicy.TARGET_FRAME_INTERVAL_MS)
        assertEquals(5000L, CarDirectSurfacePolicy.DURATION_MS)
        assertTrue(CarDirectSurfacePolicy.describe(0, 1000, 0)
            .contains("inconclusive"))
        assertTrue(CarDirectSurfacePolicy.describe(120, 5000, 55)
            .contains("not proof"))
    }

    private fun source(path: String) =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun rendererIsOnlySyntheticAndHasBoundedCleanup() {
        val renderer = source("app/src/main/java/com/aayoutube/app/CarDirectSurfaceView.kt")
        val probe = source("app/src/main/java/com/aayoutube/app/CarDirectSurfaceProbe.kt")
        val activity = source("app/src/main/java/com/aayoutube/app/CarVideoPresentation.kt")
        val settings = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(renderer.contains("class CarDirectSurfaceView("))
        assertTrue(renderer.contains(": SurfaceView(context), SurfaceHolder.Callback"))
        assertTrue(renderer.contains("holder.lockCanvas()"))
        assertTrue(renderer.contains("holder.unlockCanvasAndPost("))
        assertTrue(renderer.contains("drawThread?.quitSafely()"))
        assertTrue(renderer.contains("setZOrderOnTop(true)"))
        assertTrue(renderer.contains("PixelCopy.request("))
        assertTrue(renderer.contains("image.recycle()"))
        assertTrue(renderer.contains("CarVideoPatternPixelPolicy.analyze("))
        assertTrue(probe.contains("CarDirectSurfacePolicy.canStart("))
        assertTrue(probe.contains("CarVideoPatternProbe.state.value.visible"))
        assertTrue(probe.contains("CarWindowPixelProbe.state.value.running"))
        assertTrue(probe.contains("main.postDelayed(it, CarDirectSurfacePolicy.DURATION_MS)"))
        assertTrue(probe.contains("fun stopForPlayback(owner: String)"))
        assertTrue(activity.contains("CarDirectSurfaceProbe.register(carToken)"))
        assertTrue(activity.contains("CarDirectSurfaceProbe.unregister(carToken)"))
        assertTrue(activity.contains("CarDirectSurfaceProbe.stopForPlayback(carToken)"))
        assertTrue(activity.contains("CarDirectSurfaceView("))
        assertTrue(activity.contains("onRelease = { it.stopRendering() }"))
        assertTrue(settings.contains("CarDirectSurfaceProbe.request()"))
        assertTrue(settings.contains("Test direct surface"))
        assertTrue(settings.contains("Moving image visible"))
        assertFalse(renderer.contains("WebView("))
        assertFalse(renderer.contains("WebHost.cmd("))
        assertFalse(renderer.contains("MediaCodec("))
        assertFalse(renderer.contains("MediaSession("))
        assertFalse(renderer.contains("MediaProjectionManager"))
        assertFalse(renderer.contains("writeBytes("))
        assertFalse(renderer.contains("compress("))
        assertFalse(manifest.contains("androidx.car.app.CarAppService"))
    }
}
