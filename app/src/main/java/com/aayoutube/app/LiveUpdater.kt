package com.aayoutube.app

import android.app.Activity
import android.content.Context
import android.os.Build
import dev.n2k02.updater.UpdateConfig
import dev.n2k02.updater.UpdateInstallGuard
import dev.n2k02.updater.UpdaterController
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Experimental shared updater integration. */
internal object LiveUpdater {
    private lateinit var app: Context
    private var instance: UpdaterController? by mutableStateOf(null)
    val experimentalEnabled = MutableStateFlow(false)
    val installGuard = UpdateInstallGuard { true }

    fun init(context: Context) {
        if (instance != null) return
        instance = UpdaterInitialization.ensure(
            existing = instance,
            create = {
                app = context.applicationContext
                experimentalEnabled.value = prefs().getBoolean("experimentalEnabled", false)
                create()
            },
            onFailure = ::logInitializationFailure,
        )
    }

    private fun prefs() = app.getSharedPreferences("app_updater", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    private fun versionCode(): Long {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun create(enabled: Boolean = experimentalEnabled.value) = UpdaterController(
        context = app,
        config = UpdateConfig(
            repository = "N2K02/AAYoutube",
            packageName = app.packageName,
            installedVersionCode = versionCode(),
            experimentalEnabled = enabled,
        ),
        offerPreferences = prefs(),
    )

    private fun logInitializationFailure(error: Exception) {
        AppLog.add("UPDATER", "Controller initialization failed: " + error.javaClass.simpleName)
    }

    // Updater availability must never be a prerequisite for playback or UI rendering.
    val controllerOrNull: UpdaterController? get() = instance

    fun setExperimentalEnabled(enabled: Boolean) {
        if (enabled == experimentalEnabled.value) return
        val previous = instance ?: return
        val next = UpdaterInitialization.ensure<UpdaterController>(
            existing = null,
            create = { create(enabled) },
            onFailure = ::logInitializationFailure,
        ) ?: return
        previous.cancel()
        prefs().edit().putBoolean("experimentalEnabled", enabled).apply()
        experimentalEnabled.value = enabled
        instance = next
        next.check(manual = true)
    }

    fun onResume(activity: Activity) {
        instance?.onHostResume(activity, installGuard)
    }
}
