package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class SabrManifestPolicyTest {
    private val mpd = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT120S"><SupplementalProperty schemeIdUri="urn:youtube:sabr" value="e30="/><Period><AdaptationSet contentType="audio" mimeType="audio/mp4"><Representation id="140" codecs="mp4a.40.2" bandwidth="128000"><BaseURL>sabr://audio?key=140:</BaseURL><SegmentBase indexRange="10-20"><Initialization range="0-9"/></SegmentBase></Representation></AdaptationSet></Period></MPD>"""
    @Test fun acceptsOnlySingleAudioRepresentation() {
        assertTrue(SabrManifestPolicy.valid(mpd, "audio/mp4", "mp4a.40.2"))
        assertFalse(SabrManifestPolicy.valid(mpd, "audio/webm", "opus"))
        assertFalse(SabrManifestPolicy.valid(mpd.replace("contentType=\"audio\"", "contentType=\"video\""), "audio/mp4", "mp4a.40.2"))
        assertFalse(SabrManifestPolicy.valid(mpd.replace("sabr://audio?key=140:", "https://example.org/video"), "audio/mp4", "mp4a.40.2"))
        assertFalse(SabrManifestPolicy.valid(mpd.replace("<Initialization ", "<Initialization sourceURL=\"https://example.org/x\" "), "audio/mp4", "mp4a.40.2"))
        assertFalse(SabrManifestPolicy.valid("<!DOCTYPE x>" + mpd, "audio/mp4", "mp4a.40.2"))
    }
    @Test fun providerSupportsSabrMpdAndRejectsInvalidManifest() {
        val json = org.json.JSONObject().put("videoId", "abc").put("authorized", true)
            .put("audio", org.json.JSONArray().put(org.json.JSONObject().put("sabrMpd", mpd)
                .put("mimeType", "audio/mp4").put("codecs", "mp4a.40.2").put("bitrate", 128000).put("sampleRate", 44100)))
        val result = AuthorizedAudioProvider.parseManifest("abc", json.toString(), 1000) as StreamResult.Resolved
        assertEquals(mpd, result.streams.audio.single().sabrManifest)
        json.getJSONArray("audio").getJSONObject(0).put("sabrMpd", "<MPD/>")
        assertTrue(AuthorizedAudioProvider.parseManifest("abc", json.toString(), 1000) is StreamResult.Unavailable)
    }
}
