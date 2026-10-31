package com.aayoutube.app

import android.app.Activity
import android.os.Bundle

/** AABrowser-style no-op target: the native Android Auto Music card must not
 * move the phone/legacy-projected WebView to a different screen. */
class NoOpMediaActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.add("MEDIA", "Media card click handled without opening video")
        finish()
    }
}
