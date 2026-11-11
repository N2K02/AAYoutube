package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Equalizer UI is concise, and the EQ writes no logs (user rule: "Dont add logs"). */
class EqualizerDiagnosticPolicyTest {
    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun UIShowsOnlyControlsWithoutStatusOrDetailsText() {
        val ui = src("app/src/main/java/com/aayoutube/app/EqualizerSheet.kt")
        assertFalse(ui.contains("showDiagnostics"))
        assertFalse(ui.contains("Hide details"))
        assertFalse(ui.contains("\"Details\""))
        assertFalse(ui.contains("In-player DSP"))
        assertFalse(ui.contains("shortStatus"))
        assertTrue(ui.contains("TextButton(onClick = { AAYSoundFx.reset() })"))
        assertTrue(ui.contains("SoundEqPolicy.bandHz"))
        assertTrue(ui.contains("SoundEqPolicy.presets"))
        assertTrue(ui.contains("SoundSpaceMode.entries.forEach"))
        assertTrue(ui.contains("enabled = !fallback || mode.systemApproximation"))
        assertFalse(ui.contains("Experimental output-mix EQ for YouTube when Android permits it"))
        assertFalse(ui.contains("Concert hall, Cinema and 8D orbit require"))
        assertFalse(ui.contains("Strong positive EQ levels can clip"))
        // No ADB grant: the in-player DSP needs no DUMP-based session tracking.
        assertFalse(ui.contains("adb shell"))
        assertFalse(ui.contains("AudioSessionDiscovery"))
    }

    @Test fun equalizerWritesNoLogs() {
        listOf("AAYSoundFx.kt", "GlobalAudioEffects.kt",
            "WebAudioEqPolicy.kt", "EqualizerSheet.kt").forEach {
            val code = src("app/src/main/java/com/aayoutube/app/$it")
            assertFalse(it, code.contains("AppLog.add(\"EQ\""))
            assertFalse(it, code.contains("Log.d(") || code.contains("Log.i("))
            assertFalse(it, code.contains("EqualizerDiagnosticPolicy"))
        }
        assertFalse(File("app/src/main/java/com/aayoutube/app/EqualizerDiagnosticPolicy.kt").isFile ||
            File("../app/src/main/java/com/aayoutube/app/EqualizerDiagnosticPolicy.kt").isFile)
        val js = src("app/src/main/assets/aay_eq.js")
        assertFalse(js.contains("console."))
        val manifest = src("app/src/main/AndroidManifest.xml")
        assertFalse(manifest.contains("android.permission.DUMP"))
        listOf("AudioSessionDiscovery.kt", "AudioSessionCandidatePolicy.kt").forEach {
            val path = "app/src/main/java/com/aayoutube/app/$it"
            assertFalse(it, File(path).isFile || File("../$path").isFile)
        }
        assertTrue(manifest.contains("android:process=\":web\""))
        assertTrue(manifest.contains("android:name=\".PlaybackService\""))
    }
}
