package com.aayoutube.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogRedactionTest {
    @Test fun removesBearerToken() {
        val output = AppLog.redact("Authorization: Bearer abc.DEF-123")
        assertFalse(output.contains("abc.DEF-123"))
    }

    @Test fun removesPassword() {
        val output = AppLog.redact("password=my-secret")
        assertFalse(output.contains("my-secret"))
    }

    @Test fun removesEmailAndUrl() {
        val output = AppLog.redact("https://example.com/?token=secret contact me@example.org")
        assertFalse(output.contains("example.com"))
        assertFalse(output.contains("me@example.org"))
    }

    @Test fun removesPhoneNumber() {
        val output = AppLog.redact("Caller +230 5123 4567")
        assertFalse(output.contains("5123 4567"))
        assertTrue(output.contains("[NUMBER]"))
    }

    @Test fun retainsHarmlessDiagnosticCodes() {
        assertTrue(AppLog.redact("WEBVIEW_LOAD_FAILED").contains("WEBVIEW_LOAD_FAILED"))
    }
}
