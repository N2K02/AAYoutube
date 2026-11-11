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
            AppLog.init(this)
            Features.init(this)
            LyricsPrefs.init(this)
            AAYSoundFx.init(this)
            WebHost.init(this)
            BrowseHost.init(this)
        } else {
            AppLog.init(this) // Media-service events also appear in the Settings Logs page.
            Library.init(this)
        }
    }

    /**
     * Under memory pressure, drop the second (browse) YouTube WebView unless it is on
     * screen (mini player, or browsing after closing it). It is rebuilt the next time the mini player opens. Keeping it
     * pushed the phone into swap, and swap stalls are audible as stutter.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!isWebProcess()) return
        if (MemoryTrimPolicy.releaseBrowse(level, WebHost.mode.value != WebHost.PlayerMode.FULL))
            BrowseHost.release()
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
