package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MiniPlayerTransitionContractTest {
    private fun read(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun keepWebViewAtFullLayoutSizeDuringMiniAnimation() {
        val root = read("app/src/main/java/com/aayoutube/app/Screens.kt")
        val pane = root.substringAfter("fun WebPane(").substringBefore("// ---------------------------------------------------------------------------- settings")
        assertTrue(pane.contains("animateFloatAsState("))
        assertTrue(pane.contains("else Modifier.fillMaxSize().graphicsLayer"))
        assertTrue(pane.contains("if (hidden) Modifier.size(1.dp)"))
        assertTrue(pane.contains("scaleX = 1f + (miniScaleX - 1f) * miniProgress"))
        assertTrue(pane.contains("scaleY = 1f + (miniScaleY - 1f) * miniProgress"))
        assertFalse(pane.contains("small -> miniMod.clip("))
    }

    @Test fun pipUsesSeamlessResizeOnSupportedPhones() {
        val activity = read("app/src/main/java/com/aayoutube/app/MainActivity.kt")
        assertTrue(activity.contains("builder.setSeamlessResizeEnabled(true)"))
        assertTrue(activity.contains("Build.VERSION.SDK_INT >= 31"))
    }
}
