package com.aayoutube.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalSabrProviderTest {
    private fun descriptor(url: String = "https://r1.googlevideo.com/videoplayback?expire=2000000000"): JSONObject {
        val f = JSONObject().put("itag", 140).put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
            .put("bitrate", 128000).put("audioSampleRate", 44100).put("audioChannels", 2)
            .put("contentLength", "100000").put("lastModified", "123")
            .put("initRange", JSONObject().put("start","0").put("end","100"))
            .put("indexRange", JSONObject().put("start","101").put("end","200"))
        return JSONObject().put("videoId","abc").put("durationMs",120000)
            .put("serverAbrStreamingUrl",url).put("ustreamerConfig","YWJj")
            .put("audio", JSONArray().put(f))
    }
    @Test fun buildsAudioOnlyManifestFromAvailableMetadata() {
        val result = LocalSabrProvider.parse("abc", descriptor().toString(), 1000) as StreamResult.Resolved
        val s = result.streams.audio.single()
        assertNotNull(s.sabrManifest)
        assertTrue(SabrManifestPolicy.valid(s.sabrManifest!!, s.mimeType, s.codecs))
        assertEquals(44100, s.sampleRate)
        assertTrue(s.sabrManifest!!.contains("sabr://audio?key="))
    }
    @Test fun staleMissingOrUnauthorizedEndpointNeverBecomesPlayable() {
        assertTrue(LocalSabrProvider.parse("different", descriptor().toString(), 1000) is StreamResult.Unavailable)
        assertTrue(LocalSabrProvider.parse("abc", descriptor("https://googlevideo.com.evil.test/x").toString(), 1000) is StreamResult.Unavailable)
        assertTrue(LocalSabrProvider.parse("abc", descriptor().put("ustreamerConfig", "").toString(), 1000) is StreamResult.Unavailable)
        assertTrue(LocalSabrProvider.parse("abc", descriptor("https://r1.googlevideo.com/x?expire=1").toString(), 1000) is StreamResult.Unavailable)
    }
}
