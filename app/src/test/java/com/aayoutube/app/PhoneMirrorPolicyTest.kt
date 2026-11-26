package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

/** Behavioural tests for the phone mirror's stale-callback, lifecycle and diagnostics decisions. */
class PhoneMirrorPolicyTest {

    // ------------------------------------------------------------ stale callbacks

    @Test fun lateCallbackNeverActsOnANewerVideoOrPage() {
        assertTrue(PhoneMirrorPolicy.current(3, 3, "AAAAAAAAAAA", "AAAAAAAAAAA"))
        assertFalse("page reloaded meanwhile", PhoneMirrorPolicy.current(3, 4, "AAAAAAAAAAA", "AAAAAAAAAAA"))
        assertFalse("rapid selection changed the video", PhoneMirrorPolicy.current(3, 3, "AAAAAAAAAAA", "BBBBBBBBBBB"))
    }

    @Test fun loadsOnlyWhatTheEngineIsPlayingAndRecoversAStuckPage() {
        val id = "AAAAAAAAAAA"
        assertFalse("nothing to follow", PhoneMirrorPolicy.needsLoad("", null, "", 99_000))
        assertTrue("new video", PhoneMirrorPolicy.needsLoad("OLD", "OLD", id, 0))
        assertFalse("just loaded, page not settled", PhoneMirrorPolicy.needsLoad(id, null, id, 500))
        assertTrue("stuck on a non-video page", PhoneMirrorPolicy.needsLoad(id, null, id, 5_000))
        assertTrue("page moved to another video", PhoneMirrorPolicy.needsLoad(id, "BBBBBBBBBBB", id, 5_000))
        assertFalse("healthy", PhoneMirrorPolicy.needsLoad(id, id, id, 60_000))
    }

    // ------------------------------------------------------------ immediate follow

    @Test fun phoneFollowsCarPlayPauseSeekAndVideoChangesImmediately() {
        val base = Snap(id = "AAAAAAAAAAA", playing = true, posMs = 10_000)
        assertFalse("normal playback progress", PhoneMirrorPolicy.pokeNeeded(base, base.copy(posMs = 11_000)))
        assertTrue("car pause", PhoneMirrorPolicy.pokeNeeded(base, base.copy(playing = false)))
        assertTrue("car play", PhoneMirrorPolicy.pokeNeeded(base.copy(playing = false), base))
        assertTrue("car seek", PhoneMirrorPolicy.pokeNeeded(base, base.copy(posMs = 60_000)))
        assertTrue("car seek back", PhoneMirrorPolicy.pokeNeeded(base, base.copy(posMs = 1_000)))
        assertTrue("new video", PhoneMirrorPolicy.pokeNeeded(base, base.copy(id = "BBBBBBBBBBB")))
    }

    // ------------------------------------------------------------ user intent

    @Test fun onlyARealTapCanSelectAVideo() {
        assertTrue(PhoneMirrorPolicy.isTap(3f, 4f, 120, 8f))
        assertFalse("scroll", PhoneMirrorPolicy.isTap(0f, 300f, 200, 8f))
        assertFalse("long press / drag", PhoneMirrorPolicy.isTap(1f, 1f, 900, 8f))
    }

    @Test fun nativeYouTubeHomeAndSearchAreClassified() {
        assertEquals(PhoneMirrorPolicy.Page.WATCH, PhoneMirrorPolicy.classify("/watch"))
        assertEquals(PhoneMirrorPolicy.Page.WATCH, PhoneMirrorPolicy.classify("/live/abcdefghijk"))
        assertEquals(PhoneMirrorPolicy.Page.HOME, PhoneMirrorPolicy.classify("/"))
        assertEquals(PhoneMirrorPolicy.Page.HOME, PhoneMirrorPolicy.classify(null))
        assertEquals(PhoneMirrorPolicy.Page.SEARCH, PhoneMirrorPolicy.classify("/results"))
        assertEquals(PhoneMirrorPolicy.Page.OTHER, PhoneMirrorPolicy.classify("/@channel"))
    }

    @Test fun rapidSelectionsOpenEachDifferentVideoOnceAndKeepPlaylistAndTime() {
        val d = SelectionDeduper()
        val a = SelectionDeduper.key("/watch", "AAAAAAAAAAA", "PL1", 0)
        val b = SelectionDeduper.key("/watch", "BBBBBBBBBBB", "PL1", 0)
        val bLater = SelectionDeduper.key("/watch", "BBBBBBBBBBB", "PL1", 90_000)
        assertTrue(d.accept(a, 0))
        assertTrue(d.accept(b, 200))
        assertFalse("second report of the same tap", d.accept(b, 240))
        assertTrue("same video at another timestamp is a different selection", d.accept(bLater, 300))
        assertEquals("https://m.youtube.com/watch?v=BBBBBBBBBBB&list=PL1&t=90s",
            PhoneMirrorPolicy.watchUrl("https://m.youtube.com", "BBBBBBBBBBB", "PL1", 90_000))
    }

