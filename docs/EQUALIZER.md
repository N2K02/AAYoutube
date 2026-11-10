# AAYouTube equalizer — mRadio UI port, Chromium limitations

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
