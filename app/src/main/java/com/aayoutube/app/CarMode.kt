package com.aayoutube.app

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * What AAYoutube is in Android Auto: the full screen with the video (default), or the media player.
 *
 * Android Auto hides an app's screen (CAR_LAUNCHER) when the same app has a media player, so the
 * two can't be offered together. The media player is a second manifest entry of the media
 * service ([AutoPlaybackService]); switching enables one entry and disables the other.
 */
object CarMode {
    private fun phone(c: Context) = ComponentName(c, PlaybackService::class.java)
    private fun auto(c: Context) = ComponentName(c, AutoPlaybackService::class.java)

    /** True when Android Auto shows the media player instead of the screen. */
    fun mediaPlayer(c: Context): Boolean =
        c.packageManager.getComponentEnabledSetting(auto(c)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** The media service that is switched on. */
    fun serviceComponent(c: Context): ComponentName = if (mediaPlayer(c)) auto(c) else phone(c)

    fun set(c: Context, mediaPlayer: Boolean) {
        if (mediaPlayer == mediaPlayer(c)) return
        AppLog.add("CAR", if (mediaPlayer) "Android Auto: media player" else "Android Auto: car screen")
        val pm = c.packageManager
        val flags = PackageManager.DONT_KILL_APP
        if (mediaPlayer) {
            pm.setComponentEnabledSetting(auto(c), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, flags)
            pm.setComponentEnabledSetting(phone(c), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, flags)
        } else {
            pm.setComponentEnabledSetting(phone(c), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, flags)
            pm.setComponentEnabledSetting(auto(c), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, flags)
        }
        // The old service may still be running with its media session, and the new one would clash
        // with it. End the media process (not this one); the screen links up with the new service.
        runCatching {
            val am = c.getSystemService(ActivityManager::class.java)
            am.runningAppProcesses.orEmpty()
                .filter { it.processName == c.packageName }
                .forEach { android.os.Process.killProcess(it.pid) }
        }
    }
}
