package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source and lifecycle contract for the actual AABrowser classic SDK integration. */
class LegacyProjectionContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun usesActualPinnedAABrowserSdkAndRegisteredProjectionService() {
        val build = source("app/build.gradle.kts")
        assertTrue(build.contains("implementation(aabrowserProjectionSdk)"))
        assertTrue(build.contains("678f4afb0434ceb97b23c2237ce4489bc52b4394"))
        val manifest = source("app/src/main/AndroidManifest.xml")
        val service = manifest.substringAfter("android:name=\".LegacyProjectionService\"").substringBefore("</service>")
        assertTrue(service.contains("android:process=\":web\""))
        assertTrue(service.contains("com.google.android.gms.car.category.CATEGORY_PROJECTION"))
        assertTrue(service.contains("com.google.android.gms.car.category.CATEGORY_PROJECTION_OEM"))
        assertFalse(manifest.contains("android:name=\".CarVideoService\""))
        assertTrue(manifest.contains("android:name=\".PlaybackService\""))
        assertFalse("CarActivity is created by the projection SDK, not Android", manifest.contains("android:name=\".LegacyProjectionActivity\""))
    }

    @Test fun mirrorsUpstreamCarActivityServiceAndLifecycleOwners() {
        val service = source("app/src/main/java/com/aayoutube/app/LegacyProjectionService.kt")
        assertTrue(service.contains("class LegacyProjectionService : CarActivityService()"))
        assertTrue(service.contains("override fun getCarActivity(): Class<out CarActivity?>"))
        val activity = source("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        assertTrue(activity.contains("class LegacyProjectionActivity : CarActivity(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner"))
        assertTrue(activity.contains("SavedStateRegistryController.create(this)"))
        assertTrue(activity.contains("setViewTreeLifecycleOwner("))
        assertTrue(activity.contains("setViewTreeViewModelStoreOwner("))
        assertTrue(activity.contains("setViewTreeSavedStateRegistryOwner("))
        assertTrue(activity.contains("lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)"))
        assertTrue(activity.contains("store.clear()"))
        assertTrue(activity.contains("handler.removeCallbacksAndMessages(null)"))
        assertTrue(activity.contains("if (carStarted) connectMedia()"))
        assertTrue(activity.contains("if (!carStarted || mediaController != null) return"))
        assertTrue(activity.contains("if (WebHost.requestLink === relink) WebHost.requestLink = null"))
        assertFalse(activity.contains("runOnUiThread"))
        assertTrue(activity.contains("WindowInsetsControllerCompat"))
        assertTrue(activity.contains("setViewTreeLifecycleOwner(this)"))
        assertTrue(activity.contains("updateCarWindow()"))
    }

    @Test fun projectionUsesExistingPlayerMediaAndSafeShutdown() {
        val activity = source("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        assertTrue(activity.contains("WebHost.attach("))
        assertTrue(activity.contains("WebHost.detach("))
        assertTrue(activity.contains("WebHost.showOnCar(true, \"legacy-projection\")"))
        assertTrue(activity.contains("WebHost.showOnCar(false, \"legacy-projection\")"))
        assertTrue(activity.contains("WebHost.requestReattachment()"))
        assertTrue(activity.contains("MediaController.releaseFuture(it)"))
        assertTrue(activity.contains("future.get().sendCustomCommand(Commands.HELLO, Bundle.EMPTY)"))
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("private val carOwners = mutableSetOf<String>()"))
        assertTrue(web.contains("carShown = carOwners.isNotEmpty()"))
        val main = source("app/src/main/java/com/aayoutube/app/MainActivity.kt")
        assertTrue(main.contains("!WebHost.carShown) WebHost.setHold(true)"))
    }

    @Test fun noUnrequestedAABrowserFeaturesWereAdded() {
        val activity = source("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        assertFalse(activity.contains("SpotifyService("))
        assertFalse(activity.contains("WeatherService("))
        assertFalse(activity.contains("LocalPlayer("))
        assertFalse(activity.contains("TabManager("))
    }
}
