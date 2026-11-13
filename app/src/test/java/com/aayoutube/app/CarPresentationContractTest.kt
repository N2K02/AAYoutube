package com.aayoutube.app
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class CarPresentationContractTest {
    private fun source(path: String): String = listOf(File(path), File("../$path")).first { it.isFile }.readText()
    @Test fun projectionUsesExistingPlayerMediaAndSafeShutdown() {
        val activity = source("app/src/main/java/com/aayoutube/app/CarVideoPresentation.kt")
        assertTrue(activity.contains("WebHost.attachToCarWindow("))
        assertTrue(activity.contains("WebHost.detach("))
        // One owner token per car activity instance: Android Auto recreates the
        // activity while the old instance is alive (31_10-14-37-54 log).
        assertTrue(activity.contains("private val carToken = \"car-surface@\" + Integer.toHexString(System.identityHashCode(this))"))
        assertTrue(activity.contains("WebHost.showOnCar(true, carToken)"))
        assertFalse(activity.contains("WebHost.showOnCar(true, \"legacy-projection\")"))
        assertTrue(activity.contains("WebHost.setHold(false)"))
        assertTrue(activity.indexOf("WebHost.claimCarHost(carToken, onBecameActive)") <
            activity.indexOf("setContent { AAYoutubeTheme { CarVideoScreen() } }"))
        assertTrue(activity.indexOf("WebHost.showOnCar(true, carToken)") <
            activity.indexOf("setContent { AAYoutubeTheme { CarVideoScreen() } }"))
        assertTrue(activity.contains("WebHost.showOnCar(false, carToken)"))
        assertFalse(activity.contains("WebHost.showOnCar(false, \"legacy-projection\")"))
        assertTrue(activity.contains("WebHost.releaseCarHost(carToken)"))
        assertTrue(activity.contains("WebHost.resumeCarHost(carToken, onBecameActive)"))
        assertTrue(activity.contains("if (!registered || !WebHost.isActiveCarHost(carToken)) return"))
        assertTrue(activity.contains("WebHost.requestLink = relink\n        connectMedia()"))
        assertTrue(activity.contains("if (!carStarted || !WebHost.isActiveCarHost(carToken)) return@Runnable"))
        assertTrue(activity.contains("if (!registered || !WebHost.isActiveCarHost(carToken)) return"))
        assertTrue(activity.contains("WebHost.requestLink = relink"))
        assertTrue(activity.contains("WebHost.requestReattachment()"))
        assertTrue(activity.contains("mediaController?.disconnect()"))
        assertTrue(activity.contains("CompatMediaConnection(context)"))
        assertTrue(activity.contains("it.transportControls.sendCustomAction(Commands.HELLO, Bundle.EMPTY)"))
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(web.contains("private val carOwners = mutableSetOf<String>()"))
        assertTrue(web.contains("carShown = carOwners.isNotEmpty()"))
        val main = source("app/src/main/java/com/aayoutube/app/MainActivity.kt")
        assertTrue(main.contains("!WebHost.carShown) WebHost.setHold(true)"))
    }

    @Test fun presentationUsesNativeWindowAndReleasesLifecycle() {
        val code = source("app/src/main/java/com/aayoutube/app/CarVideoPresentation.kt")
        assertTrue(code.contains("Presentation(context, display, R.style.Theme_AAYoutube)"))
        assertTrue(code.contains("savedController.performAttach()"))
        assertTrue(code.contains("SavedStateRegistryController.create(this)"))
        assertTrue(code.contains("fun release()"))
        assertTrue(code.contains("FLAG_HARDWARE_ACCELERATED"))
        assertTrue(code.contains("window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)"))
        assertTrue(code.contains("Lifecycle.Event.ON_DESTROY"))
        assertTrue(code.contains("store.clear()"))
        assertFalse(code.contains("javaClass.getMethod"))
        assertFalse(code.contains("com.google.android.gms.car.input"))
    }
}
