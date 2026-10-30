package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Only AABrowser legacy projection provides the car video entry. */
class AndroidAutoIntegrationContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun legacyProjectionIsSoleVideoEntryWhileMediaSessionRemains() {
        val xml = source("app/src/main/AndroidManifest.xml")
        assertTrue(xml.contains("android:name=\".LegacyProjectionService\""))
        assertTrue(xml.contains("com.google.android.gms.car.category.CATEGORY_PROJECTION"))
        assertTrue(xml.contains("com.google.android.gms.car.category.CATEGORY_PROJECTION_OEM"))
        assertTrue(xml.contains("android:name=\".PlaybackService\""))
        assertFalse(xml.contains("android.media.browse.MediaBrowserService"))
        assertTrue(xml.contains("MEDIA_PLAY_FROM_SEARCH"))
        assertFalse(xml.contains("android:name=\".CarVideoService\""))
        assertFalse(xml.contains("androidx.car.app.CarAppService"))
        assertFalse(xml.contains("androidx.car.app.minCarApiLevel"))
        assertFalse(xml.contains("androidx.car.app.NAVIGATION_TEMPLATES"))
        val gradle = source("app/build.gradle.kts")
        assertFalse(gradle.contains("androidx.car.app:app:"))
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
    @Test fun androidAutoLaunchesVideoInsteadOfMediaTemplate() {
        val xml = source("app/src/main/AndroidManifest.xml")
        val mainFilter = xml.substringAfter("android:name=\".MainActivity\"")
            .substringAfter("<intent-filter>").substringBefore("</intent-filter>")
        assertTrue(mainFilter.contains("android.intent.category.CAR_LAUNCHER"))
        assertTrue(mainFilter.contains("androidx.car.app.category.NAVIGATION"))
        assertTrue(mainFilter.contains("android.intent.category.APP_MAPS"))
        val mediaEntry = xml.substringAfter("android:name=\".PlaybackService\"")
            .substringBefore("/>")
        assertTrue(mediaEntry.contains("android:exported=\"false\""))
        assertFalse(mediaEntry.contains("<intent-filter>"))
        val phone = source("app/src/main/java/com/aayoutube/app/MainActivity.kt")
        assertTrue(phone.contains("intent?.hasCategory(\"android.intent.category.CAR_LAUNCHER\") == true"))
        val service = source("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        assertTrue(service.contains("MediaBrowserServiceCompat()"))
        assertTrue(service.contains("MediaSessionCompat(this"))
        val client = source("app/src/main/java/com/aayoutube/app/CompatMediaConnection.kt")
        assertTrue(client.contains("ComponentName(context, PlaybackService::class.java)"))
    }

}
