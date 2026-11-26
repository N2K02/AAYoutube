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

    @Test fun mirrorAutoplayIsNotAUserSelection() {
        assertFalse(PhoneMirrorPolicy.userSelection(0, 10_000))
        assertTrue(PhoneMirrorPolicy.userSelection(9_000, 10_000))
        assertFalse(PhoneMirrorPolicy.userSelection(1_000, 10_000))
        assertEquals(setOf("play", "pause", "seek", "mini"), PhoneMirrorPolicy.ACTIONS)
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
        val swipe = js.substringAfter("document.addEventListener('touchend'").substringBefore("touchcancel")
        assertTrue(swipe.contains("dy < 75 || Math.abs(dx) > dy * 0.65"))
        assertTrue(swipe.contains("send('mini', 0)"))
        val mirror = listOf(java.io.File("app/src/main/java/com/aayoutube/app/PhoneMirror.kt"),
            java.io.File("../app/src/main/java/com/aayoutube/app/PhoneMirror.kt")).first { it.isFile }.readText()
        val control = mirror.substringAfter("private fun onControl(").substringBefore("private fun onNavigatedTo(")
        assertTrue(control.contains("\"mini\" -> if (BrowseHost.phoneMode.value == WebHost.PlayerMode.FULL) BrowseHost.phoneMini()"))
        assertFalse("never shrinks the car player", control.contains("WebHost.enterMini()"))
    }
}
