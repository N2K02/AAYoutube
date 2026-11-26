# Experimental native audio engine

Off by default (`Settings → Experimental → Native audio engine`). The WebView engine stays the
default and the fallback for everything.

## Status

The engine, ownership coordinator, capability/offload probes and handoff are complete and tested.
**No stream source ships**: `StreamProviders.current` is `NoStreamProvider`, so with the switch on
the app logs `NATIVE-AUDIO engine=web reason=NO_PROVIDER` once per video and the WebView keeps
playing. NewPipeExtractor, InnerTune and OuterTune are GPL-3.0 (this repository is MIT) and extracting
YouTube stream URLs bypasses YouTube's own player; they were used as architectural references only
(separate audio streams, audio-only ExoPlayer, offload, queue ownership), with no code copied.
A provider is accepted only if `authorized = true` (a source licensed for direct playback).

## Architecture

| File | Role |
|---|---|
| `StreamProvider.kt` | Provider interface, authorization gate, stream selection (decodable → offload-capable → hardware → bitrate; expired URLs skipped). |
| `AudioCapabilityProbe.kt` | AAC, Opus, Vorbis, MP3, FLAC, AC-3, E-AC-3 decoders (hardware/software) and AudioManager offload support for the current route. |
| `AudioOffloadPolicy.kt` | Offload only if allowed, route is not Android Auto projection or unknown, codec offload-capable, speed 1×, AudioManager supports it and no failure in the last 24 h for that codec/route (`OffloadFailureBreaker`, persisted). |
| `PlaybackHandoffPolicy.kt` | Position compensation (clock kept moving during preparation), user intent (explicit pause never resumed), eligibility (no live, ads, near-end, unknown duration), state merge (web metadata/queue + native clock). |
| `PlaybackEngineCoordinator.kt` | Exclusive ownership state machine `WEB → PREPARING → NATIVE`; source always silenced before target starts; at most one audible engine. |
| `NativeAudioEngine.kt` | Media3 ExoPlayer, audio only (`TRACK_TYPE_VIDEO` disabled, only the audio URL loaded), Media3 audio focus, becoming-noisy pause, offload preferences, silent (volume 0, not playing) until handed over. |
| `NativePlayback.kt` | Glue: resolves streams, probes route/capabilities, merges state into `WebHost.snapshot` (so the existing media session, notification, lock screen, Bluetooth and Android Auto controls keep working), routes transports. |

### Ownership rules

* Preparation is silent; the WebView keeps playing until native reports READY. Then: web silenced
  (paused at `tiny`, explicit pause so no recovery resumes it) → native starts at the compensated position.
* While native owns: play/pause/seek from any controller go to native; next/prev, page navigation to
  another video and the end of the video return to the WebView (it owns queue and autoplay).
* Native error → WebView resumes at the native position with the user's intent. Preparation timeout
  (8 s) or error → nothing audible changes.
* A web that becomes audible again while native owns is silenced (`duplicate-prevented` log).
* Quit / renderer release releases the native engine. Switching the setting off returns to web at once.

### Interruptions

* Calls and other apps: Media3 audio focus (transient loss pauses and resumes, duck for navigation
  prompts). The web's "other media" hold ignores native playback.
* Bluetooth/wired disconnect: becoming-noisy pause.
* Android Auto: same media session; offload never used on the projection route.

## Logs (`AAYouTube-NATIVE-AUDIO`)

`engine=web reason=…` (why native was not used), `route=… caps=[mp4a-latm=hw+offload opus=sw …]
separateAudio=true videoDecoding=off`, `handoff=prepare|native|web|aborted … latencyMs=…`,
`decoder=c2.… initMs=…` or `decoder=dsp-offload`, `offloadActive=true|false requested=…`,
`offloadSleeping=…`, `event=underrun|error|becoming-noisy|suppression`,
`offload=disabled-after-failure codec=… route=… retry=no-offload`.

## Validation before enabling by default

Requires an authorized provider. Same track, volume, route and start temperature; 2 min warm-up,
10 min measured, WebView vs native (offload on/off):

1. CPU (`HEALTH mainCpuPct`, `top` for the renderer), memory (`dumpsys meminfo`), battery drain
   (`dumpsys batterystats` delta), battery temperature and thermal status.
2. Mode-transition latency: `handoff=native … latencyMs`, `handoff=web … latencyMs`.
3. Offload: `offloadActive=true` and `offloadSleeping=true` with the screen off; no underruns.
4. Interruptions: incoming call, navigation prompt, Bluetooth disconnect/reconnect, Android Auto
   connect/disconnect, next/prev, seek, Quit — never two audible engines, position and pause preserved.
