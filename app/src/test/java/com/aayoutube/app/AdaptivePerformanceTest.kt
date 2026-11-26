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

    @Test fun wakeLockIsBoundedDuringStalledRecovery() {
        assertFalse(WakeLockPolicy.hold(wanted = false, playing = true, stalledSince = 0, nowMs = 0))
        assertTrue(WakeLockPolicy.hold(wanted = true, playing = true, stalledSince = 0, nowMs = 0))
        assertTrue(WakeLockPolicy.hold(wanted = true, playing = false, stalledSince = 0, nowMs = 1_000))
        assertTrue(WakeLockPolicy.hold(wanted = true, playing = false, stalledSince = 1_000, nowMs = 1_000 + WakeLockPolicy.STALL_LIMIT_MS - 1))
        assertFalse(WakeLockPolicy.hold(wanted = true, playing = false, stalledSince = 1_000, nowMs = 1_000 + WakeLockPolicy.STALL_LIMIT_MS))
        // Playing again: held regardless of an old stall.
        assertTrue(WakeLockPolicy.hold(wanted = true, playing = true, stalledSince = 1_000, nowMs = 10_000_000))
        assertTrue(WakeLockPolicy.needsRefresh(0, 5))
        assertFalse(WakeLockPolicy.needsRefresh(1_000, 1_000 + WakeLockPolicy.REFRESH_MS - 1))
        assertTrue(WakeLockPolicy.needsRefresh(1_000, 1_000 + WakeLockPolicy.REFRESH_MS))
        assertTrue(WakeLockPolicy.REFRESH_MS < WakeLockPolicy.TIMEOUT_MS)
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