    // ------------------------------------------------------------ crash loops

    @Test fun repeatedRendererCrashesStopRecreationInsteadOfLooping() {
        val g = RateGuard(PhoneMirrorPolicy.MAX_CRASHES, PhoneMirrorPolicy.CRASH_WINDOW_MS)
        assertTrue(g.record(0))
        assertTrue(g.record(5_000))
        assertTrue(g.record(10_000))
        assertFalse("fourth crash within a minute", g.record(15_000))
        g.reset()
        assertTrue("a full teardown starts a clean history", g.record(16_000))
    }

    @Test fun crashesSpreadOverTimeStillRecover() {
        val g = RateGuard(PhoneMirrorPolicy.MAX_CRASHES, PhoneMirrorPolicy.CRASH_WINDOW_MS)
        for (i in 0 until 10) assertTrue(g.record(i * 61_000L))
    }

    // ------------------------------------------------------------ sync results and metrics

    @Test fun parsesThePageScriptsAnswer() {
        assertEquals(SyncResult(-120, "ok"), SyncResult.parse("\"-120|ok\""))
        assertEquals(SyncResult(30_000, "seek"), SyncResult.parse("\"30000|seek\""))
        assertEquals(SyncResult(null, "ad"), SyncResult.parse("\"ad\""))
        assertEquals(SyncResult(null, "mismatch"), SyncResult.parse("\"mismatch\""))
        assertEquals(SyncResult(null, "none"), SyncResult.parse("null"))
        assertEquals(SyncResult(null, "none"), SyncResult.parse(null))
    }

    @Test fun statsAggregateDriftAndCorrections() {
        val s = MirrorSyncStats()
        assertTrue(s.isEmpty())
        s.record(SyncResult(100, "ok"))
        s.record(SyncResult(-900, "nudge"))
        s.record(SyncResult(4_000, "seek"))
        s.record(SyncResult(null, "ad"))
        s.record(SyncResult(null, "mismatch"))
        s.record(SyncResult(null, "user"))
        assertEquals(3, s.syncs)
        assertEquals(1, s.nudges)
        assertEquals(1, s.seeks)
        assertEquals(1, s.ads)
        assertEquals(1, s.mismatches)
        assertEquals(1, s.userHolds)
        assertEquals(4_000L, s.driftMax)
        assertEquals(1_666L, s.avgDriftMs())
        s.pauses++; s.resumes++; s.crashes++; s.recoveries++; s.duplicates++
        val line = s.summary()
        listOf("syncs=3", "driftMaxMs=4000", "nudges=1", "seeks=1", "ads=1", "pauses=1", "resumes=1",
            "crashes=1", "recoveries=1", "duplicateNavBlocked=1").forEach { assertTrue(it, line.contains(it)) }
        s.reset()
        assertTrue(s.isEmpty())
    }

    // ------------------------------------------------------------ completeness

    @Test fun scriptHardMutesAndKeepsHiddenPlaybackStopped() {
        val js = listOf(java.io.File("app/src/main/assets/mirror.js"), java.io.File("../app/src/main/assets/mirror.js"))
            .first { it.isFile }.readText()
        // Behaviour is covered by .github/scripts/mirror.test.cjs; this guards the wiring only.
        assertTrue(js.contains("lockProp('muted', true)"))
        assertTrue(js.contains("lockProp('volume', 0)"))
        assertTrue(js.contains("if (M.stopped) return Promise.resolve()"))
    }

    @Test fun lifecycleResumesAfterRendererRecreationAndQuitReleasesEverything() {
        val src = listOf(java.io.File("app/src/main/java/com/aayoutube/app/PhoneMirror.kt"),
            java.io.File("../app/src/main/java/com/aayoutube/app/PhoneMirror.kt")).first { it.isFile }.readText()
        assertTrue("recreated renderer re-applies the wanted visibility", src.contains("main.post { applyVisible() }"))
        val release = src.substringAfter("fun release()").substringBefore("private fun teardown()")
        assertTrue(release.contains("main.removeCallbacksAndMessages(null)"))
        assertTrue(release.contains("crashGuard.reset()"))
        val host = listOf(java.io.File("app/src/main/java/com/aayoutube/app/WebHost.kt"),
            java.io.File("../app/src/main/java/com/aayoutube/app/WebHost.kt")).first { it.isFile }.readText()
        assertTrue(host.substringAfter("fun release(preserveCarOwners").contains("PhoneMirror.release()"))
    }
}
