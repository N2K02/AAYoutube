package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class WebConsolePolicyTest {
    @Test fun suppressRepeatedPreloadWarnings() {
        assertNull(WebConsolePolicy.clean("The resource was preloaded using link preload but not used"))
    }
    @Test fun suppressGiganticInlineImageDumps() {
        assertNull(WebConsolePolicy.clean("Loading the image 'data:image/png;base64,abcdef123456'"))
        assertEquals("asset [INLINE_IMAGE]", WebConsolePolicy.clean("asset data:image/png;base64,abcdef123456"))
    }
    @Test fun preservePlaybackAndNetworkDiagnostics() {
        assertEquals("net::ERR_NAME_NOT_RESOLVED", WebConsolePolicy.clean("net::ERR_NAME_NOT_RESOLVED"))
        assertEquals("video waiting ready=0", WebConsolePolicy.clean("video waiting ready=0"))
        assertEquals(600, WebConsolePolicy.clean("e".repeat(700))!!.length)
    }
}
