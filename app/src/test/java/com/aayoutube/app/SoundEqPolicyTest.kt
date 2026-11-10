package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class SoundEqPolicyTest {
    @Test fun copiedFromMRadioBandCentersAndPresets() {
        assertArrayEquals(intArrayOf(60, 230, 910, 3600, 14000),
            SoundEqPolicy.bandHz)
        assertEquals(10, SoundEqPolicy.presets.size)
        assertEquals(listOf(6f, 4f, 0f, 0f, 0f),
            SoundEqPolicy.presets.first { it.first == "Bass boost" }.second)
        assertEquals(listOf(4f, 2f, -1f, 3f, 4f),
            SoundEqPolicy.presets.first { it.first == "Rock" }.second)
    }

    @Test fun optInOffByDefaultAndResetKeepsSwitch() {
        val initial = SoundEqSettings()
        assertFalse(initial.enabled)
        assertEquals(SoundSpaceMode.OFF, initial.mode)
        assertEquals("Flat", initial.preset)
        val dirty = SoundEqSettings(enabled = true, bass = 0.7f,
            mode = SoundSpaceMode.WIDE, bands = listOf(3f, 5f, 0f, -2f, 1f))
        val reset = SoundEqPolicy.reset(dirty)
        assertTrue(reset.enabled)
        assertTrue(reset.bands.all { it == 0f })
        assertEquals(0f, reset.bass, 0f)
        assertEquals(SoundSpaceMode.OFF, reset.mode)
    }

    @Test fun bandsClampAndInvalidChangesNeverEscapeFiveBandModel() {
        val invalid = SoundEqSettings(bands = listOf(100f, Float.NaN, -90f),
            bass = Float.POSITIVE_INFINITY, intensity = -3f,
            mode = SoundSpaceMode.ORBIT_8D)
        val cleaned = SoundEqPolicy.safe(invalid)
        assertEquals(listOf(12f, 0f, -12f, 0f, 0f), cleaned.bands)
        assertEquals(0f, cleaned.bass, 0f)
        assertEquals(0f, cleaned.intensity, 0f)
        // All mRadio modes are real now: the in-player DSP implements them.
        assertEquals(SoundSpaceMode.ORBIT_8D, cleaned.mode)
        val edited = SoundEqPolicy.setBand(cleaned, 3, 99f)
        assertEquals(12f, edited.bands[3], 0f)
        assertEquals("Custom", edited.preset)
        assertEquals(cleaned, SoundEqPolicy.setBand(cleaned, 15, 6f))
    }

    @Test fun interpolatesHardwareBandCentersRatherThanAssumingFivePhysicalBands() {
        val gain = listOf(-12f, -6f, 0f, 6f, 12f)
        assertEquals(-12f, SoundEqPolicy.gainAtHz(30, gain), 0.001f)
        assertEquals(-6f, SoundEqPolicy.gainAtHz(230, gain), 0.001f)
        assertEquals(0f, SoundEqPolicy.gainAtHz(910, gain), 0.001f)
        assertEquals(12f, SoundEqPolicy.gainAtHz(20000, gain), 0.001f)
        val middle = sqrt(230.0 * 910.0).toInt()
        assertEquals(-3f, SoundEqPolicy.gainAtHz(middle, gain), 0.06f)
        assertEquals(300.toShort(), SoundEqPolicy.millibels(12f, -300, 300))
        assertEquals((-500).toShort(), SoundEqPolicy.millibels(-12f, -500, 600))
        assertEquals(1000.toShort(), SoundEqPolicy.strength(3f))
        assertEquals(0.toShort(), SoundEqPolicy.strength(Float.NaN))
    }

    @Test fun nativeFallbackOnlyApproximatesVirtualizerModes() {
        assertTrue(SoundSpaceMode.WIDE.systemApproximation)
        assertTrue(SoundSpaceMode.HEADPHONE_3D.systemApproximation)
        assertFalse(SoundSpaceMode.HALL.systemApproximation)
        assertFalse(SoundSpaceMode.CINEMA.systemApproximation)
        assertFalse(SoundSpaceMode.ORBIT_8D.systemApproximation)
    }

    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun webProcessAudioHookAndSafeNoDuplicatePlayerContracts() {
        val app = source("app/src/main/java/com/aayoutube/app/AAYoutubeApp.kt")
        val host = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        val adapter = source("app/src/main/java/com/aayoutube/app/GlobalAudioEffects.kt")
        val ui = source("app/src/main/java/com/aayoutube/app/EqualizerSheet.kt")
        val menu = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(app.contains("AAYSoundFx.init(this)"))
        assertTrue(host.contains("AAYSoundFx.onPlaybackChanged(s.playing)"))
        assertTrue(host.contains("AAYSoundFx.shutdown()"))
        assertTrue(adapter.contains("Equalizer(0, selectedSession)"))
        assertTrue(adapter.contains("BassBoost(0, selectedSession)"))
        assertTrue(adapter.contains("Virtualizer(0, selectedSession)"))
        assertTrue(adapter.contains("if (selectedSession == null)"))
        assertTrue(adapter.contains("if (!settings.enabled)"))
        assertTrue(adapter.contains("if (!playing)"))
        assertTrue(adapter.contains("effect.hasControl()"))
        assertTrue(adapter.contains("releaseEffects()"))
        assertFalse(adapter.contains("MediaProjection"))
        assertFalse(adapter.contains("AudioRecord("))
        assertFalse(adapter.contains("ExoPlayer("))
        assertFalse(adapter.contains("MediaPlayer("))
        assertTrue(ui.contains("ModalBottomSheet("))
        assertTrue(ui.contains("SoundEqPolicy.presets"))
        assertTrue(ui.contains("SoundEqPolicy.bandHz"))
        assertTrue(ui.contains("mode.systemApproximation"))
        assertTrue(menu.contains("EqualizerSheet(onDismiss"))
        assertTrue(menu.contains("onEqualizer = { equalizerOpen = true }"))
        assertTrue(manifest.contains("android.permission.MODIFY_AUDIO_SETTINGS"))
    }
}
