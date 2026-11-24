package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class WebAudioEqPolicyTest {
    private val on = SoundEqSettings(enabled = true)

    @Test fun disabledIsNeutralPassThrough() {
        val p = WebAudioEqPolicy.params(SoundEqSettings(enabled = false,
            bands = listOf(6f, 6f, 6f, 6f, 6f), bass = 1f, mode = SoundSpaceMode.HALL))
        assertFalse(p.enabled)
        assertTrue(p.bands.all { it == 0f })
        assertEquals(0.0, p.bassDb, 0.0)
        assertEquals(1f, p.preamp, 0f)
        assertEquals(1f, p.sideGain, 0f)
        assertEquals(0f, p.crossGain, 0f)
        assertEquals(0f, p.reverbWet, 0f)
        assertEquals(0f, p.orbitDepth, 0f)
        assertFalse(p.limiter)
    }

    @Test fun flatEnabledBypassesLimiterLikeMRadio() {
        val p = WebAudioEqPolicy.params(on)
        assertTrue(p.enabled)
        assertFalse(p.limiter)
        assertEquals(1f, p.preamp, 0f)
    }

    @Test fun preampIncludesTheCombinedResponseAndBassShelf() {
        val p = WebAudioEqPolicy.params(on.copy(bands = listOf(6f, 4f, 0f, -3f, 0f)))
        assertTrue(p.preamp < 10f.pow(-6f / 20f))
        assertTrue(p.limiter)
        assertEquals(WebAudioEqPolicy.Q, p.q, 0.0)
        val bass = WebAudioEqPolicy.params(on.copy(bass = 1f))
        assertEquals(9.0, bass.bassDb, 1e-6)
        assertEquals(120.0, bass.bassHz, 0.0)
        assertTrue(bass.preamp < 10f.pow(-9f / 20f))
    }

    @Test fun tinyBandsAreIgnoredAndCutsNeverRaisePreamp() {
        val p = WebAudioEqPolicy.params(on.copy(bands = listOf(0.05f, -12f, 0f, 0f, 0f)))
        assertEquals(0f, p.bands[0], 0f)
        assertEquals(-12f, p.bands[1], 0f)
        assertEquals(1f, p.preamp, 0f)
    }

    @Test fun spaceModesMatchMRadioSoundEngine() {
        val i = 0.5f
        val wide = WebAudioEqPolicy.params(on.copy(mode = SoundSpaceMode.WIDE, intensity = i))
        assertEquals(1.6f, wide.sideGain, 1e-5f)
        assertEquals(0f, wide.reverbWet, 0f)
        assertTrue(wide.limiter)

        val hp = WebAudioEqPolicy.params(on.copy(mode = SoundSpaceMode.HEADPHONE_3D, intensity = i))
        assertEquals(1.25f, hp.sideGain, 1e-5f)
        assertEquals(0.225f, hp.crossGain, 1e-5f)
        assertEquals(1f / (1f + 0.225f), hp.crossNorm, 1e-5f)
        assertEquals(0.00027f, hp.crossDelaySec, 1e-7f)
        assertEquals(0.02f, hp.reverbWet, 1e-5f)
        assertEquals(0.65f, hp.reverbFeedback, 0f)

        val hall = WebAudioEqPolicy.params(on.copy(mode = SoundSpaceMode.HALL, intensity = i))
        assertEquals(1f, hall.sideGain, 1e-5f)
        assertEquals(0.075f, hall.reverbWet, 1e-5f)
        assertEquals(0.70f, hall.reverbFeedback, 0f)
        assertEquals(0.018f, hall.reverbPreSec, 0f)

        val cinema = WebAudioEqPolicy.params(on.copy(mode = SoundSpaceMode.CINEMA, intensity = i))
        assertEquals(1.4f, cinema.sideGain, 1e-5f)
        assertEquals(0.04f, cinema.reverbWet, 1e-5f)

        val orbit = WebAudioEqPolicy.params(on.copy(mode = SoundSpaceMode.ORBIT_8D, intensity = i))
        assertEquals(0.5f, orbit.orbitDepth, 0f)
        assertEquals(9.0, orbit.orbitPeriodSec, 0.0)
        assertEquals(1f - 0.25f, orbit.orbitKeep, 1e-6f)
        assertEquals(0f, orbit.crossGain, 0f)
    }

    @Test fun jsonIsLocaleSafeAndComplete() {
        val prior = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY) // decimal comma locale
        try {
            val json = WebAudioEqPolicy.params(on.copy(bands = listOf(1.5f, 0f, 0f, 0f, -2.25f),
                mode = SoundSpaceMode.HEADPHONE_3D)).toJson()
            assertTrue(json.startsWith("{\"enabled\":true,\"bandHz\":[60,230,910,3600,14000]"))
            assertTrue(json.contains("\"bands\":[1.5,0,0,0,-2.25]"))
            assertFalse(json.contains(",5"))
            listOf("q", "bassDb", "bassHz", "preamp", "sideGain", "crossGain", "crossNorm",
                "crossDelay", "crossLp", "wet", "feedback", "damp", "pre", "orbit",
                "orbitPeriod", "orbitKeep", "limiter").forEach {
                assertTrue(it, json.contains("\"$it\":"))
            }
            assertFalse(json.contains("NaN"))
            assertFalse(json.contains("-0,"))
        } finally {
            java.util.Locale.setDefault(prior)
        }
    }

    @Test fun nativeFallbackOnlyWhenInPlayerDspCannotAttach() {
        assertEquals(WebAudioEqState.ACTIVE, WebAudioEqPolicy.status("active"))
        assertEquals(WebAudioEqState.WAITING, WebAudioEqPolicy.status("waiting"))
        assertEquals(WebAudioEqState.OFF, WebAudioEqPolicy.status("off"))
        assertEquals(WebAudioEqState.UNSUPPORTED, WebAudioEqPolicy.status("unsupported"))
        assertEquals(WebAudioEqState.UNSUPPORTED, WebAudioEqPolicy.status("garbage"))
        assertEquals(WebAudioEqState.UNSUPPORTED, WebAudioEqPolicy.status(null))
        assertTrue(WebAudioEqPolicy.nativeFallbackAllowed(WebAudioEqState.UNSUPPORTED))
        assertFalse(WebAudioEqPolicy.nativeFallbackAllowed(WebAudioEqState.ACTIVE))
        assertFalse(WebAudioEqPolicy.nativeFallbackAllowed(WebAudioEqState.WAITING))
        assertFalse(WebAudioEqPolicy.nativeFallbackAllowed(WebAudioEqState.OFF))
    }

    @Test fun activeStatusCarriesMeasuredOutputDelay() {
        assertEquals(WebAudioEqState.ACTIVE, WebAudioEqPolicy.status("active:48"))
        assertEquals(48, WebAudioEqPolicy.latencyMs("active:48"))
        assertEquals(null, WebAudioEqPolicy.latencyMs("active"))
        assertEquals(null, WebAudioEqPolicy.latencyMs("active:0"))
        assertEquals(null, WebAudioEqPolicy.latencyMs("active:99999"))
        assertEquals(null, WebAudioEqPolicy.latencyMs("waiting:48"))
        assertEquals(null, WebAudioEqPolicy.latencyMs(null))
    }

    @Test fun switchingOffWhileRoutedReloadsOnlyThen() {
        val on = SoundEqSettings(enabled = true)
        val off = on.copy(enabled = false)
        assertTrue(WebAudioEqPolicy.releaseNeedsReload(on, off, WebAudioEqState.ACTIVE))
        assertFalse(WebAudioEqPolicy.releaseNeedsReload(on, off, WebAudioEqState.WAITING))
        assertFalse(WebAudioEqPolicy.releaseNeedsReload(on, off, WebAudioEqState.UNSUPPORTED))
        assertFalse(WebAudioEqPolicy.releaseNeedsReload(off, on, WebAudioEqState.ACTIVE))
        assertFalse(WebAudioEqPolicy.releaseNeedsReload(on, on.copy(bass = 0.5f), WebAudioEqState.ACTIVE))
    }


    @Test fun everySpatialModeAtZeroIsNeutral() {
        for (mode in SoundSpaceMode.entries) {
            val p = WebAudioEqPolicy.params(on.copy(mode = mode, intensity = 0f))
            assertEquals(mode.name, 1f, p.preamp, 0f)
            assertEquals(1f, p.sideGain, 0f)
            assertEquals(0f, p.crossGain, 0f)
            assertEquals(0f, p.reverbWet, 0f)
            assertEquals(0f, p.orbitDepth, 0f)
            assertFalse(p.limiter)
        }
    }

    @Test fun webAudioFixturesAreGeneratedFromTheLivePolicy() {
        val fixtures = src(".github/scripts/eq-fixtures.jsonl")
        for ((name, bands) in SoundEqPolicy.presets) {
            for (bass in listOf(0f, 1f)) {
                val s = on.copy(bands = bands, bass = bass)
                assertTrue(name, fixtures.contains(WebAudioEqPolicy.params(s).toJson()))
            }
        }
        for (mode in SoundSpaceMode.entries) {
            for (intensity in listOf(0f, 0.6f, 1f)) {
                assertTrue(mode.name, fixtures.contains(WebAudioEqPolicy.params(on.copy(mode = mode, intensity = intensity)).toJson()))
            }
        }
    }

    @Test fun nativeFallbackNeverStacksUnprotectedBoosts() {
        for ((name, bands) in SoundEqPolicy.presets) {
            val s = on.copy(bands = bands, bass = 1f)
            for (hz in SoundEqPolicy.bandHz) assertTrue(name, SoundEqPolicy.fallbackGainAtHz(hz, s) <= 0f)
        }
    }

    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun pageGraphIsSafeAndUsesOnlyBuiltInNodes() {
        val js = src("app/src/main/assets/aay_eq.js")
        // Same chain as mRadio, built from native nodes (CSP-safe, off the main thread).
        listOf("'peaking'", "'lowshelf'", "createStereoPanner", "c.fb.connect(c.d)",
            "createWaveShaper", "createDelay", "createChannelSplitter").forEach {
            assertTrue(it, js.contains(it))
        }
        assertFalse(js.contains("audioWorklet"))
        assertFalse(js.contains("createScriptProcessor"))
        assertFalse(js.contains("MutationObserver"))
        assertFalse(js.contains("getUserMedia"))
        // Never capture media while disabled, non-MSE (CORS-tainted) or suspended.
        assertTrue(js.contains("if (!want())"))
        assertTrue(js.contains("src.indexOf('blob:') !== 0"))
        assertTrue(js.contains("ctx.state !== 'running'"))
        assertTrue(js.contains("EQ.hooked.has(v)"))
        assertTrue(js.contains("function keepRunning()"))
        assertTrue(js.contains("window.__AAY_BROWSE"))
        // Realtime-thread budget: only active stages are wired, the reverb tail is
        // capped, kernels rebuild only on change, and hidden pages never stay faded.
        assertTrue(js.contains("function planOf(c)"))
        assertTrue(js.contains("if (!on) return { key: 'pass' }"))
        assertTrue(js.contains("if (p.wet) {"))
        // Cheap comb reverb, never a convolution (the heaviest node).
        assertFalse(js.contains("createConvolver"))
        assertTrue(js.contains("var COMBS = ["))
        assertTrue(js.contains("document.hidden"))
        assertTrue(js.contains("EQ.ctx.suspend()"))
        assertTrue(js.contains("if (EQ.idle && anyPlaying()) EQ.idle = false;"))
        assertTrue(js.contains("__aayEqProbe"))
        assertTrue(js.contains("last[0] === v"))
        // Mono YouTube streams must reach both ears (the splitter does not upmix).
        assertTrue(js.contains("g.input.channelCountMode = 'explicit'"))
        assertTrue(js.contains("N.eqStatus(msg, window.__AAY_BRIDGE_TOKEN || '')"))
    }

    @Test fun hostInjectsGraphPushesSettingsAndGatesNativeEffects() {
        val host = src("app/src/main/java/com/aayoutube/app/WebHost.kt")
        val fx = src("app/src/main/java/com/aayoutube/app/AAYSoundFx.kt")
        val native = src("app/src/main/java/com/aayoutube/app/GlobalAudioEffects.kt")
        assertTrue(host.contains("app.assets.open(\"aay_eq.js\")"))
        // Settings come only from live pushes, so a reload can never re-route with stale state.
        assertFalse(host.contains("window.__AAY_EQ="))
        assertFalse(src("app/src/main/assets/aay_eq.js").contains("window.__AAY_EQ"))
        assertTrue(host.contains("fun eqStatus(status: String, token: String?)"))
        assertTrue(host.contains("window.__aay.eq(\${AAYSoundFx.webAudioJson()})"))
        assertTrue(fx.contains("WebHost.applyEq(WebAudioEqPolicy.params(safe).toJson())"))
        assertTrue(fx.contains("WebAudioEqPolicy.releaseNeedsReload(before, safe, _webAudio.value)"))
        assertTrue(fx.contains("WebHost.reload()"))
        assertTrue(fx.contains("GlobalAudioEffects.nativeFallback(WebAudioEqPolicy.nativeFallbackAllowed(state))"))
        assertTrue(native.contains("if (!nativeAllowed) {"))
        assertTrue(native.contains("private var nativeAllowed = false"))
    }
}
