package com.aayoutube.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * AAYoutube in Android Auto is always the full screen with the video (no audio-only media player).
 *
 * Older versions could switch the media service off in favour of a media-player entry; Android keeps
 * that disabled state across updates, so it is put back to the default here.
 */
object CarMode {
    fun serviceComponent(c: Context): ComponentName = ComponentName(c, PlaybackService::class.java)

    fun resetToCarScreen(c: Context) {
        val component = serviceComponent(c)
        val pm = c.packageManager
        if (pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) return
        AppLog.add("CAR", "Android Auto: car screen (media player removed)")
        runCatching {
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        }
    }
}
