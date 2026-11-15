package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCodecPolicyTest {
    private fun read(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun manualCodecSelectionRemoved() {
        val flags = read("app/src/main/java/com/aayoutube/app/Features.kt")
        val screen = read("app/src/main/java/com/aayoutube/app/Screens.kt")
        val js = read("app/src/main/assets/aay.js")
        assertFalse(flags.contains("videoCodec"))
        assertFalse(screen.contains("CodecPicker("))
        assertFalse(screen.contains("Video format"))
        assertFalse(js.contains("CFG.videoCodec"))
        assertFalse(js.contains("C0.videoCodec"))
    }

    @Test fun nativeDecoderCapabilityGateRemains() {
        val js = read("app/src/main/assets/aay.js")
        assertTrue(js.contains("return tooLarge(t);"))
        assertTrue(js.contains("origInfo(cfg)"))
        assertTrue(js.contains("aay-auto-codec-migrated"))
        assertTrue(read("app/src/main/java/com/aayoutube/app/WebHost.kt")
            .contains("DecoderCapabilities.json()"))
    }
}
