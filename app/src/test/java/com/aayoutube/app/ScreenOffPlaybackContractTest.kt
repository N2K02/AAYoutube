package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Guards screen-off audio continuity and explicit pause/quit cleanup. */
class ScreenOffPlaybackContractTest {
    private fun app(name: String): String = sequenceOf(
        File("app/src/main/java/com/aayoutube/app/$name"),
        File("../app/src/main/java/com/aayoutube/app/$name")
    ).first { it.isFile }.readText()

    private fun playerScript(): String = sequenceOf(
        File("app/src/main/assets/aay.js"),
        File("../app/src/main/assets/aay.js")
    ).first { it.isFile }.readText()

    @Test fun cpuWakeLockFollowsPlaybackAndRecoveryAndReleasesOnShutdown() {
        val service = app("PlaybackService.kt")
        assertTrue(service.contains("PowerManager.PARTIAL_WAKE_LOCK"))
        assertTrue(service.contains("setReferenceCounted(false)"))
        assertTrue(service.contains("updatePlaybackWakeLock(s.playing || recoveringPlayback)"))
        assertTrue(service.contains("if (!active || quitRequested || shuttingDown)"))
        assertTrue(service.contains("if (lock.isHeld) lock.release()"))
        val destruction = service.substringAfter("override fun onDestroy() {")
        assertTrue(destruction.contains("updatePlaybackWakeLock(false)"))
        assertTrue(service.contains("snapshot = snapshot.copy(playing = false, buffering = false, recovering = false, userPaused = true)"))
        for (transport in listOf("onPause", "onStop")) {
            val section = service.substringAfter("override fun $transport() {").substringBefore("override fun ")
            assertTrue(section.contains("recovery.cancel()"))
            assertTrue(section.contains("recoveringPlayback = false"))
        }
    }

    @Test fun emuiTaskRemovalCannotTerminateAnActiveMediaService() {
        val service = app("PlaybackService.kt")
        val task = service.substringAfter("override fun onTaskRemoved(rootIntent: Intent?) {")
            .substringBefore("override fun onDestroy()")
        assertFalse(task.contains("stopSelf()"))
        assertTrue(task.contains("super.onTaskRemoved(rootIntent)"))
        assertTrue(service.contains("return START_STICKY"))
        assertTrue(service.contains("if (intent == null && !quitRequested) {\n            AppLog.add(\"LIFECYCLE\", \"sticky service recreated after process/service stop\")\n            restoreSystemKilledPlayback()"))
    }

    @Test fun systemRestartRecordIsRecentAndNeverOverridesPauseQuitOrDisabledPlayback() {
        val service = app("PlaybackService.kt")
        val restore = service.substringAfter("private fun restoreSystemKilledPlayback() {")
            .substringBefore("private var playbackWakeLock")
        assertTrue(restore.contains("age !in 0L..300_000L"))
        assertTrue(restore.contains("!Features.current.backgroundPlay"))
        assertTrue(restore.contains("send(Msg.PLAY"))
        assertTrue(service.contains("if (s.userPaused || s.ended || s.id.isBlank() || quitRequested)"))
        assertTrue(service.contains("resumePrefs.edit().clear().apply()"))
        assertTrue(service.contains("if (!s.playing) return"))
    }

    @Test fun nativeProbeRunsOnlyForWantedPlaybackAndStopsOnRelease() {
        val host = app("WebHost.kt")
        val probe = host.substringAfter("private val backgroundPlaybackProbe")
            .substringBefore("private var lastLogged")
        assertTrue(probe.contains("!playbackWanted"))
        assertTrue(probe.contains("!Features.current.backgroundPlay"))
        assertTrue(probe.contains("main.postDelayed(this, 15_000L)"))
        assertTrue(probe.contains("main.removeCallbacks(backgroundPlaybackProbe)"))
        assertTrue(host.contains("snapshot.value = s\n        updateBackgroundPlaybackProbe()"))
        val release = host.substringAfter("fun release(preserveCarOwners: Boolean = false) {")
        assertTrue(release.contains("main.removeCallbacks(backgroundPlaybackProbe)"))
        assertTrue(release.contains("playbackWanted = false"))
    }

    @Test fun javascriptProbeNeverOverridesIntentionalPauseOrDisabledBackgroundPlay() {
        val script = playerScript()
        assertTrue(script.contains("A.checkBackgroundPlayback = backgroundWatchdog;"))
        val watchdog = script.substringAfter("function backgroundWatchdog() {")
            .substringBefore("A.checkBackgroundPlayback = backgroundWatchdog;")
        assertTrue(watchdog.contains("if (PAUSED) {"))
        assertTrue(watchdog.contains("if (bgWatch.explicitPause || !bgWatch.wanted) return;"))
        assertTrue(watchdog.contains("CFG.backgroundPlay === false"))
        assertTrue(watchdog.contains("if (!v || !id || v.ended) return;"))
    }
}
