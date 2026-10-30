package com.aayoutube.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Restore stale overrides for all AABrowser-style discovery components. */
object CarMode {
    fun serviceComponent(c: Context): ComponentName = ComponentName(c, PlaybackService::class.java)
    fun resetToCarScreen(c: Context) {
        val pm = c.packageManager
        listOf(
            ComponentName(c, LegacyProjectionService::class.java),
            ComponentName(c, CarVideoService::class.java),
            serviceComponent(c),
        ).forEach { component ->
            runCatching {
                if (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                    pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                        PackageManager.DONT_KILL_APP)
                    AppLog.add("CAR", "Restored car component " + component.className.substringAfterLast('.'))
                }
            }.onFailure {
                AppLog.add("CAR", "Car component restore failed: " + it.javaClass.simpleName)
            }
        }
    }
}
