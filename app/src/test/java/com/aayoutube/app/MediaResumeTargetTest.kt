package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MediaResumeTargetTest {
    @Test fun liveSessionTakesPrecedenceOverSavedVideo() {
        val target = MediaResumeTarget.select(
            Snap(id = "current", posMs = 73_999L),
            Library.Last("old", 100L, "old title", "artist")
        )
        assertEquals(ResumeTarget("current", 73L), target)
    }

    @Test fun stoppedOrUnloadedPlayerResumesLastSavedVideo() {
        val last = Library.Last("saved", 183L, "", "")
        assertEquals(ResumeTarget("saved", 183L), MediaResumeTarget.select(Snap(), last))
        assertEquals(ResumeTarget("saved", 183L),
            MediaResumeTarget.select(Snap(userPaused = true), last))
    }

    @Test fun negativePositionsAndMissingSelectionsAreSafe() {
        assertEquals(ResumeTarget("current", 0L),
            MediaResumeTarget.select(Snap(id = "current", posMs = -1L), null))
        assertEquals(ResumeTarget("saved", 0L),
            MediaResumeTarget.select(Snap(), Library.Last("saved", -100L, "", "")))
        assertNull(MediaResumeTarget.select(Snap(), null))
        assertNull(MediaResumeTarget.select(Snap(), Library.Last("", 0L, "", "")))
    }

    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun nativePlayCanRecoverTheMissingWebViewInsteadOfDroppingTheCommand() {
        val media = source("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(media.contains("val target = MediaResumeTarget.select(snapshot, Library.last)"))
        assertTrue(media.contains("send(Msg.PLAY, Bundle().apply"))
        assertTrue(media.contains("putString(\"id\", target.id)"))
        assertTrue(web.contains("Msg.PLAY -> {"))
        assertTrue(web.contains("if (web == null || !mainFrameAllowsPlayback)"))
        assertTrue(web.contains("open(resumeId, d.getLong(\"sec\", 0L))"))
        assertTrue(web.contains("send(Msg.STATE, Snap().toBundle())"))
    }
}
