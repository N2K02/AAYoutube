package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** User-tested Android Auto video and standalone host-rendered music entry. */
class AndroidAutoIntegrationContractTest {
    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun videoAndMusicAreTwoDifferentCarEntries() {
        val manifest = src("app/src/main/AndroidManifest.xml")
        val video = manifest.substringAfter("android:name=\".LegacyProjectionService\"").substringBefore("</service>")
        val music = manifest.substringAfter("android:name=\".PlaybackService\"").substringBefore("</service>")
        assertTrue(video.contains("CATEGORY_PROJECTION"))
        assertTrue(video.contains("CATEGORY_PROJECTION_OEM"))
        assertTrue(src("app/src/main/java/com/aayoutube/app/CarMode.kt")
            .contains("Video projection manifest discoverable="))
        val service = src("app/src/main/java/com/aayoutube/app/LegacyProjectionService.kt")
        assertTrue(service.contains("Android Auto requested legacy projection service"))
        assertTrue(service.contains("Android Auto resolving projected video activity"))
        assertTrue(video.contains("android:process=\":web\""))
        // Android Auto has independent video/music entries; their labels
        // must not change the phone launcher name.
        assertTrue(video.contains("android:label=\"AAYT Video\""))
        assertTrue(music.contains("android:label=\"AAYT Music\""))
        val phone = manifest.substringAfter("android:name=\".MainActivity\"").substringBefore("</activity>")
        // AABrowser registers a single actual activity with car categories.
        // No separate launcher alias or competing AndroidX CarAppService.
        assertTrue(phone.contains("android.intent.category.CAR_LAUNCHER"))
        assertTrue(phone.contains("androidx.car.app.category.NAVIGATION"))
        assertTrue(phone.contains("android.intent.category.APP_MAPS"))
        assertTrue(phone.contains("android.intent.category.LAUNCHER"))
        val carWebLink = phone.substringAfter("<!-- Opens YouTube links in this app -->")
            .substringBefore("</intent-filter>")
        assertTrue(carWebLink.contains("android.intent.action.VIEW"))
        assertTrue(carWebLink.contains("android.intent.category.CAR_LAUNCHER"))
        assertTrue(carWebLink.contains("androidx.car.app.category.NAVIGATION"))
        assertTrue(carWebLink.contains("android.intent.category.APP_MAPS"))
        assertTrue(carWebLink.contains("android.intent.category.BROWSABLE"))
        assertTrue(carWebLink.contains("android:host=\"youtube.com\""))
        assertFalse(carWebLink.contains("android:scheme=\"http\""))
        val mainActivity = src("app/src/main/java/com/aayoutube/app/MainActivity.kt")
        assertTrue(mainActivity.contains("logCarActivityEntry(\"create\", intent)"))
        assertTrue(mainActivity.contains("logCarActivityEntry(\"newIntent\", intent)"))
        assertTrue(mainActivity.contains("carDisplay=$carDisplay"))
        assertFalse(mainActivity.contains("AppLog.add(\"CAR\", launch?.dataString"))

        assertFalse(manifest.contains("android:name=\".AutoVideoLauncher\""))
        assertTrue(src("app/src/main/java/com/aayoutube/app/CarMode.kt")
            .contains("Video MainActivity car-launcher discoverable="))
        assertTrue(src("app/src/main/java/com/aayoutube/app/CarMode.kt")
            .contains("Video MainActivity maps discoverable="))
        assertTrue(manifest.contains("android:label=\"AAYoutube\""))
        assertFalse(manifest.contains("android:label=\"AaYT Video\""))
        assertFalse(manifest.contains("android:label=\"AAYouTube Music\""))
        assertTrue(music.contains("android:exported=\"true\""))
        assertTrue(music.contains("android.media.browse.MediaBrowserService"))
        assertFalse(music.contains("CATEGORY_PROJECTION"))
        // The AndroidX navigation side-panel previously froze the shared WebView.
        assertFalse(manifest.contains("android:name=\".CarVideoService\""))
        assertFalse(manifest.contains("androidx.car.app.CarAppService"))
        assertFalse(src("app/build.gradle.kts").contains("androidx.car.app:app:"))
        for (resource in listOf("xml", "xml-v35")) {
            val descriptor = src("app/src/main/res/$resource/automotive_app_desc.xml")
            assertFalse(descriptor.contains("name=\"projection\""))
            assertTrue(descriptor.contains("name=\"media\""))
            assertFalse(descriptor.contains("name=\"template\""))
        }
    }

    @Test fun musicBrowsesAndControlsAudioWithoutMovingVideoSurface() {
        val service = src("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        val link = src("app/src/main/java/com/aayoutube/app/CompatMediaConnection.kt")
        assertTrue(service.contains("MediaBrowserServiceCompat()"))
        assertTrue(service.contains("MediaSessionCompat(this"))
        assertTrue(service.contains("override fun onGetRoot("))
        assertTrue(service.contains("override fun onLoadChildren("))
        assertTrue(service.contains("folder(\"recent\", \"Recently played\")"))
        assertTrue(service.contains("folder(\"favourites\", \"Favourites\")"))
        assertTrue(service.contains("override fun onPlay()"))
        assertTrue(service.contains("override fun onPause()"))
        assertTrue(service.contains("override fun onSkipToNext()"))
        assertTrue(service.contains("override fun onSkipToPrevious()"))
        assertTrue(service.contains("override fun onSkipToQueueItem(id: Long)"))
        assertTrue(service.contains("session.setQueue(entries)"))
        assertTrue(service.contains("session.setQueueTitle(\"Up next\")"))
        assertTrue(service.contains("NoOpMediaActivity::class.java"))
        assertTrue(service.contains("override fun onPlayFromMediaId("))
        assertTrue(service.contains("recovery.onLinkLost(snapshot)"))
        assertTrue(link.contains("ComponentName(context, PlaybackService::class.java)"))
        assertFalse(service.contains("WebHost.attach("))
        assertFalse(service.contains("WebHost.showOnCar("))
    }

    @Test fun videoRestoresNativeFullSizeInsteadOfPhoneMiniViewport() {
        val web = src("app/src/main/java/com/aayoutube/app/WebHost.kt")
        val car = src("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        val js = src("app/src/main/assets/aay.js")
        assertTrue(web.contains("fun prepareForCarProjection()"))
        assertTrue(web.contains("mode.value = PlayerMode.FULL"))
        assertTrue(web.contains("window.__aay&&window.__aay.mini(false)"))
        val block = web.substringAfter("fun prepareForCarProjection()").substringBefore("fun refitCarViewport()")
        assertFalse(block.contains("cmd(\"play\")"))
        assertFalse(block.contains("loadUrl("))
        assertTrue(car.contains("WebHost.prepareForCarProjection()"))
        assertTrue(car.contains("frame.addOnLayoutChangeListener"))
        assertTrue(car.contains("WebHost.refitCarViewport()"))
        assertTrue(js.contains("A.refitCar = function ()"))
        assertTrue(js.contains("object-fit:contain!important"))
    }
}
