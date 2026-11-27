package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review P2/P3/P6: nothing outlives a renderer; heat and transitions degrade gracefully. */
class PlaybackLifecycleCleanupTest {
    private fun read(p: String) = listOf(File(p), File("../$p")).first { it.isFile }.readText()
    private val web get() = read("app/src/main/java/com/aayoutube/app/WebHost.kt")

    @Test fun releaseEndsHoldListenersWakeLockAndPendingResume() {
        val release = web.substringAfter("fun release(preserveCarOwners: Boolean = false) {")
            .substringBefore("w?.destroy()")
        assertTrue(release.contains("cancelOtherMediaWork(\"WebView released\")"))
        // ...and before the generation bump, so the old renderer's queued bridge
        // calls (postOtherMediaHold) are ignored by the new WebView.
        assertTrue(release.indexOf("cancelOtherMediaWork") < release.indexOf("webGeneration++"))
        val cancel = web.substringAfter("private fun cancelOtherMediaWork(reason: String) {").substringBefore("\n    }")
        assertTrue(cancel.contains("main.removeCallbacks(droppedResume)"))
        assertTrue(cancel.contains("main.removeCallbacks(droppedResumeJudge)"))
        assertTrue(cancel.contains("endOtherMediaHold(reason)"))
        assertTrue(cancel.contains("unregisterOtherMediaWatch()"))
        val unregister = web.substringAfter("private fun unregisterOtherMediaWatch() {").substringBefore("\n    }")
        assertTrue(unregister.contains("unregisterAudioPlaybackCallback(cb)"))
        assertTrue(unregister.contains("removeOnModeChangedListener(l)"))
        assertTrue(unregister.contains("holdWake.release()"))
        val hold = web.substringAfter("fun postOtherMediaHold(generation: Int) {")
            .substringBefore("private val droppedResume")
        assertTrue(hold.contains("main.post {"))
        assertTrue(hold.contains("if (generation != webGeneration) return@post"))
        assertTrue(hold.contains("!current.playing && !current.buffering && !current.userPaused"))
        assertTrue(hold.contains("!current.ended && current.id.isNotBlank() && playbackWanted &&\n                !NetworkMonitor.recentlyDisrupted() && qualityReloadDelay() == 0L && otherMediaActive()"))
        assertTrue(hold.contains("startOtherMediaHold(\"page watchdog\")"))
        assertTrue(hold.indexOf("if (generation != webGeneration) return@post") <
            hold.indexOf("startOtherMediaHold"))
        assertTrue(hold.contains("window.__aay.mediaHold(false)"))
    }

    @Test fun manualPauseOrPlayCancelsAPendingAutomaticDecision() {
        val publish = web.substringAfter("private fun publish(incoming: Snap) {").substringBefore("val key =")
        assertTrue(publish.contains("if (incoming.playing || incoming.buffering || incoming.id != previous.id || !playbackWanted) {"))
        assertTrue(publish.contains("main.removeCallbacks(droppedResume)"))
        assertTrue(publish.contains("main.removeCallbacks(droppedResumeJudge)"))
    }

    @Test fun thermalStatusReachesThePage() {
        assertTrue(web.contains("pm.addThermalStatusListener(app.mainExecutor, listener)"))
        assertTrue(web.contains("removeThermalStatusListener(listener)"))
        assertTrue(web.contains("if (thermalListener != null && web != null)"))
        assertTrue(web.contains("window.__aay.thermal(\$status)"))
        assertTrue(web.contains(".put(\"thermal\", thermalStatus())"))
    }

    @Test fun failedFrameCaptureFallsBackToTheFade() {
        val pane = read("app/src/main/java/com/aayoutube/app/Screens.kt")
        assertTrue(pane.contains("if (frame == null) {"))
        assertTrue(pane.contains("never animate a black box"))
    }
}
