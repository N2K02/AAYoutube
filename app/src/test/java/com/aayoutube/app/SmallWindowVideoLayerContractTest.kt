package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Second mini player / PiP entry went black while frames kept decoding. */
class SmallWindowVideoLayerContractTest {
    private val js: String =
        listOf(File("app/src/main/assets/aay.js"), File("../app/src/main/assets/aay.js"))
            .first { it.isFile }.readText()

    private val rebuild = js.substringAfter("function rebuildVideoLayer(v, why) {").substringBefore("\n  function ytPlayer()")

    @Test fun rebuildRecreatesLayerWithoutTouchingPlayback() {
        assertTrue(rebuild.contains("v.style.setProperty('display', 'none', 'important')"))
        assertTrue("restores the page's own display value", rebuild.contains("if (prev) v.style.setProperty('display', prev, prio); else v.style.removeProperty('display')"))
        assertTrue(rebuild.contains("if (now - layerRebuildAt < 1000) return false;"))
        for (bad in listOf("currentTime", ".pause(", ".play(", "load(", "location"))
            assertFalse("no playback side effects: $bad", rebuild.contains(bad))
    }

    @Test fun everyMiniAndPipEntryGetsAFreshLayerAfterSettling() {
        val small = js.substringAfter("function smallLayout() {").substringBefore("\n  }\n")
        assertTrue(small.contains("rebuildVideoLayer(v, A.inPip ? 'pip' : 'mini')"))
        assertTrue("stale transitions do nothing", small.contains("if (revision !== layoutRevision || !(A.isMini || A.inPip)) return;"))
        assertTrue("paused video left alone", small.contains("if (v && !v.paused) rebuildVideoLayer("))
    }

    @Test fun returningVisibleAndFrameStallAlsoRebuild() {
        assertTrue(js.contains("rebuildVideoLayer(v, 'visible')"))
        assertTrue(js.contains("if (!rebuildVideoLayer(v, 'stall')) repaintVideo(v);"))
    }
}
