package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class MediaArtworkLoaderTest {
    @Test fun missingMetadataArtworkUsesKnownYoutubeThumbnail() {
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            MediaArtworkLoader.urlFor("", "dQw4w9WgXcQ"))
        assertEquals("", MediaArtworkLoader.urlFor("", "invalid"))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            MediaArtworkLoader.urlFor("https://not-trusted.example/p.jpg", "dQw4w9WgXcQ"))
    }

    @Test fun onlyTrustedHttpsArtworkIsLoaded() {
        assertEquals("https://i.ytimg.com/vi/example/hqdefault.jpg",
            MediaArtworkLoader.allowedUrl("https://i.ytimg.com/vi/example/hqdefault.jpg"))
        assertEquals("https://lh3.googleusercontent.com/p/image",
            MediaArtworkLoader.allowedUrl("https://lh3.googleusercontent.com/p/image"))
        for (url in listOf(
            "", "http://i.ytimg.com/image.jpg", "https://ytimg.com.evil.com/a",
            "https://127.0.0.1/a", "https://user@i.ytimg.com/a",
            "https://i.ytimg.com:8443/a", "file:///etc/passwd"
        )) assertEquals("Rejected: $url", "", MediaArtworkLoader.allowedUrl(url))
    }
}
