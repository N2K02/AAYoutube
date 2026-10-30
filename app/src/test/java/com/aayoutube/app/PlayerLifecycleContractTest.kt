package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PlayerLifecycleContractTest {
    private fun source(name: String): String =
        sequenceOf(File("app/src/main/java/com/aayoutube/app/$name"), File("../app/src/main/java/com/aayoutube/app/$name"))
            .first { it.isFile }.readText()

    @Test fun stableCarLayoutSurvivesReloadAndProjectionRestart() {
        val web = source("WebHost.kt")
        assertTrue(web.contains("car(\${carDisplay || carShown})"))
        assertTrue(web.contains("release(preserveCarOwners = true)"))
        assertTrue(web.contains("it.onResume(); it.resumeTimers(); it.invalidate()"))
        assertTrue(web.contains("load(withCarPlaybackIntent(normalise(url)))"))
        assertTrue(web.contains("webGeneration++"))
        assertTrue(web.contains("generation == webGeneration"))
        assertTrue(web.contains("rendererCrashCount <= 2"))
        val car = source("LegacyProjectionActivity.kt")
        assertTrue(car.contains("(frame.getChildAt(0) as? WebView)?.let { carInput?.bind(it) }"))
        assertTrue(car.contains("MainActivity.restorePhoneLink()"))
        val phone = source("MainActivity.kt")
        assertTrue(phone.contains("if (WebHost.requestLink === relinkPhone)"))
    }

    @Test fun quitClosesBothWebViewsAndPreventsReconnection() {
        val phone = source("MainActivity.kt")
        assertTrue(phone.contains("BrowseHost.release()"))
        assertTrue(phone.contains("releaseController()"))
        assertTrue(phone.contains("WebHost.requestLink = null"))
        val browse = source("BrowseHost.kt")
        assertTrue(browse.contains("previous?.destroy()"))
        assertTrue(browse.contains("created.value = false"))
        val media = source("PlaybackService.kt")
        assertTrue(media.contains("recovery.cancel()"))
        assertTrue(media.contains("handler.removeCallbacks(registrationTimeout)"))
        assertTrue(media.contains("send(Msg.QUIT, replyTo = incoming)"))
    }

    @Test fun secondaryBrowserCanRecoverIndependentlyWithoutAudibleMedia() {
        val browse = source("BrowseHost.kt")
        assertTrue(browse.contains("override fun onRenderProcessGone("))
        assertTrue(browse.contains("revision.value++"))
        val screens = sequenceOf(File("app/src/main/java/com/aayoutube/app/Screens.kt"),
            File("../app/src/main/java/com/aayoutube/app/Screens.kt")).first { it.isFile }.readText()
        assertTrue(screens.contains("key(browseRevision)"))
    }
}
