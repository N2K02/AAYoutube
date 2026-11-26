package com.aayoutube.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePerformanceTest {
    private fun level(s: PerfSignals) = AdaptivePerformancePolicy.assess(s).level

    @Test fun calmDeviceIsNormal() {
        assertEquals(PerfLevel.NORMAL, level(PerfSignals()))
        assertEquals(PerfLevel.NORMAL, level(PerfSignals(batteryTempDeciC = 320, primaryBufferingMs = 1_500)))
    }

    @Test fun thermalStatusMapsToProgressiveLevels() {
        assertEquals(PerfLevel.ELEVATED, level(PerfSignals(thermalStatus = 1)))
        assertEquals(PerfLevel.HIGH, level(PerfSignals(thermalStatus = 2)))
        assertEquals(PerfLevel.SEVERE, level(PerfSignals(thermalStatus = 3)))
        assertEquals(PerfLevel.SEVERE, level(PerfSignals(thermalStatus = 6)))
    }

    @Test fun batteryTemperatureAndChargingRaiseTheLevel() {
        assertEquals(PerfLevel.NORMAL, level(PerfSignals(batteryTempDeciC = 395)))
        assertEquals(PerfLevel.ELEVATED, level(PerfSignals(batteryTempDeciC = 400)))
        assertEquals(PerfLevel.HIGH, level(PerfSignals(batteryTempDeciC = 430)))
        assertEquals(PerfLevel.SEVERE, level(PerfSignals(batteryTempDeciC = 460)))
        // Charging adds its own heat: 1.5 °C earlier.
        assertEquals(PerfLevel.ELEVATED, level(PerfSignals(batteryTempDeciC = 390, charging = true)))
        assertEquals(PerfLevel.SEVERE, level(PerfSignals(batteryTempDeciC = 445, charging = true)))
        // Unknown temperature never counts.
        assertEquals(PerfLevel.NORMAL, level(PerfSignals(batteryTempDeciC = PerfSignals.UNKNOWN_TEMP, charging = true)))
    }

    @Test fun memoryCpuBufferingAndDroppedFrames() {
        assertEquals(PerfLevel.ELEVATED, level(PerfSignals(memory = MemoryLevel.LOW)))
        assertEquals(PerfLevel.SEVERE, level(PerfSignals(memory = MemoryLevel.CRITICAL)))
        assertEquals(PerfLevel.ELEVATED, level(PerfSignals(cpuPressure = true)))
        assertEquals(PerfLevel.ELEVATED, level(PerfSignals(primaryBufferingMs = 4_000)))
        assertEquals(PerfLevel.HIGH, level(PerfSignals(primaryBufferingMs = 15_000)))
        assertEquals(PerfLevel.ELEVATED, level(PerfSignals(droppedFrames = PerfLevel.ELEVATED)))
        assertEquals(PerfLevel.HIGH, level(PerfSignals(droppedFrames = PerfLevel.HIGH)))
    }

    @Test fun strongestReasonWins() {
        val a = AdaptivePerformancePolicy.assess(PerfSignals(thermalStatus = 1, memory = MemoryLevel.CRITICAL, cpuPressure = true))
        assertEquals(PerfLevel.SEVERE, a.level)
        assertEquals("memory=critical", a.reason)
    }

    @Test fun escalationIsImmediate() {
        val g = AdaptivePerformanceGovernor(calmStepMs = 20_000, minDwellMs = 10_000)
        assertNull(g.update(PerfSignals(), 0))
        val t = g.update(PerfSignals(thermalStatus = 3), 1_000)
        assertNotNull(t)
        assertEquals(PerfLevel.NORMAL, t!!.from)
        assertEquals(PerfLevel.SEVERE, t.to)
        assertEquals(PerfLevel.SEVERE, g.level)
    }

    @Test fun recoveryIsGradualOneLevelPerCalmPeriod() {
        val g = AdaptivePerformanceGovernor(calmStepMs = 20_000, minDwellMs = 10_000)
        g.update(PerfSignals(thermalStatus = 3), 0)
        // Cool: nothing changes before a full calm period.
        assertNull(g.update(PerfSignals(), 5_000))
        assertNull(g.update(PerfSignals(), 24_000))
        assertEquals(PerfLevel.SEVERE, g.level)
        assertEquals(PerfLevel.HIGH, g.update(PerfSignals(), 25_000)!!.to)
        // The next step needs another calm period of its own.
        assertNull(g.update(PerfSignals(), 30_000))
        assertEquals(PerfLevel.ELEVATED, g.update(PerfSignals(), 45_000)!!.to)
        assertEquals(PerfLevel.NORMAL, g.update(PerfSignals(), 65_000)!!.to)
        assertEquals(4, g.transitions)
    }

    @Test fun hoveringSignalCannotFlapTheLevel() {
        val g = AdaptivePerformanceGovernor(calmStepMs = 20_000, minDwellMs = 10_000)
        var changes = 0
        // CPU pressure toggling every 5 s for two minutes.
        for (i in 0..24) {
            if (g.update(PerfSignals(cpuPressure = i % 2 == 0), i * 5_000L) != null) changes++
        }
        assertEquals(1, changes)
        assertEquals(PerfLevel.ELEVATED, g.level)
    }

    @Test fun pressureDuringRecoveryRestartsTheCalmPeriod() {
        val g = AdaptivePerformanceGovernor(calmStepMs = 20_000, minDwellMs = 10_000)
        g.update(PerfSignals(thermalStatus = 2), 0)
        g.update(PerfSignals(), 15_000)
        g.update(PerfSignals(thermalStatus = 2), 18_000)  // pressure again
        assertNull(g.update(PerfSignals(), 30_000))
        assertNull(g.update(PerfSignals(), 49_000))
        assertEquals(PerfLevel.ELEVATED, g.update(PerfSignals(), 50_000)!!.to)
    }

    @Test fun recoveryRespectsMinimumDwell() {
        val g = AdaptivePerformanceGovernor(calmStepMs = 1_000, minDwellMs = 10_000)
        g.update(PerfSignals(thermalStatus = 2), 0)
        g.update(PerfSignals(), 1_000)
        assertNull(g.update(PerfSignals(), 5_000))
        assertEquals(PerfLevel.ELEVATED, g.update(PerfSignals(), 10_000)!!.to)
    }

    @Test fun resetReturnsToNormal() {
        val g = AdaptivePerformanceGovernor()
        g.update(PerfSignals(thermalStatus = 3), 0)
        g.reset()
        assertEquals(PerfLevel.NORMAL, g.level)
        assertEquals(0, g.transitions)
    }

    @Test fun droppedFramesNeedConsecutiveWindows() {
        val t = DroppedFrameTracker()
        assertEquals(PerfLevel.NORMAL, t.update(40))
        assertEquals(PerfLevel.HIGH, t.update(40))
        assertEquals(PerfLevel.NORMAL, t.update(2))
        assertEquals(PerfLevel.NORMAL, t.update(12))
        assertEquals(PerfLevel.ELEVATED, t.update(30))
        assertEquals(PerfLevel.HIGH, t.update(30))
        // Unknown (-1, audio only) is not a dropping window.
        assertEquals(PerfLevel.NORMAL, t.update(-1))
    }

    @Test fun sustainedSignal() {
        val s = SustainedSignal(3)
        assertFalse(s.update(true)); assertFalse(s.update(true)); assertTrue(s.update(true))
        assertFalse(s.update(false))
    }

    @Test fun decisionsPerLevel() {
        assertEquals("medium", AdaptivePerformancePolicy.secondaryCap(PerfLevel.NORMAL, "medium"))
        assertEquals("small", AdaptivePerformancePolicy.secondaryCap(PerfLevel.ELEVATED, "medium"))
        assertNull(AdaptivePerformancePolicy.secondaryCap(PerfLevel.HIGH, "medium"))
        assertNull(AdaptivePerformancePolicy.secondaryCap(PerfLevel.SEVERE, "small"))
        assertEquals(0, AdaptivePerformancePolicy.primaryCapHeight(PerfLevel.NORMAL))
        assertEquals(0, AdaptivePerformancePolicy.primaryCapHeight(PerfLevel.ELEVATED))
        assertEquals(1080, AdaptivePerformancePolicy.primaryCapHeight(PerfLevel.HIGH))
        assertEquals(720, AdaptivePerformancePolicy.primaryCapHeight(PerfLevel.SEVERE))
        assertEquals(PhoneMirrorPolicy.HIDDEN_RELEASE_MS, AdaptivePerformancePolicy.hiddenSecondaryReleaseMs(PerfLevel.NORMAL))
        assertTrue(AdaptivePerformancePolicy.hiddenSecondaryReleaseMs(PerfLevel.HIGH) <
            AdaptivePerformancePolicy.hiddenSecondaryReleaseMs(PerfLevel.ELEVATED))
        assertEquals(1, AdaptivePerformancePolicy.pageWorkScale(PerfLevel.NORMAL))
        assertEquals(3, AdaptivePerformancePolicy.pageWorkScale(PerfLevel.SEVERE))
        assertEquals(30_000L, AdaptivePerformancePolicy.diagnosticsIntervalMs(PerfLevel.NORMAL, screenOn = true))
        assertEquals(60_000L, AdaptivePerformancePolicy.diagnosticsIntervalMs(PerfLevel.HIGH, screenOn = true))
        assertEquals(120_000L, AdaptivePerformancePolicy.diagnosticsIntervalMs(PerfLevel.NORMAL, screenOn = false))
        assertEquals(60_000L, AdaptivePerformancePolicy.healthIntervalMs(true))
        assertEquals(180_000L, AdaptivePerformancePolicy.healthIntervalMs(false))
    }

    @Test fun mirrorGateFollowsTheEngineLevel() {
        val g = MirrorLoadGate(stableMs = 10_000, bufferingSuspendMs = 15_000)
        assertEquals(MirrorLoadGate.State.NORMAL, g.update(0, false, 0, perf = PerfLevel.NORMAL))
        assertEquals(MirrorLoadGate.State.DEGRADED, g.update(0, false, 1_000, perf = PerfLevel.ELEVATED))
        assertEquals(MirrorLoadGate.State.SUSPENDED, g.update(0, false, 2_000, perf = PerfLevel.HIGH))
        assertEquals(MirrorLoadGate.State.SUSPENDED, g.update(0, false, 3_000, perf = PerfLevel.SEVERE))
        // Back to NORMAL from the engine: still a calm period before the copy returns, then reduced quality.
        assertEquals(MirrorLoadGate.State.SUSPENDED, g.update(0, false, 10_000, perf = PerfLevel.NORMAL))
        assertEquals(MirrorLoadGate.State.DEGRADED, g.update(0, false, 14_000, perf = PerfLevel.NORMAL))
        assertEquals(MirrorLoadGate.State.NORMAL, g.update(0, false, 24_000, perf = PerfLevel.NORMAL))
    }

    @Test fun secondaryCeilingFollowsActualPlayerSize() {
        // Small floating player: 240p.
        assertEquals("small", PhoneMirrorPolicy.viewportCap(videoOnly = true, degraded = false, pip = false, widthPx = 480, heightPx = 270))
        // Larger video-only view: 360p rather than a soft 240p.
        assertEquals("medium", PhoneMirrorPolicy.viewportCap(videoOnly = true, degraded = false, pip = false, widthPx = 1080, heightPx = 608))
        // Full watch page: the 16:9 frame of the width counts, not the page height.
        assertEquals("medium", PhoneMirrorPolicy.viewportCap(videoOnly = false, degraded = false, pip = false, widthPx = 1344, heightPx = 2992))
        assertEquals("small", PhoneMirrorPolicy.viewportCap(videoOnly = false, degraded = false, pip = false, widthPx = 500, heightPx = 2000))
        // Pressure, PiP and unknown size keep the established ceilings.
        assertEquals("small", PhoneMirrorPolicy.viewportCap(videoOnly = false, degraded = true, pip = false, widthPx = 1344, heightPx = 2992))
        assertEquals("medium", PhoneMirrorPolicy.viewportCap(videoOnly = true, degraded = false, pip = true, widthPx = 300, heightPx = 170))
        assertEquals("small", PhoneMirrorPolicy.viewportCap(videoOnly = true, degraded = false, pip = false, widthPx = 0, heightPx = 0))
        // Never above 360p for the secondary copy.
        assertTrue((PhoneMirrorPolicy.QUALITY_HEIGHT[PhoneMirrorPolicy.viewportCap(false, false, false, 4000, 4000)] ?: 0) <= 360)
    }

    @Test fun wakeLockIsBoundedByTheStalledMediaClock() {
        assertFalse(WakeLockPolicy.hold(wanted = false, stalledSince = 0, nowMs = 0))
        assertTrue(WakeLockPolicy.hold(wanted = true, stalledSince = 0, nowMs = 1_000))
        assertTrue(WakeLockPolicy.hold(wanted = true, stalledSince = 1_000, nowMs = 1_000 + WakeLockPolicy.STALL_LIMIT_MS - 1))
        assertFalse(WakeLockPolicy.hold(wanted = true, stalledSince = 1_000, nowMs = 1_000 + WakeLockPolicy.STALL_LIMIT_MS))
        assertTrue(WakeLockPolicy.needsRefresh(0, 5))
        assertFalse(WakeLockPolicy.needsRefresh(1_000, 1_000 + WakeLockPolicy.REFRESH_MS - 1))
        assertTrue(WakeLockPolicy.needsRefresh(1_000, 1_000 + WakeLockPolicy.REFRESH_MS))
        assertTrue(WakeLockPolicy.REFRESH_MS < WakeLockPolicy.TIMEOUT_MS)
    }

    /** Simulates the service: one state report, then the wake-lock decision. */
    private fun holds(clock: MediaClockWatch, now: Long) =
        WakeLockPolicy.hold(true, clock.stalledSince(now), now)

    @Test fun bufferingPlayerReportingPlayingTrueLosesTheWakeLockAfterTheLimit() {
        val c = MediaClockWatch()
        c.onSample("v", 10_000, wantsPlay = true, isBuffering = false, nowMs = 0)
        c.onSample("v", 20_000, wantsPlay = true, isBuffering = false, nowMs = 10_000)
        // Stuck buffering: playing=true keeps arriving, the position never moves.
        var t = 10_000L
        while (t < 10_000 + WakeLockPolicy.STALL_LIMIT_MS + 20_000) {
            t += 15_000
            c.onSample("v", 20_000, wantsPlay = true, isBuffering = true, nowMs = t)
        }
        assertEquals(10_000L, c.stalledSince(t))
        assertEquals(MediaClockWatch.State.STALLED, c.state(t))
        assertFalse(holds(c, t))
        // The clock moves again: held at once.
        c.onSample("v", 21_000, wantsPlay = true, isBuffering = false, nowMs = t + 1_000)
        assertEquals(0L, c.stalledSince(t + 1_000))
        assertTrue(holds(c, t + 1_000))
    }

    @Test fun stallReleaseDeadlineIsIndependentOfTheHealthInterval() {
        val c = MediaClockWatch()
        val start = 5_000L
        c.onSample("v", 0, wantsPlay = true, isBuffering = false, nowMs = start)
        val due = WakeLockPolicy.releaseDueAt(c.progressAt())
        assertEquals(start + WakeLockPolicy.STALL_LIMIT_MS, due)
        // Screen off: health ticks 180 s apart would check at start+180 s and start+360 s; the
        // deadline fires exactly at the limit instead.
        val healthTick = start + AdaptivePerformancePolicy.healthIntervalMs(screenOn = false)
        assertTrue(due <= healthTick)
        assertTrue(holds(c, due - 1))
        assertFalse(holds(c, due))
        assertEquals(WakeLockPolicy.STALL_LIMIT_MS, WakeLockPolicy.delayUntil(due, start))
        assertEquals("overdue runs at once", 0L, WakeLockPolicy.delayUntil(due, due + 50))
        // Progress moves the deadline later; idling cancels it.
        c.onSample("v", 10_000, wantsPlay = true, isBuffering = false, nowMs = start + 10_000)
        assertEquals(start + 10_000 + WakeLockPolicy.STALL_LIMIT_MS, WakeLockPolicy.releaseDueAt(c.progressAt()))
        c.onSample("v", 10_000, wantsPlay = false, isBuffering = false, nowMs = start + 20_000)
        assertEquals(0L, WakeLockPolicy.releaseDueAt(c.progressAt()))
    }

    @Test fun silentClockWithoutAnyReportsIsStalledToo() {
        val c = MediaClockWatch()
        val start = 1_000L
        c.onSample("v", 0, wantsPlay = true, isBuffering = false, nowMs = start)
        // No report at all (frozen renderer); the health tick asks.
        assertTrue(holds(c, start + MediaClockWatch.GRACE_MS - 1))
        assertEquals(MediaClockWatch.State.STALLED, c.state(start + MediaClockWatch.GRACE_MS))
        assertTrue(holds(c, start + WakeLockPolicy.STALL_LIMIT_MS - 1))
        assertFalse(holds(c, start + WakeLockPolicy.STALL_LIMIT_MS))
    }

    @Test fun progressingBackgroundPlaybackKeepsTheWakeLock() {
        val c = MediaClockWatch()
        // Background reports every 10 s, each 10 s further.
        for (i in 0..60) c.onSample("v", i * 10_000L, wantsPlay = true, isBuffering = false, nowMs = i * 10_000L)
        assertEquals(MediaClockWatch.State.PROGRESSING, c.state(600_000))
        assertTrue(holds(c, 600_000))
        // Small sub-step moves accumulate instead of being lost.
        val d = MediaClockWatch()
        d.onSample("v", 0, true, false, 0)
        for (i in 1..200) d.onSample("v", i * 100L, true, false, i * 1_000L)
        assertEquals(0L, d.stalledSince(200_000))
    }

    @Test fun recoveryWithoutPlaybackIsBoundedAndPauseIdles() {
        val c = MediaClockWatch()
        val start = 1_000L
        // Recovering (wanted, not playing): the clock does not move.
        c.onSample("v", 5_000, wantsPlay = true, isBuffering = false, nowMs = start)
        assertFalse(holds(c, start + WakeLockPolicy.STALL_LIMIT_MS))
        // Paused: idle, and a later Play gets a fresh grace period.
        c.onSample("v", 5_000, wantsPlay = false, isBuffering = false, nowMs = start + WakeLockPolicy.STALL_LIMIT_MS + 1)
        assertEquals(MediaClockWatch.State.IDLE, c.state(start + WakeLockPolicy.STALL_LIMIT_MS + 2))
        val play = start + WakeLockPolicy.STALL_LIMIT_MS + 10_000
        c.onSample("v", 5_000, wantsPlay = true, isBuffering = false, nowMs = play)
        assertEquals(0L, c.stalledSince(play + MediaClockWatch.GRACE_MS - 1))
        // A new video counts as progress.
        c.onSample("w", 0, wantsPlay = true, isBuffering = true, nowMs = play + MediaClockWatch.GRACE_MS + 5)
        assertEquals(MediaClockWatch.State.BUFFERING, c.state(play + MediaClockWatch.GRACE_MS + 6))
    }

    @Test fun clockTotalsSeparateBufferingFromStalledTime() {
        val c = MediaClockWatch()
        c.onSample("v", 0, wantsPlay = true, isBuffering = false, nowMs = 1_000)
        c.onSample("v", 0, wantsPlay = true, isBuffering = true, nowMs = 11_000)
        // 11 s..31 s buffering within grace; 31 s..51 s the clock is stalled.
        val (buffering, stalled) = c.totals(51_000)
        assertEquals(20_000L, buffering)
        assertEquals(20_000L, stalled)
        c.reset()
        assertEquals(MediaClockWatch.State.IDLE, c.state(60_000))
        assertEquals(0L to 0L, c.totals(70_000))
    }

    @Test fun sessionIgnoresEverythingOutsidePlayback() {
        val s = PerformanceSession()
        // Before a session (or after Quit): thermal/memory callbacks change nothing, no recovery tick.
        val idle = s.evaluate(PerfSignals(thermalStatus = 3, memory = MemoryLevel.CRITICAL), 0)
        assertEquals(PerfLevel.NORMAL, idle.level)
        assertNull(idle.transition)
        assertFalse(idle.needsRecoveryTick)
        s.onDroppedFrames(90); s.onDroppedFrames(90)
        assertEquals(PerfLevel.NORMAL, s.droppedLevel)
        assertEquals(0L, s.primaryBufferingMs(true, 100))
        s.start()
        val hot = s.evaluate(PerfSignals(thermalStatus = 3), 1_000)
        assertEquals(PerfLevel.SEVERE, hot.level)
        assertTrue(hot.needsRecoveryTick)
        // Quit: back to NORMAL, and a late thermal callback cannot restart the recovery tick.
        s.stop()
        val late = s.evaluate(PerfSignals(thermalStatus = 3), 2_000)
        assertEquals(PerfLevel.NORMAL, late.level)
        assertFalse(late.needsRecoveryTick)
        assertEquals(PerfLevel.NORMAL, s.governor.level)
    }

    @Test fun rendererChangeDropsStaleDroppedFrameAndBufferingMeasurements() {
        val s = PerformanceSession()
        s.start()
        s.onDroppedFrames(40); s.onDroppedFrames(40)
        assertEquals(PerfLevel.HIGH, s.droppedLevel)
        assertEquals(0L, s.primaryBufferingMs(true, 1_000))
        assertEquals(9_000L, s.primaryBufferingMs(true, 10_000))
        s.rendererChanged()
        assertEquals(PerfLevel.NORMAL, s.droppedLevel)
        assertEquals(0L, s.primaryBufferingMs(true, 11_000))
        // One bad window of the new renderer is not sustained.
        s.onDroppedFrames(40)
        assertEquals(PerfLevel.NORMAL, s.droppedLevel)
        // The dropped level reaches the governor through the session.
        s.onDroppedFrames(40)
        assertEquals(PerfLevel.HIGH, s.evaluate(PerfSignals(), 12_000).level)
    }

    @Test fun hiddenReleaseDeadlineOnlyMovesEarlier() {
        val hiddenAt = 100_000L
        val normal = hiddenAt + PhoneMirrorPolicy.HIDDEN_RELEASE_MS
        assertEquals(hiddenAt + 10_000, HiddenReleasePolicy.deadline(hiddenAt, normal, PerfLevel.HIGH))
        assertEquals(hiddenAt + 30_000, HiddenReleasePolicy.deadline(hiddenAt, normal, PerfLevel.ELEVATED))
        // Cooling down never postpones an earlier deadline.
        assertEquals(hiddenAt + 10_000, HiddenReleasePolicy.deadline(hiddenAt, hiddenAt + 10_000, PerfLevel.NORMAL))
        // Pressure that rises after the shorter deadline already passed: due now (past).
        assertTrue(HiddenReleasePolicy.deadline(hiddenAt, normal, PerfLevel.SEVERE) < hiddenAt + 45_000)
    }

    @Test fun positionPersistenceSavesTransportsImmediatelyAndPlaybackRarely() {
        val playing = Snap(id = "v1", playing = true, posMs = 60_000)
        val on = { true }
        val off = { false }
        // New video or nothing saved yet: at once.
        assertTrue(PositionPersistPolicy.shouldSave("", -1, 0, playing, 0, on))
        assertTrue(PositionPersistPolicy.shouldSave("v0", 1_000, 0, playing, 0, on))
        // Pause, Stop/Quit (userPaused) and end: at once whenever the position differs.
        assertTrue(PositionPersistPolicy.shouldSave("v1", 59_000, 100_000, playing.copy(playing = false), 100_001, on))
        assertTrue(PositionPersistPolicy.shouldSave("v1", 59_000, 100_000, playing.copy(playing = false, userPaused = true), 100_001, on))
        assertTrue(PositionPersistPolicy.shouldSave("v1", 59_000, 100_000, playing.copy(ended = true), 100_001, on))
        assertFalse(PositionPersistPolicy.shouldSave("v1", 60_000, 100_000, playing.copy(playing = false), 100_001, on))
        // Playing: interval and a real move are both required.
        assertFalse(PositionPersistPolicy.shouldSave("v1", 50_000, 0, playing, 14_999, on))
        assertTrue(PositionPersistPolicy.shouldSave("v1", 50_000, 0, playing, 15_000, on))
        assertFalse(PositionPersistPolicy.shouldSave("v1", 50_000, 0, playing, 15_000, off))
        assertTrue(PositionPersistPolicy.shouldSave("v1", 50_000, 0, playing, 30_000, off))
        assertFalse(PositionPersistPolicy.shouldSave("v1", 57_000, 0, playing, 60_000, on))
        // Blank id is never saved.
        assertFalse(PositionPersistPolicy.shouldSave("v1", 0, 0, Snap(), 60_000, on))
    }

    @Test fun screenStateIsOnlyQueriedWhenTheIntervalDecides() {
        var asked = 0
        val probe = { asked++; true }
        val playing = Snap(id = "v1", playing = true, posMs = 60_000)
        PositionPersistPolicy.shouldSave("v1", 50_000, 0, playing, 1_000, probe)
        PositionPersistPolicy.shouldSave("v1", 50_000, 0, playing, 40_000, probe)
        assertEquals(0, asked)
        PositionPersistPolicy.shouldSave("v1", 50_000, 0, playing, 20_000, probe)
        assertEquals(1, asked)
    }

    @Test fun diagnosticsCountRendererOwnershipAndThermalChanges() {
        val d = PerfDiagnostics()
        d.rendererAllocations = 2; d.rendererReleases = 1
        d.secondaryAllocations = 1; d.secondaryReleases = 1
        assertEquals(1, d.liveRenderers())
        d.rendererReleases = 5
        assertEquals(0, d.liveRenderers())
        d.noteThermal(0); d.noteThermal(0); d.noteThermal(2); d.noteThermal(1)
        assertEquals(2, d.thermalChanges)
        assertTrue(d.summary().contains("batteryTempC=unknown"))
        d.batteryTempDeciC = 412
        assertTrue(d.summary().contains("batteryTempC=41.2"))
    }

    @Test fun snapshotWithoutPositionIgnoresOnlyThePosition() {
        val a = Snap(id = "v", playing = true, posMs = 1_000)
        val b = a.copy(posMs = 2_400)
        assertEquals(a.withoutPosition(), b.withoutPosition())
        assertFalse(a.withoutPosition() == a.copy(playing = false).withoutPosition())
        assertFalse(a.withoutPosition() == a.copy(buffering = true).withoutPosition())
    }
}
