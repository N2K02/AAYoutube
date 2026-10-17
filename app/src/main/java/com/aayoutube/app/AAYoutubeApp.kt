package com.aayoutube.app

import android.app.Application
import android.os.Build
import java.io.File

class AAYoutubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The same Application class starts in both processes; each one sets up only what it uses.
        // Only the ":web" process may ever create a WebView.
        if (isWebProcess()) {
            Features.init(this)
            WebHost.init(this)
        } else {
            Library.init(this)
        }
    }

    private fun isWebProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim('\u0000') }.getOrDefault("")
        }
        return name.endsWith(":web")
    }
}
