package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ProjectedPhoneNavigationTest {
    private fun read(name: String): String {
        val path = "app/src/main/java/com/aayoutube/app/$name"
        return listOf(File(path), File("../$path")).first { it.isFile }.readText()
    }

    @Test fun phoneAndCarHaveIndependentBrowsingAndOnePlaybackDestination() {
        val browser = read("BrowseHost.kt")
        assertTrue(browser.contains("object BrowseHost : BrowseSession()"))
        assertTrue(browser.contains("val phone = BrowseSession"))
        assertTrue(browser.contains("WebHost.openUrl(url)"))
        assertTrue(browser.contains("if (web === w && browseToken == token)"))
        assertTrue(browser.contains("phone.release()"))
        assertTrue(browser.contains("phone.onSessionCleared()"))
        val pane = read("Screens.kt").substringAfter("private fun ProjectedPhonePane(")
            .substringBefore("private fun rememberLivePosition(")
        assertTrue(pane.contains("BrowseHost.phone.attach(ctx)"))
        assertTrue(pane.contains("BrowseHost.phone.detach(it)"))
        assertTrue(pane.contains("BrowseHost.phoneMode.value = WebHost.PlayerMode.HIDDEN"))
        assertFalse(pane.contains("WebHost.enterMini()"))
        assertFalse(pane.contains("WebHost.closePlayer()"))
        // The phone shows the original YouTube player, decoding only while shown, released after projection.
        assertTrue(pane.contains("PhoneMirror.attach(ctx)"))
        assertTrue(pane.contains("PhoneMirror.setVisible(mirrorShown && ((pip && started) || (resumed && visible)))"))
        assertTrue(pane.contains("PhoneMirror.release()"))
        assertFalse("no PixelCopy preview loop on the phone", pane.contains("CarPhonePreview"))
    }

    @Test fun phonePipDoesNotChangeProjectedPlayer() {
        val activity = read("MainActivity.kt")
        val callback = activity.substringAfter("override fun onPictureInPictureModeChanged(")
        assertTrue(callback.contains("if (WebHost.phonePreviewOnly || previewPip.value)"))
        assertTrue(callback.indexOf("return") < callback.indexOf("WebHost.pip(isInPictureInPictureMode)"))
        assertTrue(activity.contains("if (!WebHost.phonePreviewOnly) WebHost.pip(true)"))
        val web = read("WebHost.kt")
        assertTrue(web.contains("val phonePreviewOnly: Boolean get() = carShown || carAudioRetained.value"))
    }
}
