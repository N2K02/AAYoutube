package com.aayoutube.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AuthorizedAudioProviderTest {
    private val audio = """{"url":"https://media.example.org/track.m4a","mimeType":"audio/mp4","codecs":"mp4a.40.2","bitrate":128000,"sampleRate":44100,"channels":2}"""
    private fun manifest(id: String = "abc", authorized: Boolean = true, stream: String = audio) =
        """{"videoId":"$id","authorized":$authorized,"durationMs":120000,"audio":[$stream]}"""

    @Test fun httpsOnlyAndNoCredentialsOrQuery() {
        assertEquals("https://example.org/resolve", AuthorizedAudioProvider.normalizeEndpoint(" https://example.org/resolve "))
        for (s in listOf("", "http://example.org/resolve", "https://user:secret@example.org", "https://example.org?a=b", "https://example.org/#x", "file:///tmp/x"))
            assertNull(s, AuthorizedAudioProvider.normalizeEndpoint(s))
    }
    @Test fun resolvesSeparateAudioAndSendsOnlyId() = runBlocking<Unit> {
        var requested = ""
        val p = AuthorizedAudioProvider.create("https://example.org/resolve", { requested = it; manifest() }, { 1000 })!!
        val r = p.resolve("abc") as StreamResult.Resolved
        assertEquals("https://example.org/resolve?videoId=abc", requested)
        assertTrue(r.streams.separateAudio)
        assertEquals(128000, r.streams.audio.single().bitrate)
        assertEquals(StreamSelection.AAC, r.streams.audio.single().codecMime)
        assertTrue(r.streams.audio.single().headers.isEmpty())
        assertTrue(p.resolve("abc?bad") is StreamResult.Unavailable)
    }
    @Test fun invalidUnauthorizedOrExpiredManifestFallsBack() {
        fun unavailable(body: String) = assertTrue(AuthorizedAudioProvider.parseManifest("abc", body, 1000) is StreamResult.Unavailable)
        unavailable(manifest(id = "wrong"))
        unavailable(manifest(authorized = false))
        unavailable(manifest(stream = audio.replace("audio/mp4", "video/mp4")))
        unavailable(manifest(stream = audio.replace("https://", "http://")))
        unavailable(manifest(stream = audio.dropLast(1) + ""","expiresAtMs":2000}"""))
        unavailable("not json")
        unavailable("x".repeat(131073))
    }
}
