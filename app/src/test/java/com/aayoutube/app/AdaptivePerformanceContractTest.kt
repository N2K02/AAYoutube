package com.aayoutube.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Wiring of the adaptive performance engine into the players, the service and the page. */
class AdaptivePerformanceContractTest {
    private fun read(path: String): String =
        listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }.readText()

    private fun kt(name: String) = read("java/com/aayoutube/app/$name")

    @Test fun phoneMirrorConsultsTheEngineBeforeLoadingOrSyncing() {
        val mirror = kt("PhoneMirror.kt")
        val tick = mirror.substringAfter("private val syncTick").substringBefore("private fun enterSuspension")
        val evaluate = tick.indexOf("PerformanceMonitor.evaluate()")
        assertTrue(evaluate >= 0)
        assertTrue(evaluate < tick.indexOf("gate.update("))
        assertTrue(tick.contains("memoryLevel(), perf)"))
        assertTrue(tick.indexOf("gate.update(") < tick.indexOf("load(w, s, now)"))
        // The ceiling follows the phone player's real size.
        assertTrue(tick.contains("PhoneMirrorPolicy.viewportCap("))
    }

    @Test fun hiddenSecondaryRendererIsReleasedSoonerUnderPressureAndCounted() {
        val mirror = kt("PhoneMirror.kt")
        assertTrue(mirror.contains("AdaptivePerformancePolicy.hiddenSecondaryReleaseMs(PerformanceMonitor.evaluate())"))
        assertTrue(mirror.contains("main.postDelayed(idleRelease, hiddenReleaseMs)"))
        assertTrue(mirror.contains("PerformanceMonitor.diagnostics.secondaryAllocations++"))
        assertTrue(mirror.contains("PerformanceMonitor.diagnostics.secondaryReleases++"))
    }

    @Test fun primaryRendererOwnershipIsCountedAndQuitResetsTheEngine() {
        val host = kt("WebHost.kt")
        assertTrue(host.contains("PerformanceMonitor.diagnostics.rendererAllocations++"))
        assertEquals(2, Regex("PerformanceMonitor\\.diagnostics\\.rendererReleases\\+\\+").findAll(host).count())
        val release = host.substringAfter("fun release(preserveCarOwners: Boolean = false) {").substringBefore("main.removeCallbacks(freezeSoon)")
        assertTrue(release.contains("PhoneMirror.release()"))
        assertTrue(release.contains("PerformanceMonitor.reset()"))
        // The page learns the level at load and on every change.
        assertTrue(host.contains(".put(\"perfLevel\", PerformanceMonitor.level.value.ordinal)"))
        assertTrue(host.contains("window.__aay.perfLevel(\${level.ordinal},"))
        // Thermal changes and dropped frames feed the engine.
        assertTrue(host.contains("PerformanceMonitor.onThermalChanged()"))
        assertTrue(host.contains("PerformanceMonitor.onDroppedFrames("))
    }

    @Test fun engineLogsTransitionsAndRecoversWithoutOtherActivity() {
        val monitor = kt("PerformanceMonitor.kt")
        assertTrue(monitor.contains("AppLog.add(\"PERF-STATE\""))
        assertTrue(monitor.contains("if (governor.level != PerfLevel.NORMAL) main.postDelayed(recoveryTick, RECOVERY_TICK_MS)"))
        assertTrue(monitor.contains("main.removeCallbacks(recoveryTick)"))
        // Battery comes from the sticky broadcast: no receiver stays registered.
        assertTrue(monitor.contains("registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))"))
        assertTrue(kt("AAYoutubeApp.kt").contains("PerformanceMonitor.onTrimMemory()"))
    }

    @Test fun wakeLockIsAlwaysAcquiredWithATimeoutAndBoundedInRecovery() {
        val service = kt("PlaybackService.kt")
        assertTrue(service.contains("lock.acquire(WakeLockPolicy.TIMEOUT_MS)"))
        assertFalse("no unbounded acquisition", service.contains("lock.acquire()"))
        assertTrue(service.contains("WakeLockPolicy.hold(true, snapshot.playing, wakeStalledSince, now)"))
        assertTrue(service.contains("releaseWakeLock(\"stalled-recovery\")"))
        // The health tick refreshes or ends the hold even when no state arrives.
        val health = service.substringAfter("private val healthTick").substringBefore("private fun startHealthTicker")
        assertTrue(health.contains("updatePlaybackWakeLock(ForegroundDemotePolicy.wantsWakeLock(snapshot, recoveringPlayback))"))
        assertTrue(service.contains("wakeHeldMs="))
    }

    @Test fun positionPersistenceUsesThePolicyAndQuitStillSavesAtOnce() {
        val service = kt("PlaybackService.kt")
        assertTrue(service.contains("PositionPersistPolicy.shouldSave(lastSavedId, lastSavedPosition, lastSavedAt, s, savedNow, PerformanceMonitor::screenOn)"))
        assertFalse(service.contains("kotlin.math.abs(lastSavedPosition - s.posMs) >= 5_000L"))
        // Quit publishes a paused, user-paused snapshot: saved immediately by the policy.
        val quit = service.substringAfter("private fun requestQuit()").substringBefore("override fun onStartCommand")
        assertTrue(quit.indexOf("userPaused = true)") < quit.indexOf("publish(snapshot)"))
    }

    @Test fun oneAudibleEngineAndNoNativeAudioFocusRequests() {
        // Chromium's media session owns audio focus for the single audible player; the app never
        // requests focus itself (no duplicate requests on mode or quality changes).
        val dir = listOf(File("src/main/java/com/aayoutube/app"), File("app/src/main/java/com/aayoutube/app")).first { it.isDirectory }
        dir.listFiles()!!.filter { it.extension == "kt" }.forEach { f ->
            val t = f.readText()
            assertFalse(f.name, t.contains("requestAudioFocus("))
            assertFalse(f.name, t.contains("AudioFocusRequest.Builder"))
        }
        // The phone copy stays natively muted.
        assertTrue(kt("PhoneMirror.kt").contains("WebViewCompat.setAudioMuted(w, true)"))
    }

    @Test fun composePositionTicksStayInSmallScopes() {
        val screens = kt("Screens.kt")
        assertTrue(screens.contains("WebHost.snapshot.map { it.withoutPosition() }.distinctUntilChanged()"))
        assertTrue(screens.contains("LiveLyricsOverlay(foundLyrics)"))
        assertTrue(screens.contains("private fun LiveProgressLine(snap: Snap, visible: Boolean, modifier: Modifier)"))
        // The live clock stops while buffering.
        assertTrue(screens.contains("LaunchedEffect(snap.playing, snap.buffering, snap.id, visible)"))
        assertTrue(screens.contains("if (!snap.playing || snap.buffering || !visible) return@LaunchedEffect"))
        assertFalse(screens.contains("LaunchedEffect(snap, paneShown)"))
    }

    @Test fun pageScriptReducesWorkWithoutTouchingPlaybackEvents() {
        val js = read("assets/aay.js")
        // Level handler and ceilings.
        assertTrue(js.contains("var PERF_CAP_H = [0, 0, 1080, 720], PERF_WORK_SCALE = [1, 2, 2, 3];"))
        assertTrue(js.contains("A.perfLevel = function (level, diagMs)"))
        // Hidden page: observer disconnected, slow keep-alive tick instead.
        assertTrue(js.contains("pageObserver.disconnect(); pageObserved = false;"))
        assertTrue(js.contains("hiddenTickTimer = setInterval(schedTick, 12000)"))
        // No repeated identical bridge reports; changes still immediate.
        assertTrue(js.contains("if (json === lastStateJson && sentAt - lastStateAt < STATE_REPEAT_MS)"))
        // Periodic position report pauses while buffering/seeking.
        assertTrue(js.contains("if (v && !v.paused && !v.seeking && v.readyState >= 3) schedReport(0);"))
        // GPU: ambient/cinematic layers and backdrop blur are off.
        assertTrue(js.contains("backdrop-filter:none!important"))
        assertTrue(js.contains("CSS_VFIT + CSS_EFFECTS"))
        // Playback events remain registered.
        assertTrue(js.contains("v.addEventListener('timeupdate'"))
        // Adaptive diagnostics window with decoded/presented frames and stall estimates.
        assertTrue(js.contains("decoded: df, presented: Math.max(0, df - dd)"))
        assertTrue(js.contains("var perfTimer = setInterval(perfSample, perfState.diagMs);"))
    }

    @Test fun hardwareAccelerationIsPreserved() {
        val manifest = read("AndroidManifest.xml")
        assertFalse(manifest.contains("android:hardwareAccelerated=\"false\""))
        listOf("WebHost.kt", "PhoneMirror.kt", "BackgroundWebView.kt").forEach {
            assertFalse(it, kt(it).contains("LAYER_TYPE_SOFTWARE"))
        }
    }
}
