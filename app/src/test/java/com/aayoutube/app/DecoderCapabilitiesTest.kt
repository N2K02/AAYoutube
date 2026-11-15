package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderCapabilitiesTest {
    @Test fun largestSupportedTierWins() {
        assertEquals(4320, DecoderCapabilities.maxHeight { w, h -> w <= 7680 && h <= 4320 })
        assertEquals(2160, DecoderCapabilities.maxHeight { w, h -> w <= 4096 && h <= 2304 })
        assertEquals(1080, DecoderCapabilities.maxHeight { w, h -> w <= 1920 && h <= 1088 })
        assertEquals(0, DecoderCapabilities.maxHeight { _, _ -> false })
    }

    @Test fun portraitOnlyDecodersStillCount() {
        // Some vendors publish 2160x3840 (portrait) limits only.
        assertEquals(2160, DecoderCapabilities.maxHeight { w, h -> w <= 2160 && h <= 3840 })
    }

    @Test fun tiersCoverYouTubeUpTo8K() {
        assertEquals(4320, DecoderCapabilities.TIERS.first())
        assertTrue(DecoderCapabilities.TIERS.contains(2160))
        assertEquals(30.0, DecoderCapabilities.MIN_FPS, 0.0)
    }

    @Test fun capsReachThePageBeforeYouTubeLoads() {
        fun read(p: String) = listOf(File(p), File("../$p")).first { it.isFile }.readText()
        val web = read("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("Features.current.toJson().put(\"decodeCaps\", DecoderCapabilities.json())"))
        val js = read("app/src/main/assets/aay.js")
        assertTrue(js.contains("var caps = C0.decodeCaps && typeof C0.decodeCaps === 'object' ? C0.decodeCaps : null;"))
        assertTrue(js.contains("if (caps) { var limit = codecLimit(codecFamily(t)); return !!limit && size > limit; }"))
        assertTrue(js.contains("if (!nativeCaps) try { localStorage.setItem('aay-max-smooth-h'"))
    }

    @Test fun vendorMeasuredRateWinsOverAdvertised() {
        assertTrue(DecoderCapabilities.meetsRate(advertised = false, achievableUpper = 60.0, fps = 60.0))
        assertTrue(DecoderCapabilities.meetsRate(false, 57.5, 60.0)) // within 5%
        assertFalse(DecoderCapabilities.meetsRate(advertised = true, achievableUpper = 24.0, fps = 30.0))
        assertTrue(DecoderCapabilities.meetsRate(advertised = true, achievableUpper = null, fps = 30.0))
        assertFalse(DecoderCapabilities.meetsRate(advertised = false, achievableUpper = null, fps = 30.0))
    }

    @Test fun hdrProfilesAreTheTenBitOnes() {
        val p = android.media.MediaCodecInfo.CodecProfileLevel::class.java
        assertTrue(DecoderCapabilities.tenBitProfiles("vp9").contains(
            p.getField("VP9Profile2").getInt(null)))
        assertTrue(DecoderCapabilities.tenBitProfiles("av1").contains(
            p.getField("AV1ProfileMain10").getInt(null)))
        assertFalse(DecoderCapabilities.tenBitProfiles("vp9").contains(
            p.getField("VP9Profile0").getInt(null)))
        assertTrue(DecoderCapabilities.tenBitProfiles("h264").isEmpty())
    }

    @Test fun capsCover60FpsAndHdr() {
        fun read(p: String) = listOf(File(p), File("../$p")).first { it.isFile }.readText()
        val caps = read("app/src/main/java/com/aayoutube/app/DecoderCapabilities.kt")
        assertTrue(caps.contains("put(codec + \"_60\""))
        assertTrue(caps.contains("put(codec + \"_hdr\""))
    }
}
