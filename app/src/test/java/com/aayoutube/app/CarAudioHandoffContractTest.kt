package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CarAudioHandoffContractTest {
    private fun read(name: String): String {
        val path = "app/src/main/java/com/aayoutube/app/$name"
        return listOf(File(path), File("../$path")).first { it.isFile }.readText()
    }

    @Test fun videoExitDoesNotAutomaticallyRecreateTheAudioDocument() {
        val web = read("WebHost.kt")
        val handoff = web.substringAfter("fun showOnCar(").substringBefore("fun requestReattachment()")
        assertTrue(handoff.contains("carAudioRetained.value = true"))
        assertFalse(handoff.contains("recreateRenderer("))
        assertFalse(handoff.contains("loadUrl("))
        assertTrue(web.contains("if (!carShown && !carAudioRetained.value) webVersion.update"))
        assertTrue(web.contains("modeMeter.onState("))
        assertTrue(web.contains("rendererRecreated="))
        assertTrue(read("Screens.kt").contains("if ((projectedToCar || audioRetained) && !onCar)"))
    }

    @Test fun previewBudgetReducesWorkInMiniAndOnFailure() {
        assertTrue(CarPhonePreview.intervalMs(false, 0) >= 500L)
        assertTrue(CarPhonePreview.intervalMs(true, 0) > CarPhonePreview.intervalMs(false, 0))
        assertTrue(CarPhonePreview.intervalMs(false, 3) > CarPhonePreview.intervalMs(true, 0))
        assertFalse(CarPhonePreview.allowed(false, true, true, true))
    }
}
