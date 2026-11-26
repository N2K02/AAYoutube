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
        assertTrue(monitor.contains("if (result.needsRecoveryTick) main.postDelayed(recoveryTick, RECOVERY_TICK_MS)"))
        assertTrue(monitor.contains("main.removeCallbacks(recoveryTick)"))
        // Battery comes from the sticky broadcast: no receiver stays registered.
        assertTrue(monitor.contains("registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))"))
        assertTrue(kt("AAYoutubeApp.kt").contains("PerformanceMonitor.onTrimMemory()"))
    }

    @Test fun monitoringRunsOnlyDuringAPlaybackSession() {
        val monitor = kt("PerformanceMonitor.kt")
        // Late thermal/memory/frame callbacks after Quit are ignored and cannot restart the recovery tick.
        assertTrue(monitor.contains("fun onThermalChanged() { if (session.active) evaluate(force = true) }"))
        assertTrue(monitor.contains("fun onTrimMemory() { if (session.active) evaluate(force = true) }"))
        assertTrue(monitor.contains("private val recoveryTick = Runnable { if (session.active) evaluate(force = true) }"))
        val evaluate = monitor.substringAfter("fun evaluate(force: Boolean = false): PerfLevel {")
        assertTrue(evaluate.indexOf("if (!session.active) {") < evaluate.indexOf("SystemClock.elapsedRealtime()"))
        assertTrue(monitor.contains("session.stop()"))
        // The session starts with each primary renderer (dropping the old renderer's measurements)
        // and stops with any release, car-preserving or not.
        val host = kt("WebHost.kt")
        assertTrue(host.indexOf("PerformanceMonitor.diagnostics.rendererAllocations++") <
            host.indexOf("PerformanceMonitor.onPrimaryRendererCreated()"))
        val release = host.substringAfter("fun release(preserveCarOwners: Boolean = false) {").substringBefore("main.removeCallbacks(freezeSoon)")
        val guarded = release.substringAfter("if (!preserveCarOwners) {").substringBefore("PhoneMirror.release()\n        }")
        assertFalse(guarded.contains("PerformanceMonitor.reset()"))
        assertTrue(release.substringAfter("PhoneMirror.release()\n        }").contains("PerformanceMonitor.reset()"))
    }

    @Test fun pressureMovesAScheduledHiddenReleaseEarlier() {
        val mirror = kt("PhoneMirror.kt")
        assertTrue(kt("PerformanceMonitor.kt").contains("PhoneMirror.onPerformanceLevel(t.to)"))
        val hook = mirror.substringAfter("internal fun onPerformanceLevel(level: PerfLevel) {").substringBefore("\n    }\n")
        assertTrue(hook.contains("if (visible || web == null || releaseDeadline == 0L) return"))
        assertTrue(hook.contains("HiddenReleasePolicy.deadline(hiddenAt, releaseDeadline, level)"))
        assertTrue(hook.contains("if (next >= releaseDeadline) return"))
        assertTrue(hook.contains("main.removeCallbacks(idleRelease)"))
        // Shown again or torn down: no stale deadline.
        assertEquals(2, Regex("main\\.removeCallbacks\\(idleRelease\\)\\n        releaseDeadline = 0L").findAll(mirror).count())
    }

    @Test fun nativeMuteFailureKeepsRendererCountersBalanced() {
        val create = kt("PhoneMirror.kt").substringAfter("private fun create(context: Context): WebView?")
            .substringBefore("token = java.util.UUID")
        val alloc = create.indexOf("PerformanceMonitor.diagnostics.secondaryAllocations++")
        val failure = create.substringAfter("WebViewCompat.isAudioMuted(w)")
        assertTrue(alloc in 0 until create.indexOf("WebViewCompat.setAudioMuted(w, true)"))
        assertTrue(failure.indexOf("w.destroy()") < failure.indexOf("PerformanceMonitor.diagnostics.secondaryReleases++"))
        assertTrue(failure.indexOf("PerformanceMonitor.diagnostics.secondaryReleases++") < failure.indexOf("return null"))
    }

    @Test fun wakeLockIsAlwaysAcquiredWithATimeoutAndBoundedInRecovery() {
        val service = kt("PlaybackService.kt")
        assertTrue(service.contains("lock.acquire(WakeLockPolicy.TIMEOUT_MS)"))
        assertFalse("no unbounded acquisition", service.contains("lock.acquire()"))
        assertTrue(service.contains("WakeLockPolicy.hold(true, mediaClock.stalledSince(now), now)"))
        // Stalls are judged from the media clock fed by every state report, not from playing=true.
        assertTrue(service.contains("mediaClock.onSample(s.id, s.posMs, ForegroundDemotePolicy.wantsWakeLock(s, recoveringPlayback), s.buffering,"))
        assertFalse(service.contains("wakeStalledSince"))
        assertTrue(service.contains("releaseWakeLock(\"stalled-recovery\")"))
        // The health tick refreshes or ends the hold even when no state arrives.
        val health = service.substringAfter("private val healthTick").substringBefore("private fun startHealthTicker")
        assertTrue(health.contains("updatePlaybackWakeLock(ForegroundDemotePolicy.wantsWakeLock(snapshot, recoveringPlayback))"))
        assertTrue(service.contains("wakeHeldMs="))
    }

    @Test fun stallReleaseHasItsOwnDeadline() {
        val service = kt("PlaybackService.kt")
        val update = service.substringAfter("private fun updatePlaybackWakeLock(active: Boolean) {")
            .substringBefore("// A transient WebView pause")
        // Armed on every hold decision, cancelled when not wanted or released for a stall.
        assertTrue(update.indexOf("scheduleStallRelease(now)") in 0 until update.indexOf("runCatching {"))
        assertEquals(2, Regex("cancelStallRelease\\(\\)").findAll(update).count())
        assertTrue(service.contains("val due = WakeLockPolicy.releaseDueAt(mediaClock.progressAt())"))
        assertTrue(service.contains("if (due == stallReleaseAt) return"))
        assertTrue(service.contains("handler.postDelayed(stallRelease, WakeLockPolicy.delayUntil(due, now))"))
        // The deadline re-evaluates the decision rather than releasing blindly.
        val runnable = service.substringAfter("private val stallRelease = Runnable {").substringBefore("\n    }")
        assertTrue(runnable.contains("updatePlaybackWakeLock(ForegroundDemotePolicy.wantsWakeLock(snapshot, recoveringPlayback))"))
    }

    @Test fun miniSizeAndFrameCallbacksAreSelfContained() {
        val js = read("assets/aay.js")
        assertTrue(js.contains("if (on) miniSized.timer = setTimeout(miniSizeTimerFired, MINI_SIZE_SETTLE_MS);"))
        assertTrue(js.contains("miniSized.retries < MINI_SIZE_RETRIES"))
        assertTrue(js.contains("if (playing && !perf.rvfcHandle && typeof v.requestVideoFrameCallback === 'function')"))
        assertTrue(js.contains("perf.rvfcSrc.cancelVideoFrameCallback(perf.rvfcHandle)"))
        assertTrue(js.contains("if (src !== perf.rvfcSrc) return; // obsolete element"))
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
        assertTrue(screens.contains("private fun LiveProgressLine(visible: Boolean, modifier: Modifier)"))
        // WebPane (the player) also collects the position-free snapshot; only the line collects positions.
        val pane = screens.substringAfter("fun WebPane(").substringBefore("ProjectedPhonePane(snap")
        assertTrue(pane.contains("WebHost.snapshot.map { it.withoutPosition() }.distinctUntilChanged()"))
        assertFalse(pane.contains("val snap by WebHost.snapshot.collectAsState()"))
        val line = screens.substringAfter("private fun LiveProgressLine(").substringBefore("private fun LiveLyricsOverlay(")
        assertTrue(line.contains("val snap by WebHost.snapshot.collectAsState()"))
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
        assertTrue(js.contains("decoded: df, presentedEst: Math.max(0, df - dd), displayed: displayed,"))
        assertTrue(js.contains("v.requestVideoFrameCallback(function (t, md) {"))
        assertTrue(js.contains("clockStallMs: clockStallMs,"))
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
