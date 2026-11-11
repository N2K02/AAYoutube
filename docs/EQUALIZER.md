# AAYouTube equalizer — mRadio DSP inside the YouTube player

## Primary engine: in-player Web Audio (no ADB, this app only)

`assets/aay_eq.js` runs mRadio's `SoundEngine` signal path as a native Web
Audio graph on YouTube's own `<video>` element inside the WebView:

    preamp -> 5x RBJ peaking (60/230/910/3,600/14,000 Hz, Q 0.9)
           -> 120 Hz low shelf (Bass boost, up to +9 dB)
           -> mid/side widening | 8D equal-power orbit (9 s lap)
           -> Headphone 3D cross-feed (0.27 ms, 700 Hz low-pass)
           -> dry + reverb send (Hall / Cinema / Headphone 3D / 8D)
           -> mRadio soft limiter (tanh above 0.8)

`WebAudioEqPolicy` computes every parameter exactly as mRadio's
`SoundEngine.apply()` does (unit tested), and the page applies them with
30 ms smoothing. All six mRadio 3D modes work. Only built-in nodes are used
(no AudioWorklet module or ScriptProcessor), so YouTube's CSP cannot block
it and DSP runs on Chromium's audio thread. Mono streams are upmixed to
stereo like mRadio. Reverb is four damped feedback combs (mRadio/Freeverb
style), about 5x cheaper than the earlier convolution reverb; 8D
uses level panning without mRadio's small inter-ear delay and rear muffling.

Safety rules in the page script:
- Nothing is routed while the switch is off (the default), nor while it
  is on with Flat bands, no bass and 3D Off: Chromium's native audio path
  is kept until a setting actually changes the sound (no CPU/battery cost).
- Only MediaSource (`blob:`) playback is routed; progressive cross-origin
  media would be silenced by CORS tainting, so it is reported unsupported.
- An element is routed only after the AudioContext is running; if Chromium
  later suspends the context it is resumed immediately on state change and
  on every `playing` event.
- Only the stages the current settings use are wired (Flat + Off is a
  straight pass-through); Chromium renders the graph on a realtime thread,
  and idle stages caused dropouts. Stage changes, including Hall <-> Cinema
  reverb-tail swaps, fade out/in over ~50 ms.
- When every routed video is paused or ended for 2 s the AudioContext is
  suspended, so the audio output closes; `play` resumes it.
- A routed element cannot be returned to Chromium's normal path, so
  switching the EQ **off** while active reloads the page; WebHost keeps the
  same-video position (from 10 s).
- The page uses only live settings pushed by the app, never a copy baked
  into the injected script, so a reload cannot re-route with stale state.
- A non-MediaSource clip enables the native fallback only if it is still
  playing after 4 s (previews and short ads do not).
- The page reports the measured added audio delay (`baseLatency +
  outputLatency`) with its status. Video is not delayed to match.
- The sheet shows only the controls (switch, Reset, 3D modes, presets,
  bands, bass): no status line or details text.
- `.github/scripts/equalizer.test.cjs` runs the graph on a real Web Audio
  implementation in CI (levels, mono upmix, limiter, idle suspend,
  fallback hysteresis, faded reverb swaps).

The page reports `off`, `waiting`, `active` or `unsupported` through
`AAYNative.eqStatus`. **Android native effects (below) run only when the
page reports `unsupported`**, so the curve is never applied twice.

---

# Native fallback (previous implementation)

## What is implemented

- **Settings → Equalizer** opens a mRadio-style Material 3 bottom sheet
  above the existing, unchanged video WebView.
- Five visible bands at **60, 230, 910, 3,600 and 14,000 Hz**
  (+/- 12 dB), mRadio's ten equalizer presets, Reset, Bass boost,
  and the mRadio-inspired 3D mode chips.
- All settings are saved locally and applied to the native effect adapter
  on YouTube playback-state transitions. The switch is **OFF by default**;
  Reset restores flat bands, zero bass and Off 3D, keeping the switch state.
- Android EQ implementations may expose a different number of bands. A
  pure Kotlin policy interpolates the five user curves in log frequency to
  the actual system equalizer center frequencies, respecting its level range.
- Native **BassBoost** is used when independently available. Android's
  **Virtualizer** approximates **Wide** / **Headphone 3D**, but it is NOT
  mRadio's spatial PCM algorithm. Concert hall, Cinema and 8D orbit require
  PCM/DSP processing and are explicitly disabled rather than simulated.

## Fundamental audio-path difference

mRadio is a Media3/ExoPlayer audio player and inserts `SoundProcessor`
directly into its decoded stereo PCM pipeline; it applies 5-band biquads,
spatial DSP and a limiter. AAYouTube uses a **single Chromium WebView
video/audio player**. Android WebView does not offer a stable supported
callback to inject a PCM processor, nor does it expose its actual audio
session ID to this app. AAYouTube's separate `PlaybackService` exposes
transport metadata and buttons; it is NOT an audio decoder.

For this experimental port only, the user may explicitly opt into
Android's deprecated `Equalizer` / `BassBoost` / `Virtualizer` using
**audio session 0 (the global output mix)**. The app has
`MODIFY_AUDIO_SETTINGS` but no capture or privileged permissions.
The adapter creates effects only while the switch is on and Chromium
reports playback, disables them on pause, and releases them on teardown
or when the switch is off. The effect status and whether it has control
are visible in the sheet.

**Critical constraints:**

- Session 0 output-mix effects are deprecated and may fail or be ignored
  on modern Android, Android Auto, Bluetooth or USB routing.
- If supported, a global-mix effect can also modify **other applications'
  audio** while AAYouTube is playing. This is why it is not enabled
  automatically and why the warning is prominent in the sheet.
- An Android effect engine reporting initialized is **not proof** that
  Chromium's actual audio passes through it. The phone and car must be
  audibly tested on each audio route.
- Android native Equalizer/BassBoost has no mRadio-style PCM limiter;
  large positive gains can clip. Start from Flat, then make small changes.
- No second decoder, duplicated audio stream, audio loopback, playback
  capture, Shizuku or root-based processing is started.

## Checks

Tests verify mRadio's preset and five-band values, safe clamping,
interpolated hardware frequencies and level limits, reset and off-by-
default behavior, disabled unsupported 3D modes, actual WebView
playback-state wiring, release on quit and the absence of capture paths.

Hardware acceptance: first install green experimental APK. Play music on
the phone, open Settings → Equalizer, switch on and test a clear Bass boost
or Vocal preset; check the status message. Compare Flat with custom gains
while listening. Then test Bluetooth and Android Auto separately, ensuring
steering-wheel controls, video and audio continuity are unchanged. If the
engine says unavailable or there is no audible change, switch OFF; do not
assume the adapter works because it compiled.

Android documentation:
- https://developer.android.com/reference/android/media/audiofx/AudioEffect
- https://developer.android.com/reference/android/media/audiofx/Equalizer

## No ADB grant

The ADB `DUMP` session-tracking workaround was removed: the in-player
DSP needs no permission grant. The app no longer declares `DUMP`; any
earlier grant can be revoked with
`adb shell pm revoke com.aayoutube.app android.permission.DUMP`.
The native fallback attaches only to the legacy output mix (session 0)
and only when the page reports the in-player DSP cannot attach.

## Logs

The equalizer writes **no logs** (Kotlin or page script); a contract
test enforces this. Diagnose dropouts with McpAdbAndroidLogger instead:
Chromium's own `SyncReader::Read timed out, audio glitch count=N` line
shows whether the realtime audio thread misses deadlines.
