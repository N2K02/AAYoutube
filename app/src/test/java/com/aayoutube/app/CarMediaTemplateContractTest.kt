package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Protect both independent AA entries and their supported media classification. */
class CarMediaTemplateContractTest {
    private fun read(path: String): String = listOf(File(path), File("../$path"))
        .first { it.isFile }.readText()

    @Test fun legacyVideoAndMediaBrowserSurviveModernTemplateRegistration() {
        val xml = read("app/src/main/AndroidManifest.xml")
        assertTrue(xml.contains("android:name=\".LegacyProjectionService\""))
        assertTrue(xml.contains("com.google.android.gms.car.category.CATEGORY_PROJECTION_OEM"))
        assertTrue(xml.contains("android:name=\".PlaybackService\""))
        assertTrue(xml.contains("android.media.browse.MediaBrowserService"))
        assertTrue(xml.contains("android:name=\".CarMediaTemplateService\""))
        assertTrue(xml.contains("androidx.car.app.category.MEDIA"))
        assertFalse(xml.contains("androidx.car.app.category.NAVIGATION"))
        assertTrue(xml.contains("androidx.car.app.MEDIA_TEMPLATES"))
        assertTrue(xml.contains("androidx.car.app.minCarApiLevel"))
    }

    @Test fun descriptorDeclaresTemplateWithoutDroppingProjectionOrAudio() {
        val xml = read("app/src/main/res/xml/automotive_app_desc.xml")
        for (kind in listOf("service", "projection", "media", "template"))
            assertTrue("Missing $kind", xml.contains("name=\"$kind\""))
    }

    @Test fun templateServiceDoesNotOwnOrReparentVideoWebview() {
        val src = read("app/src/main/java/com/aayoutube/app/CarMediaTemplateService.kt")
        assertTrue(src.contains("CarAppService()"))
        assertTrue(src.contains("MediaInfoScreen"))
        assertFalse(src.contains("WebView("))
        assertFalse(src.contains("setContentView("))
        assertFalse(src.contains("CarActivityService"))
        assertFalse(src.contains("ALLOW_ALL_HOSTS_VALIDATOR"))
    }
}
