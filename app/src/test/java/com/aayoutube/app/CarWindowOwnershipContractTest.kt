package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression for the 31_10-14-37-54 hardware log: Android Auto replaced the
 * projected car window (new Surface, restored CarActivity) on the same display;
 * the single WebView was moved into it, kept decoding/playing audio, but the
 * car screen stayed black. The old instance's teardown also cleared the shared
 * "legacy-projection" owner (background 144p quality, car layout off).
 */
class CarWindowOwnershipContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    private val car get() = source("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
    private val web get() = source("app/src/main/java/com/aayoutube/app/WebHost.kt")

    @Test fun onlyTheActiveCarActivityBindsThePlayer() {
        val activity = car
        val bind = activity.substringAfter("private fun bindVideoToCarFrame(frame: FrameLayout) {")
            .substringBefore("private val relink")
        val gate = bind.indexOf("if (!WebHost.isActiveCarHost(carToken)) {")
        val attach = bind.indexOf("WebHost.attachToCarWindow(")
        assertTrue("inactive car host check must precede binding", gate in 0 until attach)
        assertFalse("car path must not use the phone attach", bind.contains("WebHost.attach("))
        assertTrue(activity.contains("WebHost.claimCarHost(carToken, onBecameActive)"))
        // Only onCreate claims new ownership; a late onStart must never
        // promote a replaced activity over the new one.
        assertEquals(1, activity.split("WebHost.claimCarHost(carToken, onBecameActive)").size - 1)
        assertEquals(1, activity.split("WebHost.resumeCarHost(carToken, onBecameActive)").size - 1)
        // Released by both onStop and onDestroy, never by a shared literal key.
        assertEquals(2, Regex("WebHost\\.releaseCarHost\\(carToken\\)").findAll(activity).count())
        val stop = activity.substringAfter("override fun onStop() {").substringBefore("override fun onDestroy()")
        assertTrue(stop.indexOf("WebHost.releaseCarHost(carToken)") < stop.indexOf("WebHost.showOnCar(false, carToken)"))
        assertTrue(stop.indexOf("WebHost.showOnCar(false, carToken)") < stop.indexOf("WebHost.requestReattachment()"))
        assertFalse(activity.contains("\"legacy-projection\")"))
    }

    @Test fun becomingActiveAgainRebindsAfterHostSettles() {
        val activity = car
        assertTrue(activity.contains("handler.postDelayed(rebindWhenActive, 1_200L)"))
        assertTrue(activity.contains("if (!carStarted || !WebHost.isActiveCarHost(carToken)) return@Runnable"))
        assertTrue(activity.contains("if (WebHost.fullscreenView.value == null) bindVideoToCarFrame(frame)"))
        assertTrue(activity.contains("WebHost.requestLink = relink\n        connectMedia()"))
        // onStop/onDestroy cancel it via removeCallbacksAndMessages before releasing.
        val destroy = activity.substringAfter("override fun onDestroy() {")
        assertTrue(destroy.indexOf("handler.removeCallbacksAndMessages(null)") <
            destroy.indexOf("WebHost.releaseCarHost(carToken)"))
    }

    @Test fun inactiveCarWindowNeverTakesTheFullscreenView() {
        val activity = car
        assertTrue(activity.contains("val activeHost by WebHost.activeCarHost.collectAsState()"))
        assertTrue(activity.contains("if (fullscreenView != null && activeHost == carToken) {"))
        val screens = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        assertTrue(screens.contains("val fullscreen = fullscreenView.takeIf { !projectedToCar || onCar }"))
        assertTrue("phone must not reparent the car's fullscreen view",
            screens.substringAfter("val full = fullscreen").contains("(full.parent as? ViewGroup)?.removeView(full)"))
    }

    @Test fun replacementCarWindowGetsFreshRendererAtSamePosition() {
        val host = web
        val bind = host.substringAfter("fun attachToCarWindow(context: Context, frame: ViewGroup): WebView {")
            .substringBefore("fun claimCarHost(")
        assertTrue(bind.contains("frame.windowToken?.let { System.identityHashCode(it) }"))
        assertTrue(bind.contains("PlayerDisplayPolicy.carRecreateReason("))
        assertTrue(bind.contains("webDisplayId, targetDisplay, carWindowId, targetWindow)"))
        assertTrue(bind.indexOf("recreateRenderer(context, targetDisplay ?: -1, reason)") < bind.indexOf("carWindowId = targetWindow"))
        // A new renderer, a phone attach and Quit all forget the car window.
        assertTrue(host.substringAfter("private fun ensureWeb(").substringBefore("val w = BackgroundWebView(context)")
            .contains("carWindowId = null"))
        assertTrue(host.substringAfter("fun attach(context: Context, destination: ViewGroup? = null): WebView {")
            .substringBefore("fun attachToCarWindow(").contains("carWindowId = null"))
        assertTrue(host.substringAfter("fun release(preserveCarOwners: Boolean = false) {")
            .contains("carWindowId = null"))
        // Position and play intent survive the renderer swap.
        val recreate = host.substringAfter("private fun recreateRenderer(").substringBefore("fun logCarRenderProbe()")
        assertTrue(recreate.contains("appendQueryParameter(\"t\", \"\${state.posMs / 1000}s\")"))
        assertTrue(recreate.contains("appendQueryParameter(\"aay\", if (play) \"1\" else \"paused\")"))
        assertTrue(recreate.contains("playingWhenCarLeft && now - carLeftAt < 8_000"))
        assertTrue(recreate.contains("now - userPausedAt > 8_000"))
        assertTrue(recreate.contains("previous.destroy()"))
    }

    @Test fun quitClearsCarHostOwnership() {
        val release = web.substringAfter("fun release(preserveCarOwners: Boolean = false) {")
            .substringBefore("fun onLinkUnbound()")
        val owners = release.substringAfter("if (!preserveCarOwners) {").substringBefore("}")
        assertTrue(owners.contains("carHosts.clear()"))
        assertTrue(owners.contains("activeCarHost.value = null"))
    }

    @Test fun oldCarInputDoesNotRemoveNewerTouchListener() {
        val input = car.substringAfter("private class LegacyCarInput(")
        assertTrue(input.contains("private val owners = java.util.WeakHashMap<WebView, LegacyCarInput>()"))
        assertTrue(input.contains("owners[web] = this"))
        val stop = input.substringAfter("fun stop() {").substringBefore("companion object")
        assertTrue(stop.contains("if (owners[web] === this) {"))
        assertTrue(stop.indexOf("if (owners[web] === this) {") < stop.indexOf("web.setOnTouchListener(null)"))
    }

    @Test fun blackScreenDiagnosticsReachLogcat() {
        val log = source("app/src/main/java/com/aayoutube/app/AppLog.kt")
        val add = log.substringAfter("fun add(tag: String, message: String) {")
        assertTrue("CAR lines are mirrored before the diagnostics-file switch",
            add.indexOf("if (tag == \"CAR\") runCatching {") < add.indexOf("if (context?.let { marker(it).isFile } != true) return"))
        assertTrue(add.contains("android.util.Log.i(LOGCAT_TAG, redact(message)"))
        assertTrue(log.contains("private const val LOGCAT_TAG = \"AAYouTube-CAR\""))
        val probe = web.substringAfter("fun logCarRenderProbe()").substringBefore("fun detach(")
        assertTrue(probe.contains("document.elementFromPoint(innerWidth/2,innerHeight/2)"))
        assertTrue(probe.contains("Object.getOwnPropertyDescriptor(Document.prototype,'hidden')"))
        assertTrue(probe.contains("webkitDecodedFrameCount"))
        assertTrue(probe.contains("classList.contains('aay-car')"))
        assertTrue(car.contains("longArrayOf(2_000L, 6_000L, 12_000L).forEach { handler.postDelayed(renderProbe, it) }"))
    }
}
