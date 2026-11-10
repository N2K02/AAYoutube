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

## Experimental workaround: optional AAYouTube session targeting

Enhanced player tracking is modelled after the ADB-assisted approach used
by Wavelet and Poweramp Equalizer; it does **not** modify Chromium or hook
hidden Android AudioPlaybackConfiguration APIs. It is not a guarantee that
Android will expose WebView's actual audio session.

**One-time optional setup**, after installing the latest experimental APK:

```sh
adb shell pm grant com.aayoutube.app android.permission.DUMP
```

The app declares DUMP but cannot automatically grant it. Restart the app
or toggle Equalizer off/on after granting, then play YouTube audio.
Permissions can be revoked:

```sh
adb shell pm revoke com.aayoutube.app android.permission.DUMP
```

While Equalizer is ON and a Chromium video reports playing, an asynchronous,
bounded, read-only `dumpsys audio` request runs no more often than
every eight seconds. Its 2.5-second timeout kills the subprocess if needed.
Only `AudioPlaybackConfiguration` lines with the exact AAYouTube Unix UID
are retained transiently; other players' records are discarded immediately.
The parser accepts only an **active started** player, a positive audio
session, and an unambiguous single session ID attributed to AAYouTube.
It does not store session IDs, diagnostics dumps, audio samples, URLs,
player identities, or device identifiers in persistent storage or logcat.

If a unique own-UID session is verified, the existing Android native
`Equalizer`, `BassBoost`, and `Virtualizer` are attached to that
**nonzero audio session**, not the deprecated global mix. The current
effect handles are released and recreated when the session changes.
If an ADB-granted scan fails, is unavailable, finds multiple different
sessions, or detects no own-UID session, the new mode **fails closed**
(no global-mix fallback). On pause/quit, its scanner stops and effects
are disabled/released. Chromium remains the only actual audio player.

Without the optional DUMP permission, the user-enabled existing legacy
**session 0 output-mix fallback** continues as before; it can affect other
apps while AAYouTube is playing. Keep Equalizer OFF if that behavior is
unwanted. Hardware support varies.

### Known limits to validate on the user's device

Android's public AudioPlaybackConfiguration API anonymizes player sessions.
The detailed session ID accessor is system-only. This design reads optional
ADB-granted service diagnostics, which OEM builds and Android releases can
restrict; the scanner never attempts non-SDK reflection.

Chromium WebView's audio may run under an isolated renderer UID rather than
the app UID. If the real player is not attributed to the app UID, no session
is selected. Android effects can also fail to affect some Bluetooth/Android
Auto paths despite a successful audio-session attachment.

Web Audio `createMediaElementSource` was deliberately NOT added to real
YouTube elements: CORS-cross-origin media can emit silence, and redirecting
an element to an AudioContext cannot be undone safely without recreating
the media element. A muted/stuck car video would be worse than a missing EQ.

The only trustworthy success criterion is an **audible** change when
comparing Flat with Bass boost/Vocal on the same output route while audio
and video remain smooth. Verify phone speakers, Bluetooth and Android Auto
separately.

References:
- https://developer.android.com/reference/android/media/audiofx/AudioEffect
- https://github.com/Pittvandewitt/Wavelet/blob/master/docs/Settings.md
- https://forum.powerampapp.com/topic/20638-using-adb-to-allow-equaliser-permissions-for-more-audio-applications/
- https://www.w3.org/TR/webaudio-1.1/
