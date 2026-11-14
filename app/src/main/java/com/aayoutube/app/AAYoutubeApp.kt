package com.aayoutube.app

import android.app.Application

class AAYoutubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // One process, as in AABrowser: screens, WebView, car projection and media service.
        // The WebViews themselves are still created lazily, on first use.
        AppLog.init(this)
        Features.init(this)
        LyricsPrefs.init(this)
        AAYSoundFx.init(this)
        Library.init(this)
        WebHost.init(this)
        BrowseHost.init(this)
    }

    /**
     * Under memory pressure, drop the second (browse) YouTube WebView unless it is on
     * screen (mini player, or browsing after closing it). It is rebuilt the next time the mini player opens. Keeping it
     * pushed the phone into swap, and swap stalls are audible as stutter.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (MemoryTrimPolicy.releaseBrowse(level, WebHost.mode.value != WebHost.PlayerMode.FULL))
            BrowseHost.release()
    }
}
