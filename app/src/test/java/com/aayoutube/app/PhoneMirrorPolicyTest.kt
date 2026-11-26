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
            "crashes(total)=1", "recoveries(total)=1", "duplicateNavBlocked=1").forEach { assertTrue(it, line.contains(it)) }
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
        assertTrue(js.contains("if (S.stopped) return Promise.resolve()"))
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

    // ------------------------------------------------------------ round 2

    @Test fun intervalStatsRollButCrashesStayCumulative() {
        val s = MirrorSyncStats()
        s.record(SyncResult(200, "ok")); s.crashes = 2; s.recoveries = 1; s.timeouts = 3
        s.roll()
        assertEquals(0, s.syncs)
        assertEquals(0, s.timeouts)
        assertEquals(2, s.crashes)
        assertEquals(1, s.recoveries)
    }

    @Test fun staleAnswersAreRejectedByGenerationAndVideo() {
        // The engine answered for a video the phone is no longer following (rapid selection).
        assertFalse(PhoneMirrorPolicy.current(5, 5, "AAAAAAAAAAA", "BBBBBBBBBBB"))
        // The mirror page was replaced while the answer was in flight.
        assertFalse(PhoneMirrorPolicy.current(5, 6, "AAAAAAAAAAA", "AAAAAAAAAAA"))
    }

    @Test fun phoneBrowseUsesTheExistingNavigationAndSecondaryPlayerNeverInjectsLate() {
        val browse = listOf(java.io.File("app/src/main/java/com/aayoutube/app/BrowseHost.kt"),
            java.io.File("../app/src/main/java/com/aayoutube/app/BrowseHost.kt")).first { it.isFile }.readText()
        val fn = browse.substringAfter("fun phoneBrowse(").substringBefore("fun phoneMini(")
        assertTrue(fn.contains("phone.navigate(url)"))
        assertFalse(fn.contains("showFrom"))
        val mirror = listOf(java.io.File("app/src/main/java/com/aayoutube/app/PhoneMirror.kt"),
            java.io.File("../app/src/main/java/com/aayoutube/app/PhoneMirror.kt")).first { it.isFile }.readText()
        assertFalse("no late script injection after the page has started", mirror.contains("view.evaluateJavascript(script()"))
        assertFalse("no generic audio-configuration claim", mirror.contains("activePlaybackConfigurations"))
    }

    // ------------------------------------------------------------ round 3

    private fun bridge(got: MutableList<String>, intents: MutableList<String> = mutableListOf(), hidden: Boolean = true) =
        MirrorBridge("secret", { hidden }, { a, _, _ -> got += a }, { intents += it })

    @Test fun bridgeRejectsStaleDocumentsWrongTokensAndBadValues() {
        val got = mutableListOf<String>()
        val b = bridge(got)
        assertEquals("wrong token cannot register a document", 0, b.open("nope"))
        val doc1 = b.open("secret")
        b.control("pause", 0.0, "wrong", doc1, 1.0, "AAAAAAAAAAA")
        b.control("evil", 0.0, "secret", doc1, 2.0, "AAAAAAAAAAA")
        b.control("pause", Double.NaN, "secret", doc1, 3.0, "AAAAAAAAAAA")
        b.control("seek", -5.0, "secret", doc1, 4.0, "AAAAAAAAAAA")
        b.control("seek", 1e15, "secret", doc1, 5.0, "AAAAAAAAAAA")
        assertTrue(got.isEmpty())
        b.control("seek", 12_345.0, "secret", doc1, 6.0, "AAAAAAAAAAA")
        b.control("pause", 0.0, "secret", doc1, 6.0, "AAAAAAAAAAA")   // repeated sequence number
        b.control("pause", 0.0, "secret", doc1, 5.0, "AAAAAAAAAAA")   // out of order
        assertEquals(listOf("seek"), got)
        // A new document supersedes the old one: its delayed commands no longer pass.
        val doc2 = b.open("secret")
        b.control("play", 0.0, "secret", doc1, 7.0, "AAAAAAAAAAA")
        assertEquals(listOf("seek"), got)
        b.control("next", 0.0, "secret", doc2, 1.0, "BBBBBBBBBBB")
        assertEquals(listOf("seek", "next"), got)
    }

    @Test fun bridgeStartsNewDocumentsStoppedWhileHiddenAndAcceptsLinkIntentsOfTheCurrentDocument() {
        val intents = mutableListOf<String>()
        val b = bridge(mutableListOf(), intents, hidden = true)
        assertTrue(b.hidden())
        assertFalse(bridge(mutableListOf(), hidden = false).hidden())
        val doc = b.open("secret")
        b.intent("https://m.youtube.com/watch?v=BBBBBBBBBBB", "secret", doc + 1)
        b.intent("https://m.youtube.com/watch?v=BBBBBBBBBBB", "bad", doc)
        assertTrue(intents.isEmpty())
        b.intent("https://m.youtube.com/watch?v=BBBBBBBBBBB", "secret", doc)
        assertEquals(1, intents.size)
    }

    @Test fun navigationIsAuthorisedByWhatWasChosenAndOnlyOnce() {
        val n = NavAuthorizer()
        n.onTap(1_000)
        n.onIntent("BBBBBBBBBBB", 1_010)
        assertFalse("automatic navigation to another video", n.authorize("CCCCCCCCCCC", 1_500))
        assertTrue("the chosen video", n.authorize("BBBBBBBBBBB", 1_600))
        assertFalse("consumed", n.authorize("BBBBBBBBBBB", 1_700))
        n.onTap(5_000)
        assertFalse("a bare tap cannot authorize navigation", n.authorize("DDDDDDDDDDD", 5_300))
        n.onTap(9_000)
        assertFalse("too late", n.authorize("DDDDDDDDDDD", 9_900))
        n.onTap(12_000); n.onIntent("m.youtube.com/results?search_query=test", 12_005)
        assertFalse(n.authorize("EEEEEEEEEEE", 12_100))
        assertTrue(n.authorize("m.youtube.com/results?search_query=test", 12_200))
        n.onTap(20_000); n.reset()
        assertFalse("page change clears authorisation", n.authorize("FFFFFFFFFFF", 20_100))
    }

    @Test fun repeatedTransportCommandsAreForwardedOnce() {
        val g = TransportGuard(500)
        assertTrue(g.accept("next", 0))
        assertFalse(g.accept("next", 200))
        assertTrue("a different command", g.accept("prev", 250))
        assertTrue("later again", g.accept("prev", 900))
    }

    @Test fun phoneCopyIsSuspendedByHeatOrSustainedBufferingAndReturnsOnlyWhenCalm() {
        val g = MirrorLoadGate(stableMs = 10_000, bufferingSuspendMs = 8_000)
        assertEquals(MirrorLoadGate.State.NORMAL, g.update(0, false, 0))
        assertEquals("brief buffering only lowers quality", MirrorLoadGate.State.DEGRADED, g.update(0, true, 1_000))
        assertEquals(MirrorLoadGate.State.DEGRADED, g.update(0, true, 8_000))
        assertEquals("sustained buffering suspends", MirrorLoadGate.State.SUSPENDED, g.update(0, true, 9_500))
        assertEquals("not yet calm", MirrorLoadGate.State.SUSPENDED, g.update(0, false, 15_000))
        assertEquals("calm 10s: degrade first", MirrorLoadGate.State.DEGRADED, g.update(0, false, 19_600))
        assertEquals("and only then normal", MirrorLoadGate.State.NORMAL, g.update(0, false, 30_000))
        assertEquals("severe heat", MirrorLoadGate.State.SUSPENDED, g.update(3, false, 31_000))
        assertEquals(MirrorLoadGate.State.SUSPENDED, g.update(1, false, 35_000))
        g.reset()
        assertEquals("teardown clears the governor", MirrorLoadGate.State.NORMAL, g.update(0, false, 36_000))
        assertEquals(MirrorLoadGate.State.DEGRADED, g.update(2, false, 37_000))
    }

    @Test fun clockSamplesAreCompensatedForLatencyAndOldOnesDropped() {
        assertEquals(10_000L, PhoneMirrorPolicy.compensatedPosMs(10_000, false, 1.0, 0, 400))
        assertEquals("playing: half the round trip", 10_200L, PhoneMirrorPolicy.compensatedPosMs(10_000, true, 1.0, 0, 400))
        assertEquals("speed counts", 10_400L, PhoneMirrorPolicy.compensatedPosMs(10_000, true, 2.0, 0, 400))
        assertNull("too old", PhoneMirrorPolicy.compensatedPosMs(10_000, true, 1.0, 0, 2_000))
        assertNull("clock went backwards", PhoneMirrorPolicy.compensatedPosMs(10_000, true, 1.0, 500, 100))
    }

    @Test fun identityIsTheSameForWatchLiveAndShortsRoutes() {
        assertEquals("abcdefghijk", PhoneMirrorPolicy.videoId("/watch", "abcdefghijk"))
        assertEquals("abcdefghijk", PhoneMirrorPolicy.videoId("/live/abcdefghijk", null))
        assertEquals("abcdefghijk", PhoneMirrorPolicy.videoId("/shorts/abcdefghijk/", null))
        assertNull(PhoneMirrorPolicy.videoId("/results", null))
        assertNull(PhoneMirrorPolicy.videoId("/watch", "bad&id"))
        assertEquals("abcdefghijk", PhoneMirrorPolicy.videoIdFromUrl("https://m.youtube.com/watch?v=abcdefghijk&t=90#x"))
        assertEquals("abcdefghijk", PhoneMirrorPolicy.videoIdFromUrl("https://m.youtube.com/shorts/abcdefghijk"))
        assertNull(PhoneMirrorPolicy.videoIdFromUrl("https://m.youtube.com/results?search_query=a"))
        assertNull(PhoneMirrorPolicy.videoIdFromUrl("https://m.youtube.com/"))
        assertEquals(PhoneMirrorPolicy.Page.WATCH, PhoneMirrorPolicy.classify("/shorts/abcdefghijk"))
    }

    @Test fun onlyYouTubeFramesAreAllowedBecauseOnlyTheirOriginsGetTheMuteLock() {
        assertTrue(PhoneMirrorPolicy.allowFrameHost("www.youtube.com"))
        assertTrue(PhoneMirrorPolicy.allowFrameHost("youtube.com"))
        assertFalse(PhoneMirrorPolicy.allowFrameHost("evilyoutube.com"))
        assertFalse(PhoneMirrorPolicy.allowFrameHost("googleads.g.doubleclick.net"))
        assertFalse(PhoneMirrorPolicy.allowFrameHost(null))
        assertTrue(PhoneMirrorPolicy.isDocumentRequest("text/html,application/xhtml+xml"))
        assertFalse(PhoneMirrorPolicy.isDocumentRequest("image/avif,image/webp,*/*"))
        assertFalse(PhoneMirrorPolicy.isDocumentRequest(null))
    }

    @Test fun oneFallbackServesUnsupportedCrashedAndSuspendedStates() {
        val none = PhoneMirrorPolicy.Fallback.NONE
        assertEquals(none, PhoneMirrorPolicy.fallback(true, false, false))
        assertEquals(PhoneMirrorPolicy.Fallback.UNSUPPORTED, PhoneMirrorPolicy.fallback(false, true, true))
        assertEquals(PhoneMirrorPolicy.Fallback.FAILED, PhoneMirrorPolicy.fallback(true, true, true))
        assertEquals(PhoneMirrorPolicy.Fallback.SUSPENDED, PhoneMirrorPolicy.fallback(true, false, true))
        assertTrue("retry can help after a crash", PhoneMirrorPolicy.canRetry(PhoneMirrorPolicy.Fallback.FAILED))
        assertFalse("retry cannot fix an unsupported WebView", PhoneMirrorPolicy.canRetry(PhoneMirrorPolicy.Fallback.UNSUPPORTED))
        assertFalse("suspension ends by itself", PhoneMirrorPolicy.canRetry(PhoneMirrorPolicy.Fallback.SUSPENDED))
    }

    @Test fun syncResultsCarryTheDecodedHeightAndStatsReportIt() {
        assertEquals(SyncResult(120, "ok", 360), SyncResult.parse("\"120|ok|360\""))
        val s = MirrorSyncStats()
        s.record(SyncResult(10, "ok", 360))
        assertTrue(s.summary().contains("decodedHeightPx=360"))
    }

    @Test fun timeoutsMismatchesAndStaleAnswersAreWorthALogLine() {
        listOf<(MirrorSyncStats) -> Unit>({ it.timeouts++ }, { it.mismatches++ }, { it.stale++ },
            { it.suspensions++ }, { it.blockedFrames++ }).forEach {
            val s = MirrorSyncStats()
            assertTrue(s.isEmpty())
            it(s)
            assertFalse(s.isEmpty())
        }
        val s = MirrorSyncStats()
        s.crashes = 2
        assertFalse(s.summary(includeTotals = false).contains("crashes(total)"))
        assertTrue(s.summary(includeTotals = true).contains("crashes(total)=2"))
    }

    @Test fun lifecycleContracts() {
        fun read(name: String) = listOf(java.io.File("app/src/main/java/com/aayoutube/app/$name"),
            java.io.File("../app/src/main/java/com/aayoutube/app/$name")).first { it.isFile }.readText()
        val mirror = read("PhoneMirror.kt")
        // Idle release must not bump the revision (Compose would recreate the hidden renderer).
        val idle = mirror.substringAfter("private val idleRelease").substringBefore("private fun refreshHidden()")
        assertTrue(idle.contains("teardown()"))
        assertFalse(idle.contains("revision.value++"))
        // Heat/primary-health check happens before anything is loaded or synchronised.
        val tick = mirror.substringAfter("private val syncTick").substringBefore("private fun enterSuspension")
        assertTrue(tick.indexOf("gate.update(") in 0 until tick.indexOf("load(w, s, now)"))
        // Teardown clears session state and the governor.
        val teardown = mirror.substringAfter("private fun teardown()").substringBefore("/** Load the engine's video")
        assertTrue(teardown.contains("gate.reset()"))
        assertTrue(teardown.contains("suspended.value = false"))
        assertTrue(teardown.contains("nav.reset()"))
        // Sign-out stops the copy and clears selection guards before data is cleared, renderer or not.
        val signOut = mirror.substringAfter("fun beginSignOut()").substringBefore("fun onSessionCleared()")
        assertTrue(signOut.contains("syncSeq++") && signOut.contains("WebHost.resetSelectionGuards()"))
        assertTrue(read("WebHost.kt").substringAfter("fun clearSession()").substringBefore("youtubeAccount.value")
            .contains("PhoneMirror.beginSignOut()"))
        // Phone selections carry the requested start position like the player's.
        assertTrue(read("BrowseHost.kt").contains("TimestampParam.parseMs(uri.getQueryParameter(\"t\")"))
        // The pane composes the player only while it should be visible.
        assertTrue(read("Screens.kt").contains("if (mirrorShown && mirrorWanted) ProjectedMirrorPlayer("))
    }

    @Test fun rejectedReloadsDoNotExtendTheCooldown() {
        val g = RateGuard(2, 1000)
        assertTrue(g.record(10))
        assertTrue(g.record(20))
        for (now in 30L..1000L step 10) assertFalse(g.record(now))
        assertEquals(1011L, g.nextAllowedAt(1000))
        assertTrue(g.record(1011))
    }

    @Test fun queuedBridgeCommandsAndIntentsCannotOutliveTheirDocument() {
        val queued = mutableListOf<() -> Unit>()
        val got = mutableListOf<String>()
        val bridge = MirrorBridge("secret", { false }, { a, _, _ -> got += a },
            { got += it }, { queued += it })
        val old = bridge.open("secret")
        bridge.control("pause", 0.0, "secret", old, 1.0, "AAAAAAAAAAA")
        bridge.intent("https://m.youtube.com/", "secret", old)
        val fresh = bridge.open("secret")
        bridge.control("play", 0.0, "secret", fresh, 1.0, "AAAAAAAAAAA")
        queued.forEach { it() }
        assertEquals(listOf("play"), got)
    }

    @Test fun exactNavigationKeysPreserveSearchAndTime() {
        val a = PhoneMirrorPolicy.navigationKey("https://m.youtube.com/watch?v=AAAAAAAAAAA&t=20")
        val b = PhoneMirrorPolicy.navigationKey("https://m.youtube.com/watch?t=30&v=AAAAAAAAAAA")
        assertNotEquals(a, b)
        assertEquals(a, PhoneMirrorPolicy.navigationKey("https://m.youtube.com/watch?t=20&v=AAAAAAAAAAA"))
        assertNull(PhoneMirrorPolicy.navigationKey("https://example.com/watch?v=AAAAAAAAAAA"))
        val n = NavAuthorizer()
        n.onIntent(a, 100)
        assertFalse(n.authorize(b, 150))
        assertTrue(n.authorize(a, 150))
    }

    @Test fun transportResetAndBusyChangesAreNotLost() {
        val g = TransportGuard()
        assertTrue(g.accept("next", 100))
        assertFalse(g.accept("next", 200))
        g.reset()
        assertTrue(g.accept("next", 200))
        val s = Snap(id = "AAAAAAAAAAA", playing = true)
        assertTrue(PhoneMirrorPolicy.pokeNeeded(s, s.copy(buffering = true)))
        assertTrue(PhoneMirrorPolicy.pokeNeeded(s, s.copy(ad = true)))
    }
}
