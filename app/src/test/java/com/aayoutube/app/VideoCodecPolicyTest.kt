package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCodecPolicyTest {
    @Test fun pixelWithHardwareAv1DefaultsToAv1() {
        assertEquals("av1", VideoCodecPolicy.default("Google", "Pixel 8 Pro", hardwareAv1 = true))
    }

    @Test fun everythingElseDefaultsToH264() {
        assertEquals("h264", VideoCodecPolicy.default("Google", "Pixel 5", hardwareAv1 = false))
        assertEquals("h264", VideoCodecPolicy.default("samsung", "SM-S918B", hardwareAv1 = true))
        assertEquals("h264", VideoCodecPolicy.default("Google", "Chromecast", hardwareAv1 = true))
    }

    @Test fun valuesAreNormalizedAndLabelled() {
        assertEquals("h264", VideoCodecPolicy.normalize("hevc"))
        assertEquals("vp9", VideoCodecPolicy.normalize("vp9"))
        assertEquals(listOf("AV1", "VP9", "H.264"), VideoCodecPolicy.options.map(VideoCodecPolicy::label))
    }

    @Test fun choiceIsStoredDefaultedOnceAndSentToThePage() {
        val f = listOf(File("app/src/main/java/com/aayoutube/app/Features.kt"),
            File("../app/src/main/java/com/aayoutube/app/Features.kt")).first { it.isFile }.readText()
        assertTrue(f.contains(".put(\"videoCodec\", videoCodec)"))
        assertTrue(f.contains(".putString(\"videoCodec\", n.videoCodec)"))
        assertTrue(f.contains("prefs.getString(\"videoCodec\", null) ?: VideoCodecPolicy.deviceDefault()"))
    }
}
