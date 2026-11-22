package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guardrails for security and cleanup behavior that must survive refactors. */
class PlaybackHardeningContractTest {
    private fun read(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun webViewRejectsInsecureMixedContent() {
        val source = read("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(source.contains("s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW"))
        assertFalse(source.contains("s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE"))
    }

    @Test fun failedAndLatePixelCopiesReleaseTheirBitmap() {
        val source = read("app/src/main/java/com/aayoutube/app/PlayerTransition.kt")
        assertTrue(source.contains("result == PixelCopy.SUCCESS && cont.isActive"))
        assertTrue(source.contains("bmp.recycle()"))
        assertTrue(source.contains("}.onFailure {\n                bmp.recycle()"))
    }

    @Test fun failedForegroundPromotionCannotLeaveStickyServiceRunning() {
        val source = read("app/src/main/java/com/aayoutube/app/PlaybackService.kt")
        assertTrue(source.contains("Foreground start failed:"))
        assertTrue(source.contains("updatePlaybackWakeLock(false)"))
        assertTrue(source.contains("stopSelf(startId)"))
        assertTrue(source.contains("return START_NOT_STICKY"))
    }

    @Test fun carTemplateValidatesKnownHostsBySignature() {
        val source = read("app/src/main/java/com/aayoutube/app/CarMediaTemplateService.kt")
        assertTrue(source.contains("addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)"))
        assertFalse(source.contains("ALLOW_ALL_HOSTS_VALIDATOR"))
    }
}
