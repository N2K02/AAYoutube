package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EqualizerDiagnosticPolicyTest {
    @Test fun reportsOnlyFixedNumericValuesAndEnumSettings() {
        val curve = SoundEqSettings(
            enabled = true,
            preset = "Rock",
            bands = listOf(4f, 2f, -1f, 3f, 4f),
            bass = 0.52f,
            mode = SoundSpaceMode.WIDE,
            intensity = 0.7f,
        )
        val log = EqualizerDiagnosticPolicy.settingSummary(curve)
        assertEquals(
            "enabled=true preset=Rock bandsDb=[4,2,-1,3,4] " +
                "bassPct=52 spatial=WIDE strengthPct=70", log,
        )
        assertFalse(log.contains("sessionId"))
        assertFalse(log.contains("http"))
        assertFalse(log.contains("pid"))
    }

    @Test fun invalidValuesAreClampedBeforeLogging() {
        val log = EqualizerDiagnosticPolicy.settingSummary(
            SoundEqSettings(
                bands = listOf(9000f, Float.NaN, -50f),
                bass = Float.POSITIVE_INFINITY,
                intensity = Float.NaN,
            ),
        )
        assertTrue(log.contains("bandsDb=[12,0,-12,0,0]"))
        assertTrue(log.contains("bassPct=0"))
        assertTrue(log.contains("strengthPct=60"))
    }

    @Test fun engineAndDiscoveryLogsOmitSessionIdentifiers() {
        assertEquals(
            "bound=true scope=own-session eq=true bass=false spatial=false hardwareBands=5",
            EqualizerDiagnosticPolicy.outcome(
                attached = true, scoped = true, equalizer = true,
                bass = false, spatial = false, hardwareBands = 5,
            ),
        )
        assertEquals(
            "permission=granted dump=readable uniqueOwnSession=true",
            EqualizerDiagnosticPolicy.discovery(true, true, true),
        )
        assertEquals(
            "permission=missing dump=unavailable uniqueOwnSession=false",
            EqualizerDiagnosticPolicy.discovery(false, false, false),
        )
    }

    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun UIIsConciseAndDebugTextIsCollapsedByDefault() {
        val ui = src("app/src/main/java/com/aayoutube/app/EqualizerSheet.kt")
        assertTrue(ui.contains("var showDiagnostics by remember { mutableStateOf(false) }"))
        assertTrue(ui.contains("if (showDiagnostics)"))
        assertTrue(ui.contains("Hide details"))
        assertTrue(ui.contains("Details"))
        assertTrue(ui.contains("SoundEqPolicy.bandHz"))
        assertTrue(ui.contains("SoundEqPolicy.presets"))
        assertTrue(ui.contains("SoundSpaceMode.entries.filter { mode -> mode.systemApproximation }"))
        assertFalse(ui.contains("Experimental output-mix EQ for YouTube when Android permits it"))
        assertFalse(ui.contains("Concert hall, Cinema and 8D orbit require"))
        assertFalse(ui.contains("Strong positive EQ levels can clip"))
        assertTrue(ui.contains("AudioSessionDiscovery.status.collectAsState()"))
    }

    @Test fun logsAreDebouncedAndEngineFailuresAreObservable() {
        val app = src("app/src/main/java/com/aayoutube/app/AAYSoundFx.kt")
        val engine = src("app/src/main/java/com/aayoutube/app/GlobalAudioEffects.kt")
        val scanner = src("app/src/main/java/com/aayoutube/app/AudioSessionDiscovery.kt")
        val manifest = src("app/src/main/AndroidManifest.xml")
        assertTrue(app.contains("postDelayed(reportSettings, 300L)"))
        assertTrue(app.contains("diagnosticsHandler.removeCallbacks(reportSettings)"))
        assertTrue(app.contains("AppLog.add(\"EQ\", \"chromium playback=$playing\")"))
        assertTrue(engine.contains("apply equalizer failed="))
        assertTrue(engine.contains("apply bass failed="))
        assertTrue(engine.contains("apply virtualizer failed="))
        assertTrue(engine.contains("native effects created scope="))
        assertTrue(engine.contains("EqualizerDiagnosticPolicy.outcome("))
        assertTrue(scanner.contains("session scan #$scanCount"))
        assertTrue(scanner.contains("ownPlayerRows=$resultRows"))
        assertTrue(scanner.contains("durationMs=$elapsed"))
        assertTrue(scanner.contains("truncated=$wasTruncated"))
        assertTrue(manifest.contains("android:process=\":web\""))
        assertTrue(manifest.contains("android:name=\".PlaybackService\""))
    }
}
