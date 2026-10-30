package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TopMenuPolicyTest {
    private fun read(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun defaultsToThreeSeconds() {
        assertEquals(3, FeatureFlags().topMenuTimeoutSeconds)
        assertEquals(3_000L, TopMenuPolicy.timeoutMillis(3))
    }
    @Test fun supportsFasterSlowerAndAlwaysVisible() {
        assertEquals(listOf(0, 1, 2, 3, 5, 10, 15), TopMenuPolicy.options)
        assertNull(TopMenuPolicy.timeoutMillis(0))
        assertEquals(1_000L, TopMenuPolicy.timeoutMillis(1))
        assertEquals(15_000L, TopMenuPolicy.timeoutMillis(15))
        assertEquals(3, TopMenuPolicy.normalize(300))
        assertEquals(3, TopMenuPolicy.normalize(-1))
    }
    @Test fun phoneVideoHeaderDoesNotResizeWebViewAndSettingsHeaderRemains() {
        val screen = read("app/src/main/java/com/aayoutube/app/Screens.kt")
        val watchHeader = screen.substringAfter("// App-owned watch header overlays the WebView")
            .substringBefore("if (lyricsOnScreen) {")
        assertTrue(watchHeader.contains("AnimatedVisibility("))
        assertTrue(watchHeader.contains("visible = topMenuVisible"))
        assertTrue(watchHeader.contains("statusBarsPadding().height(28.dp)"))
        assertTrue(watchHeader.contains("topMenuEpoch++"))
        assertTrue(screen.contains("if (!watchTab && headerAllowed) {"))
        assertTrue(screen.contains("delay(timeout)"))
        assertTrue(screen.contains("TopMenuPolicy.timeoutMillis(flags.topMenuTimeoutSeconds)"))
        assertTrue(screen.contains("WebPane(Modifier.fillMaxSize())"))
    }
    @Test fun carAndPhoneShareFeatureAndRestoreByTopEdge() {
        val legacy = read("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        val features = read("app/src/main/java/com/aayoutube/app/Features.kt")
        assertTrue(legacy.contains("TopMenuPolicy.timeoutMillis(flags.topMenuTimeoutSeconds)"))
        assertTrue(legacy.contains("controlsVisible = true; controlsVersion++"))
        assertTrue(legacy.contains("height(28.dp)"))
        assertTrue(features.contains("prefs.getInt(\"topMenuTimeoutSeconds\""))
        assertTrue(features.contains(".putInt(\"topMenuTimeoutSeconds\""))
    }
}
