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
        assertTrue(activity.contains("if (WebHost.requestLink === relink) {"))
        assertTrue(activity.contains("MainActivity.restorePhoneLink()"))
        assertFalse(activity.contains("runOnUiThread"))
        assertTrue(activity.contains("WindowInsetsControllerCompat"))
        assertTrue(activity.contains("bindVideoToCarFrame(frame)"))
        assertTrue(activity.contains("fallback.createDisplayContext(display)"))
        assertTrue(activity.contains("setViewTreeLifecycleOwner(this)"))
        assertTrue(activity.contains("updateCarWindow()"))
        assertTrue(activity.contains("findCarInputManager()"))
        assertTrue(activity.contains("class LegacyCarInput(private val manager: InputManager) : CarEditable"))
        assertTrue(activity.contains("manager.startInput(this)"))
        assertTrue(activity.contains("manager.stopInput()"))
        assertTrue(activity.contains("if (event.actionMasked == MotionEvent.ACTION_UP)"))
        assertTrue(activity.contains("web.evaluateJavascript(EDITABLE_FOCUS_SCRIPT)"))
        assertTrue(activity.contains("if (result == \"true\") start()"))
        assertTrue(activity.contains("Car input bound; keyboard idle until text field tapped"))
        assertTrue(activity.contains("Video projection onStop; frameAttached="))
        assertFalse(activity.contains("web.onFocusChangeListener ="))
        assertFalse(activity.contains("if (web.hasFocus()) web.post"))
        assertTrue(activity.contains("web.onCreateInputConnection(editorInfo)"))
        assertTrue(activity.contains("configureLegacyCarInput()"))
        assertTrue(activity.contains("method.returnType.name.endsWith(\".input.InputManager\")"))
        assertTrue(activity.contains("getInputManager"))
        assertTrue(activity.contains("setIgnoreConfigChanges"))
        assertTrue(activity.contains("WebHost.requestReattachment()"))
    }

    @Test fun projectionUsesExistingPlayerMediaAndSafeShutdown() {
        val activity = source("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        assertTrue(activity.contains("WebHost.attachToCarWindow("))
        assertTrue(activity.contains("WebHost.detach("))
        // One owner token per car activity instance: Android Auto recreates the
        // activity while the old instance is alive (31_10-14-37-54 log).
        assertTrue(activity.contains("private val carToken = \"legacy-projection@\" + Integer.toHexString(System.identityHashCode(this))"))
        assertTrue(activity.contains("WebHost.showOnCar(true, carToken)"))
        assertFalse(activity.contains("WebHost.showOnCar(true, \"legacy-projection\")"))
        assertTrue(activity.contains("WebHost.setHold(false)"))
        assertTrue(activity.indexOf("WebHost.claimCarHost(carToken, onBecameActive)") <
            activity.indexOf("setContent { AAYoutubeTheme { LegacyProjectionScreen() } }"))
        assertTrue(activity.indexOf("WebHost.showOnCar(true, carToken)") <
            activity.indexOf("setContent { AAYoutubeTheme { LegacyProjectionScreen() } }"))
        assertTrue(activity.contains("WebHost.showOnCar(false, carToken)"))
        assertFalse(activity.contains("WebHost.showOnCar(false, \"legacy-projection\")"))
        assertTrue(activity.contains("WebHost.releaseCarHost(carToken)"))
        assertTrue(activity.contains("WebHost.resumeCarHost(carToken, onBecameActive)"))
        assertTrue(activity.contains("if (!registered || !WebHost.isActiveCarHost(carToken)) return"))
        assertTrue(activity.contains("WebHost.requestLink = relink\n        connectMedia()"))
        assertTrue(activity.contains("if (!carStarted || !WebHost.isActiveCarHost(carToken)) return@Runnable"))
        assertTrue(activity.contains("if (!WebHost.isActiveCarHost(carToken)) return"))
        assertTrue(activity.contains("WebHost.requestLink = relink"))
        assertTrue(activity.contains("WebHost.requestReattachment()"))
        assertTrue(activity.contains("mediaController?.disconnect()"))
        assertTrue(activity.contains("CompatMediaConnection(this)"))
        assertTrue(activity.contains("it.transportControls.sendCustomAction(Commands.HELLO, Bundle.EMPTY)"))
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("private val carOwners = mutableSetOf<String>()"))
        assertTrue(web.contains("carShown = carOwners.isNotEmpty()"))
        val main = source("app/src/main/java/com/aayoutube/app/MainActivity.kt")
        assertTrue(main.contains("!WebHost.carShown) WebHost.setHold(true)"))
    }

    @Test fun legacyProjectionSdkAndRuntimeHaveAndroidXCompatibility() {
        // Real crash: CarActivityHostImpl -> com.google.android.gms.car.e
        // NoClassDefFoundError android.support.v4.app.FragmentManager.
        val properties = source("gradle.properties")
        assertTrue("Legacy aauto.aar must be jetified",
            properties.contains("android.enableJetifier=true"))
        assertTrue(properties.contains("android.useAndroidX=true"))
        val gradle = source("app/build.gradle.kts")
        assertTrue("AppCompat supplies AndroidX FragmentManager at runtime",
            gradle.contains("androidx.appcompat:appcompat:"))
        assertTrue("Do not strip dynamically used legacy projection classes",
            gradle.contains("isMinifyEnabled = false"))
        assertTrue(gradle.contains("isShrinkResources = false"))
        assertTrue(gradle.contains("implementation(aabrowserProjectionSdk)"))
        assertTrue("Legacy projection remains separate from native music",
            source("app/src/main/AndroidManifest.xml")
                .contains("android:name=\".LegacyProjectionService\""))
    }

    @Test fun noUnrequestedAABrowserFeaturesWereAdded() {
        val activity = source("app/src/main/java/com/aayoutube/app/LegacyProjectionActivity.kt")
        assertFalse(activity.contains("SpotifyService("))
        assertFalse(activity.contains("WeatherService("))
        assertFalse(activity.contains("LocalPlayer("))
        assertFalse(activity.contains("TabManager("))
    }
}
