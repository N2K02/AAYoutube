package com.aayoutube.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Exposes both a video-screen entry and a media-library audio-player entry to Android Auto.
 * Older versions could disable the media service; reset that stale component override so
 * media browsing and the video screen can coexist, as in AABrowser.
 */
object CarMode {
    fun serviceComponent(c: Context): ComponentName = ComponentName(c, PlaybackService::class.java)

    fun resetToCarScreen(c: Context) {
        val component = serviceComponent(c)
        val pm = c.packageManager
        if (pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) return
        AppLog.add("CAR", "Android Auto: media library and video screen enabled")
        runCatching {
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        }
    }
}
