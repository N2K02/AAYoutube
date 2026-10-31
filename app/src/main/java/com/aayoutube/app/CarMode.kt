package com.aayoutube.app

import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager

/** Restore the sole projected video entry and private media connection. */
object CarMode {
    fun serviceComponent(c: Context): ComponentName = ComponentName(c, PlaybackService::class.java)
    fun resetToCarScreen(c: Context) {
        val pm = c.packageManager
        listOf(ComponentName(c, LegacyProjectionService::class.java), serviceComponent(c)).forEach { component ->
            runCatching {
                if (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                    pm.setComponentEnabledSetting(component,
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
                    AppLog.add("CAR", "Restored " + component.className.substringAfterLast('.'))
                }
            }.onFailure { AppLog.add("CAR", "Component restore failed: " + it.javaClass.simpleName) }
        }
        // Phone-side self-check: a missing [CAR] launch record might mean
        // Android Auto never dispatched the exported projection service.
        // Verify the package manager can actually discover our service so the
        // next device logs distinguish manifest discovery from car host routing.
        val query = Intent(Intent.ACTION_MAIN)
            .setPackage(c.packageName)
            .addCategory("com.google.android.gms.car.category.CATEGORY_PROJECTION")
        val matches = runCatching {
            pm.queryIntentServices(query, PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrDefault(emptyList())
        val videoEntry = LegacyProjectionService::class.java.name
        val discovered = matches.any { it.serviceInfo?.name == videoEntry }
        val component = ComponentName(c, LegacyProjectionService::class.java)
        val state = pm.getComponentEnabledSetting(component)
        AppLog.add("CAR", "Video projection manifest discoverable=$discovered; enabledState=$state")
    }
}
