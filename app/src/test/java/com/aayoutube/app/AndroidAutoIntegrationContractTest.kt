package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** AABrowser's three Android Auto launch paths and video lifecycle. */
class AndroidAutoIntegrationContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun legacyAndWideVideoEntriesCoexistWithMediaSession() {
        val xml = source("app/src/main/AndroidManifest.xml")
        assertTrue(xml.contains("android:name=\".LegacyProjectionService\""))
        assertTrue(xml.contains("com.google.android.gms.car.category.CATEGORY_PROJECTION"))
        assertTrue(xml.contains("com.google.android.gms.car.category.CATEGORY_PROJECTION_OEM"))
        assertTrue(xml.contains("android:name=\".PlaybackService\""))
        assertTrue(xml.contains("android.media.browse.MediaBrowserService"))
        assertTrue(xml.contains("MEDIA_PLAY_FROM_SEARCH"))
        assertTrue(xml.contains("android:name=\".CarVideoService\""))
        assertTrue(xml.contains("androidx.car.app.CarAppService"))
        assertTrue(xml.contains("androidx.car.app.minCarApiLevel"))
        assertTrue(xml.contains("androidx.car.app.NAVIGATION_TEMPLATES"))
        val gradle = source("app/build.gradle.kts")
        assertTrue(gradle.contains("androidx.car.app:app:"))
        assertTrue(gradle.contains("androidx.car.app:app-projected:"))
    }

    @Test fun carPageKeepsMediaSessionFallbackAndTouchSeeking() {
        val js = source("app/src/main/assets/aay.js")
        assertTrue(js.contains("Object.defineProperty(navigator, 'mediaSession'"))
        assertTrue(js.contains("window.MediaMetadata = function"))
        assertTrue(js.contains("AABrowser-style tap-to-seek"))
        assertTrue(js.contains("v.currentTime = fraction * v.duration"))
    }

    @Test fun serviceDisconnectNeverRestartsUserPausedVideo() {
        val kotlin = source("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        assertTrue(kotlin.contains("recovery.onLinkLost(snapshot)"))
        assertTrue(kotlin.contains("send(Msg.SYNC)"))
        assertTrue(kotlin.contains("recovery.onMissingPlayer()"))
        assertTrue(kotlin.contains("command == Msg.PAUSE || command == Msg.STOP || command == Msg.QUIT"))
        assertFalse(kotlin.contains("pending.addFirst(Msg.OPEN"))
        assertTrue(kotlin.contains("send(Msg.OPEN, Bundle().apply"))
        assertTrue(kotlin.contains("MediaRetryPolicy.shouldRetry(retries, hasWork)"))
        assertTrue(kotlin.contains("handler.removeCallbacks(retry)"))
    }
    @Test fun carVideoAndAudioHaveSeparateEntries() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        val wide = manifest.substringAfter("android:name=\".CarVideoService\"").substringBefore("</service>")
        val media = manifest.substringAfter("android:name=\".PlaybackService\"").substringBefore("</service>")
        assertTrue(wide.contains("androidx.car.app.category.NAVIGATION"))
        assertTrue(wide.contains("AAYouTube Video"))
        assertTrue(media.contains("android.media.browse.MediaBrowserService"))
        assertTrue(media.contains("AAYouTube Audio"))
        assertFalse(manifest.substringAfter("android:name=\".MainActivity\"")
            .substringBefore("</activity>").contains("CAR_LAUNCHER"))
        for (type in listOf("xml", "xml-v35")) {
            val desc = source("app/src/main/res/" + type + "/automotive_app_desc.xml")
            listOf("service", "projection", "media", "template").forEach {
                assertTrue(desc.contains("name=\"$it\""))
            }
        }
    }

    @Test fun widePreservesVideoThroughSurfaceChangesAndShutdown() {
        val src = source("app/src/main/java/com/aayoutube/app/CarVideoService.kt")
        listOf("HostValidator.Builder(applicationContext)", "old.surface = surface",
            "old.resize(rawWidth, rawHeight, rawDpi)", "onVisibleAreaChanged",
            "onStableAreaChanged", "CarSurfacePolicy.viewport", "WebHost.requestReattachment()",
            "WebHost.showOnCar(true, carOwner)", "WebHost.showOnCar(false, carOwner)",
            "mediaController?.disconnect()", "scope.cancel()",
            "WebHost.webVersion.drop(1)", "show(p, frame, forceRefresh = true)",
            "rendererJob?.cancel()").forEach {
            assertTrue("Missing " + it, src.contains(it))
        }
        val frame = source("app/src/main/java/com/aayoutube/app/CarFrameLayout.kt")
        assertTrue(frame.contains("dispatchWindowVisibilityChanged"))
        val modes = source("app/src/main/java/com/aayoutube/app/CarMode.kt")
        assertTrue(modes.contains("ComponentName(c, LegacyProjectionService::class.java)"))
        assertTrue(modes.contains("ComponentName(c, CarVideoService::class.java)"))
    }
}
