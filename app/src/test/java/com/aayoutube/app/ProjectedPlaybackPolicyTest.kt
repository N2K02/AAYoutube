package com.aayoutube.app

import com.aayoutube.app.WebHost.PlayerMode
import org.junit.Assert.*
import org.junit.Test

/** Behavioural tests for the projected (phone + Android Auto) playback decisions. */
class ProjectedPlaybackPolicyTest {

    // ------------------------------------------------------------ selections

    @Test fun oneTapReportedByThreeCallbacksOpensTheVideoOnce() {
        val d = SelectionDeduper()
        val key = SelectionDeduper.key("/watch", "abcdefghijk", null)
        assertTrue(d.accept(key, 1_000))      // page bridge
        assertFalse(d.accept(key, 1_040))     // shouldOverrideUrlLoading
        assertFalse(d.accept(key, 1_300))     // doUpdateVisitedHistory
        assertTrue("a deliberate re-selection later still works", d.accept(key, 3_000))
    }

    @Test fun rapidConsecutiveDifferentSelectionsAreAllAccepted() {
        val d = SelectionDeduper()
        val ids = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "aaaaaaaaaaa")
        ids.forEachIndexed { i, id ->
            assertTrue(id, d.accept(SelectionDeduper.key("/watch", id, null), 1_000L + i * 50))
        }
    }

    @Test fun sameVideoInAnotherPlaylistIsADifferentSelection() {
        val d = SelectionDeduper()
        assertTrue(d.accept(SelectionDeduper.key("/watch", "abcdefghijk", null), 0))
        assertTrue(d.accept(SelectionDeduper.key("/watch", "abcdefghijk", "PL123"), 10))
    }

    @Test fun nonVideoPagesAreNeverDeduplicated() {
        val d = SelectionDeduper()
        assertEquals("", SelectionDeduper.key("/results", null, null))
        assertTrue(d.accept("", 0))
        assertTrue(d.accept("", 1))
        assertEquals("/shorts/xyz", SelectionDeduper.key("/shorts/xyz", null, null))
    }

    @Test fun clockGoingBackwardsDoesNotSwallowSelections() {
        val d = SelectionDeduper()
        val key = SelectionDeduper.key("/watch", "abcdefghijk", null)
        assertTrue(d.accept(key, 5_000))
        assertTrue(d.accept(key, 1_000))
    }

    @Test fun selectingTheLoadedVideoReusesThePlayer() {
        assertTrue(SelectedVideoReuse.reuse("abc", true, "abc", null, "PL1", false))
        assertTrue(SelectedVideoReuse.reuse("abc", true, "abc", "PL1", "PL1", false))
        assertFalse("other playlist context reloads", SelectedVideoReuse.reuse("abc", true, "abc", "PL2", "PL1", false))
        assertFalse("other video loads", SelectedVideoReuse.reuse("abc", true, "def", null, null, false))
        assertFalse("off the watch page loads", SelectedVideoReuse.reuse("abc", false, "abc", null, null, false))
        assertFalse("explicit paused restore loads", SelectedVideoReuse.reuse("abc", true, "abc", null, null, true))
        assertFalse("nothing loaded", SelectedVideoReuse.reuse("", true, "", null, null, false))
    }

    @Test fun browseHomeIsNotReloadedWhenAlreadyThere() {
        assertTrue(BrowseNavigation.sameDestination("https://m.youtube.com/", "https://m.youtube.com/"))
        assertTrue(BrowseNavigation.sameDestination("https://m.youtube.com/#menu", "https://m.youtube.com"))
        assertFalse(BrowseNavigation.sameDestination("https://m.youtube.com/results?search_query=x", "https://m.youtube.com/"))
        assertFalse(BrowseNavigation.sameDestination(null, "https://m.youtube.com/"))
    }

    // ------------------------------------------------------------ renderer reuse

    @Test fun retainedAudioRendererIsReusedInTheNewCarWindow() {
        assertNull(CarRendererReusePolicy.reason(620, 620, 0x11, 0x22, retainedDetached = true))
    }

    @Test fun liveRendererMovedBetweenWindowsStillGetsAFreshOne() {
        assertEquals("window", CarRendererReusePolicy.reason(620, 620, 0x11, 0x22, retainedDetached = false))
        assertNull(CarRendererReusePolicy.reason(620, 620, 0x11, 0x11, retainedDetached = false))
    }

    @Test fun displayChangeAlwaysRecreatesEvenWhenRetained() {
        assertEquals("display", CarRendererReusePolicy.reason(578, 620, 0x11, 0x22, retainedDetached = true))
        assertEquals("display", CarRendererReusePolicy.reason(0, 620, null, 0x22, retainedDetached = false))
    }

    @Test fun blackScreenRecoveryForcesARecreate() {
        assertEquals("window-black",
            CarRendererReusePolicy.reason(620, 620, 0x22, 0x22, retainedDetached = true, forced = "window-black"))
    }

    @Test fun onlyAdvancingPlaybackOverABlackFrameCountsAsBlack() {
        assertTrue(CarRendererReusePolicy.blackSample(100, playing = true, buffering = false, advancing = true))
        assertFalse("paused", CarRendererReusePolicy.blackSample(100, false, false, true))
        assertFalse("buffering", CarRendererReusePolicy.blackSample(100, true, true, true))
        assertFalse("clock stalled", CarRendererReusePolicy.blackSample(100, true, false, false))
        assertFalse("picture present", CarRendererReusePolicy.blackSample(60, true, false, true))
        assertFalse("capture unavailable", CarRendererReusePolicy.blackSample(null, true, false, true))
    }

    @Test fun recoveryNeedsConsecutiveBlackSamplesAndRespectsCooldown() {
        assertFalse(CarRendererReusePolicy.shouldRecover(1, 10_000, 0))
        assertTrue(CarRendererReusePolicy.shouldRecover(2, 10_000, 0))
        assertFalse("no recovery loop", CarRendererReusePolicy.shouldRecover(2, 30_000, 10_000))
        assertTrue(CarRendererReusePolicy.shouldRecover(2, 80_000, 10_000))
    }

    @Test fun frameDarknessDistinguishesBlackFromPicture() {
        assertEquals(100, FrameDarkness.percent(IntArray(144) { 0xFF000000.toInt() }))
        assertEquals(0, FrameDarkness.percent(IntArray(144) { 0xFF808080.toInt() }))
        val mixed = IntArray(100) { if (it < 50) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertEquals(50, FrameDarkness.percent(mixed))
        assertEquals(100, FrameDarkness.percent(IntArray(0)))
    }

    // ------------------------------------------------------------ timestamps / mirror

    @Test fun timestampSelectionsAreIntentionalAndDistinct() {
        val d = SelectionDeduper()
        assertTrue(d.accept(SelectionDeduper.key("/watch", "abcdefghijk", null, null), 0))
        assertTrue("same video at a timestamp is a seek, not a duplicate",
            d.accept(SelectionDeduper.key("/watch", "abcdefghijk", null, 90_000), 10))
        assertFalse(d.accept(SelectionDeduper.key("/watch", "abcdefghijk", null, 90_000), 20))
    }

    @Test fun youTubeTimestampFormatsParse() {
        assertEquals(90_000L, TimestampParam.parseMs("90"))
        assertEquals(90_000L, TimestampParam.parseMs("90s"))
        assertEquals(90_000L, TimestampParam.parseMs("1m30s"))
        assertEquals(3_723_000L, TimestampParam.parseMs("1h2m3s"))
        assertNull(TimestampParam.parseMs(null))
        assertNull(TimestampParam.parseMs(""))
        assertNull(TimestampParam.parseMs("abc"))
        assertNull(TimestampParam.parseMs("-5"))
        assertNull(TimestampParam.parseMs("1x"))
    }

    @Test fun darkSceneIsNotADeadSurfaceOnceControlsAreShown() {
        assertFalse("bright controls painted", CarRendererReusePolicy.stillBlackWithControls(97))
        assertTrue(CarRendererReusePolicy.stillBlackWithControls(100))
        assertFalse("no capture: never recover blindly", CarRendererReusePolicy.stillBlackWithControls(null))
    }

    @Test fun mirrorLoadsOnlyPlainVideoIds() {
        assertEquals("https://m.youtube.com/watch?v=abcdefghijk&list=PL_x-1&t=90s",
            PhoneMirrorPolicy.watchUrl("https://m.youtube.com/", "abcdefghijk", "PL_x-1", 90_500))
        assertEquals("https://m.youtube.com/watch?v=abcdefghijk",
            PhoneMirrorPolicy.watchUrl("https://m.youtube.com/", "abcdefghijk", "bad&list", 500))
        assertNull(PhoneMirrorPolicy.watchUrl("https://m.youtube.com/", "abc\"&x=1", null, 0))
        assertNull(PhoneMirrorPolicy.watchUrl("https://m.youtube.com/", "", null, 0))
    }

    @Test fun mirrorActionsAreTheKnownSet() {
        assertEquals(setOf("play", "pause", "seek", "mini", "next", "prev"), PhoneMirrorPolicy.ACTIONS)
    }

    // ------------------------------------------------------------ phone navigation

    @Test fun phoneHomeShowsFloatingMiniWhenSomethingIsLoaded() {
        assertEquals(PlayerMode.MINI, ProjectedPhoneNav.home(hasVideo = true))
        assertEquals(PlayerMode.HIDDEN, ProjectedPhoneNav.home(hasVideo = false))
    }

    @Test fun phoneBackMinimisesThenWalksBrowseHistory() {
        assertEquals(ProjectedPhoneNav.Back.Minimize, ProjectedPhoneNav.back(PlayerMode.FULL, true))
        assertEquals(ProjectedPhoneNav.Back.BrowseHistory, ProjectedPhoneNav.back(PlayerMode.MINI, true))
        assertEquals(ProjectedPhoneNav.Back.Home, ProjectedPhoneNav.back(PlayerMode.MINI, false))
        assertEquals(ProjectedPhoneNav.Back.Home, ProjectedPhoneNav.back(PlayerMode.HIDDEN, false))
    }

    @Test fun phoneModeFollowsTheSharedVideo() {
        assertEquals(PlayerMode.MINI, ProjectedPhoneNav.onVideoChanged(PlayerMode.HIDDEN, true))
        assertEquals(PlayerMode.FULL, ProjectedPhoneNav.onVideoChanged(PlayerMode.FULL, true))
        assertEquals(PlayerMode.MINI, ProjectedPhoneNav.onVideoChanged(PlayerMode.MINI, true))
        assertEquals(PlayerMode.HIDDEN, ProjectedPhoneNav.onVideoChanged(PlayerMode.FULL, false))
        assertEquals(PlayerMode.HIDDEN, ProjectedPhoneNav.onVideoChanged(PlayerMode.MINI, false))
    }

    // ------------------------------------------------------------ car connection

    @Test fun onlyARealDisconnectEndsAudioRetention() {
        assertTrue(CarConnectionPolicy.disconnected(previous = 2, current = 0))
        assertTrue(CarConnectionPolicy.disconnected(previous = 1, current = 0))
        assertFalse("initial not-connected report", CarConnectionPolicy.disconnected(-1, 0))
        assertFalse("still disconnected", CarConnectionPolicy.disconnected(0, 0))
        assertFalse("reconnect", CarConnectionPolicy.disconnected(0, 2))
    }

    // ------------------------------------------------------------ transition meter

    private fun meter(pos: Long = 10_000, playing: Boolean = true) = ModeTransitionMeter().apply {
        start("Video->Music", "vid", pos, generation = 1, playing = playing, nowMs = 0)
    }

    @Test fun transitionCompletesOnlyWhenTheClockAdvances() {
        val m = meter()
        assertNull("transient playing callback", m.onState("vid", true, false, false, false, 10_000, 1, 200))
        assertNull(m.onState("vid", false, false, false, false, 10_000, 1, 400))
        val done = m.onState("vid", true, false, false, false, 10_400, 1, 1_200)
        assertTrue(done is ModeTransitionMeter.Event.Completed)
        done as ModeTransitionMeter.Event.Completed
        assertEquals(1_200, done.elapsedMs)
        assertEquals("interruption from first stall", 800, done.interruptionMs)
        assertFalse(done.rendererRecreated)
        assertFalse(m.running)
        assertNull("reported once", m.onState("vid", true, false, false, false, 11_000, 1, 1_500))
    }

    @Test fun uninterruptedHandoffReportsZeroInterruption() {
        val m = meter()
        val done = m.onState("vid", true, false, false, false, 10_250, 1, 250) as ModeTransitionMeter.Event.Completed
        assertEquals(0, done.interruptionMs)
        assertEquals(0, done.bufferingMs)
    }

    @Test fun bufferingIsReportedOnceAndItsDurationMeasured() {
        val m = meter()
        assertTrue(m.onState("vid", false, true, false, false, 10_000, 2, 100) is ModeTransitionMeter.Event.Buffering)
        assertNull(m.onState("vid", false, true, false, false, 10_000, 2, 600))
        assertNull(m.onState("vid", true, false, false, false, 10_000, 2, 900))
        val done = m.onState("vid", true, false, false, false, 10_500, 2, 1_400) as ModeTransitionMeter.Event.Completed
        assertEquals(800, done.bufferingMs)
        assertTrue("generation changed", done.rendererRecreated)
    }

    @Test fun explicitPauseCancelsInsteadOfCompleting() {
        val m = meter()
        val e = m.onState("vid", false, false, true, false, 10_000, 1, 300)
        assertEquals("user-paused", (e as ModeTransitionMeter.Event.Cancelled).reason)
        assertFalse(m.running)
    }

    @Test fun rapidVideoChangeCancelsTheOldMeasurement() {
        val m = meter()
        val e = m.onState("other", true, false, false, false, 1_000, 1, 300)
        assertEquals("video-changed", (e as ModeTransitionMeter.Event.Cancelled).reason)
    }

    @Test fun restartingDuringRapidModeSwitchingMeasuresTheLatestHandoff() {
        val m = meter()
        m.start("Music->Video", "vid", 20_000, generation = 3, playing = true, nowMs = 500)
        assertNull("old start position no longer counts", m.onState("vid", true, false, false, false, 15_000, 3, 700))
        val done = m.onState("vid", true, false, false, false, 20_200, 3, 900) as ModeTransitionMeter.Event.Completed
        assertEquals("Music->Video", done.direction)
        assertEquals(400, done.elapsedMs)
    }

    @Test fun duplicateHandoffStartIsIgnoredAndTheOtherDirectionSupersedes() {
        val m = ModeTransitionMeter()
        assertEquals(ModeTransitionMeter.Start.STARTED, m.start("Video->Music", "vid", 10_000, 1, true, 0))
        assertEquals(ModeTransitionMeter.Start.DUPLICATE, m.start("Video->Music", "vid", 10_400, 1, true, 400))
        assertEquals("the first start still defines the measurement", 400L, m.elapsed(400))
        assertEquals(ModeTransitionMeter.Start.SUPERSEDED, m.start("Music->Video", "vid", 10_600, 1, true, 600))
        assertEquals("Music->Video", m.currentDirection)
        m.cancel()
        assertEquals(ModeTransitionMeter.Start.STARTED, m.start("Music->Video", "vid", 10_800, 1, true, 800))
    }

    @Test fun recoveryIsConfirmedByTheMediaClockNotTheDelayedSnapshot() {
        assertTrue(HandoffConfirm.confirmed(true, 20_300, 20_200))
        assertTrue("within report jitter", HandoffConfirm.confirmed(true, 20_000, 20_200))
        assertFalse("clock still paused: the snapshot was ahead of reality", HandoffConfirm.confirmed(false, 20_300, 20_200))
        assertFalse("clock behind the reported position", HandoffConfirm.confirmed(true, 18_000, 20_200))
        assertFalse("no clock", HandoffConfirm.confirmed(null, null, 20_200))
    }

    @Test fun stuckHandoffTimesOut() {
        val m = meter()
        val e = m.onState("vid", false, false, false, false, 10_000, 1, 31_000)
        assertEquals("timeout", (e as ModeTransitionMeter.Event.Cancelled).reason)
    }

    @Test fun blankVideoNeverStartsAMeasurement() {
        val m = ModeTransitionMeter()
        m.start("Video->Music", "", 0, 1, true, 0)
        assertFalse(m.running)
        assertNull(m.onState("", true, false, false, false, 1_000, 1, 100))
    }

    @Test fun phonePlayerSwipeDownOpensThePhoneMiniPlayerOnly() {
        val js = listOf(java.io.File("app/src/main/assets/mirror.js"), java.io.File("../app/src/main/assets/mirror.js"))
            .first { it.isFile }.readText()
        // Gesture behaviour (tap, drag, scroll take-over, seek bar) is tested on the real script in mirror.test.cjs.
        assertTrue(js.contains("send('mini', 0)"))
        val mirror = listOf(java.io.File("app/src/main/java/com/aayoutube/app/PhoneMirror.kt"),
            java.io.File("../app/src/main/java/com/aayoutube/app/PhoneMirror.kt")).first { it.isFile }.readText()
        val control = mirror.substringAfter("private fun onControl(").substringBefore("private fun onNavigatedTo(")
        assertTrue(control.contains("\"mini\" -> if (BrowseHost.phoneMode.value == WebHost.PlayerMode.FULL) BrowseHost.phoneMini()"))
        assertFalse("never shrinks the car player", control.contains("WebHost.enterMini()"))
    }

    @Test fun mirrorChecksRarelyWhenInStep() {
        assertEquals(PhoneMirrorPolicy.STABLE_SYNC_MS, PhoneMirrorPolicy.nextSyncMs(120))
        assertEquals(PhoneMirrorPolicy.STABLE_SYNC_MS, PhoneMirrorPolicy.nextSyncMs(-300))
        assertEquals(PhoneMirrorPolicy.SYNC_MS, PhoneMirrorPolicy.nextSyncMs(900))
        assertEquals("unknown state (no video, ad, user acting)", PhoneMirrorPolicy.SYNC_MS, PhoneMirrorPolicy.nextSyncMs(null))
    }

    @Test fun carRailHasSettingsSizedForLandscape() {
        val car = listOf(java.io.File("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt"),
            java.io.File("../app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")).first { it.isFile }.readText()
        val rail = car.substringAfter("FloatingVideoBar(").substringBefore("private fun carVideoToMini(")
        assertTrue(rail.contains("BarIcon(Icons.Filled.Settings, \"Settings\", size,"))
        assertTrue(rail.contains("availableWidth = if (carLandscape) maxWidth else null"))
        assertTrue(rail.contains("extraCount = if (fitShown) 3 else 2"))
        assertTrue(car.contains("if (carSettings.back()) return true"))
    }

    @Test fun carSettingsBackWalksSubpageThenMenuThenCloses() {
        val st = SettingsPanelState()
        assertFalse(st.back())
        st.openMenu()
        assertTrue(st.open)
        st.page = st.page + 1 // any subpage
        assertTrue(st.back()); assertTrue("back to the menu", st.open)
        assertTrue(st.back()); assertFalse("closed", st.open)
    }

    @Test fun crossDisplayTrialRequiresRetainedCarOriginAndRecoveryOwner() {
        val now = 100_000L
        assertTrue(CarRendererReusePolicy.canTrial(670, 671, true, true, true, now, 0))
        assertFalse(CarRendererReusePolicy.canTrial(0, 670, true, true, true, now, 0))
        assertFalse(CarRendererReusePolicy.canTrial(670, 671, false, true, true, now, 0))
        assertFalse(CarRendererReusePolicy.canTrial(670, 671, true, false, true, now, 0))
        assertFalse(CarRendererReusePolicy.canTrial(670, 671, true, true, false, now, 0))
        assertFalse(CarRendererReusePolicy.canTrial(670, 671, true, true, true, now, 99_000))
        assertNull(CarRendererReusePolicy.reason(670, 671, 1, 2, true, verifiedTrial = true))
        assertEquals("display", CarRendererReusePolicy.reason(670, 671, 1, 2, true))
        assertEquals("window-black", CarRendererReusePolicy.reason(670, 671, 1, 2, true, "window-black", true))
    }

    @Test fun reuseTrialMustProveVisibleAdvancingFrames() {
        assertTrue(CarRendererReusePolicy.trialVerified(true, true, 40))
        assertFalse(CarRendererReusePolicy.trialVerified(false, true, 40))
        assertFalse(CarRendererReusePolicy.trialVerified(true, false, 40))
        assertFalse(CarRendererReusePolicy.trialVerified(true, true, null))
        assertFalse(CarRendererReusePolicy.trialVerified(true, true, 100))
    }

    @Test fun timestampNavigationNeverGetsOverwrittenByOldPosition() {
        assertNull(CarRendererReusePolicy.restorePosition("v", "v", "v", 320_000, false, 320_000))
        assertNull(CarRendererReusePolicy.restorePosition("v", "v", "v", 320_000, false, 0))
        assertNull(CarRendererReusePolicy.restorePosition("new", "v", "v", 320_000, false, null))
        assertNull(CarRendererReusePolicy.restorePosition("v", "v", "v", 320_000, true, null))
        assertEquals(320_000L, CarRendererReusePolicy.restorePosition("v", "v", "v", 320_000, false, null))
    }

    @Test fun sustainedPressureRequiresTwoFreshSamplesAndRecovers() {
        val policy = ProcessPressurePolicy()
        policy.update(178, 1000)
        assertFalse(policy.active(1000))
        policy.update(148, 31_000)
        assertTrue(policy.active(31_000))
        policy.update(80, 61_000)
        assertFalse(policy.active(61_000))
        policy.update(180, 91_000)
        policy.update(180, 121_000)
        assertTrue(policy.active(121_000))
        assertFalse(policy.active(197_000))
        policy.update(180, 197_000)
        assertFalse(policy.active(197_000))
        policy.reset()
        assertFalse(policy.active(197_000))
    }

    @Test fun cpuPressureDegradesMirrorAndRequiresCalmRecovery() {
        val gate = MirrorLoadGate(stableMs = 1000)
        assertEquals(MirrorLoadGate.State.DEGRADED, gate.update(0, false, 1000, true))
        assertEquals(MirrorLoadGate.State.DEGRADED, gate.update(0, false, 1500, false))
        assertEquals(MirrorLoadGate.State.NORMAL, gate.update(0, false, 2001, false))
    }

    @Test fun timestampParsingRejectsOverflowAndRepeatedUnits() {
        assertNull(TimestampParam.parseMs("9999999999999999"))
        assertNull(TimestampParam.parseMs("999999999999999h"))
        assertNull(TimestampParam.parseMs("1h2h"))
        assertNull(TimestampParam.parseMs("1s2m"))
        assertEquals(3_723_000L, TimestampParam.parseMs("1h2m3s"))
        assertEquals(0L, TimestampParam.parseMs("0"))
    }

    @Test fun browseIdentityPreservesCaseSensitiveVideoAndSearchValues() {
        assertTrue(BrowseNavigation.sameDestination("https://M.YOUTUBE.COM:443/", "https://m.youtube.com"))
        assertFalse(BrowseNavigation.sameDestination("https://m.youtube.com/watch?v=AbCdEfGhIJK", "https://m.youtube.com/watch?v=abcdefghijk"))
        assertFalse(BrowseNavigation.sameDestination("https://m.youtube.com/results?search_query=ABC", "https://m.youtube.com/results?search_query=abc"))
        assertFalse(BrowseNavigation.sameDestination("https://m.youtube.com/@Example", "https://m.youtube.com/@example"))
    }

    @Test fun handoffExpiresWithoutAnyPlaybackCallbackAndOnlyOnce() {
        val m = ModeTransitionMeter(timeoutMs = 1000)
        m.start("Video->Music", "vid", 100, 1, true, 100)
        assertNull(m.expire(1099))
        assertEquals(ModeTransitionMeter.Start.DUPLICATE, m.start("Video->Music", "vid", 100, 1, true, 1000))
        assertEquals("timeout", m.expire(1100)?.reason)
        assertFalse(m.running)
        assertNull(m.expire(1200))
        m.start("Music->Video", "vid", 100, 1, true, 1200)
        assertTrue(m.running)
        assertNull(m.expire(2100))
    }
}
