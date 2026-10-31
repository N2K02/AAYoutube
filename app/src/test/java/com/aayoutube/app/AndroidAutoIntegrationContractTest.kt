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
            assertTrue(descriptor.contains("name=\"projection\""))
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
