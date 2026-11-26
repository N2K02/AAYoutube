# Experimental native audio engine

Off by default (`Settings → Experimental → Native audio engine`). The WebView engine stays the
default and the fallback for everything.

## Status

The engine, ownership coordinator and capability/offload probes are experimental; real-device handoff and offload verification remain required.
**No default stream service is configured**: `StreamProviders.current` is `NoStreamProvider`, so with the switch on
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

* Native audio is eligible only during background playback with no visible phone, PiP or car video; returning to video cancels pending preparation or returns ownership to WebView. The native position clock samples every 10 seconds while confirmed offload is sleeping, otherwise every second while playing.
* Preparation is silent; the WebView keeps playing until native reports READY. Then: web silenced
  (paused at `tiny`, explicit pause so no recovery resumes it) → native starts at the compensated position. The WebView's asynchronous pause and network activity still require physical verification; a paused page alone does not prove video downloads have stopped.
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

## Configuring an authorized HTTPS resolver

Settings → Native audio engine → Authorized HTTPS resolver URL. Save the URL and enable the engine. Only background audio requests use this source; GET `?videoId=ID` sends no YouTube credentials. Use an operator-controlled catalog of content licensed for separate audio playback, returning the matching track. Unknown IDs return 404. Example (illustrative URLs, not a running service):

```json
{"videoId":"abc","authorized":true,"durationMs":120000,"audio":[{"url":"https://media.example.org/track.m4a","mimeType":"audio/mp4","codecs":"mp4a.40.2","bitrate":128000,"sampleRate":44100,"channels":2,"expiresAtMs":0}]}
```

The resolver must use HTTPS without redirects; audio URLs must be HTTPS. No custom headers, credentials or stream URLs are logged by the provider. Missing, invalid or unauthorized manifests fall back to WebView. The authorization field is an operator assertion, not automatic verification of rights. The official YouTube API does not offer general separate audio playback. Native offload remains unverified until a real source is configured and tested.

## First-party WebView resolver feasibility probe

With Native audio enabled, existing playback diagnostics report aggregate audio format counts from matching current-player metadata: `probe=metadata-only audioFormats=… direct=… wrapped=… aac=… opus=…`. -1 means unavailable or not attributable to the active video. The probe never exports stream URLs, signatures, credentials or raw responses and never starts native playback. A positive direct count establishes only that a URL is present, not that it is transferable, licensed, valid for the native client or offload-capable. No signature or challenge handling is implemented.

## Confirmed handoff

Native playback waits for a matching WebView media element to confirm it is paused. Late acknowledgements cannot start native playback after cancellation. Actual duplicate-audio behavior still needs device verification; a matching authorized audio stream is required.

## Audio-only stream selection

The native engine ranks all supplied separate audio variants by actual per-stream route/codec/sample-rate/channel offload eligibility, then hardware decoder and bitrate. It keeps video mode on WebView. If the server exposes only adaptive metadata, the diagnostic now distinguishes SABR, DASH and HLS by presence only (no URLs, tokens or raw requests are logged). SABR metadata is not a playable URI and no SABR transport is implemented. A source authorized for direct playback is still required. Center transport diagnostics record only matched-control counts and media playing state.

## SABR transport foundation and automatic background mode

The hidden phone automatically enters lightweight WebView audio mode when background playback is enabled; PiP and visible video are excluded. The delayed low-resolution saver remains active if enabled. This mode does **not** disable video decoding by itself; decoded/dropped frames continue to be reported truthfully. Car retention keeps its existing behavior.

A bounded Kotlin UMP framing reader is implemented and unit-tested against synthetic input (1–5 byte integers, truncation, payload/response/part limits). It is a foundation only: no SABR session bootstrap, request protobuf, segment timeline, Media3 source adapter or live transport has been integrated. The HTTPS authorized audio provider remains the only native stream source. References: https://github.com/LuanRT/googlevideo and https://github.com/chadacious/sabr-exoplayer (MIT). No upstream code was copied and no dependencies added.

## Experimental SABR playback integration

The pinned MIT-licensed `chadacious/sabr-exoplayer` transport at `710ac75fece262315cb9c48231ae1bce11b70636` is vendored with its license. Protobuf and DASH support are built into AAYouTube; no external player or runtime is needed. The configured authorized resolver may return an `audio` entry with `sabrMpd` instead of `url`. It must be a static MPD containing exactly one audio adaptation/representation, a `sabr://audio?key=...` BaseURL, SegmentBase/Initialization ranges and the SABR supplemental property with valid session configuration. Each candidate may provide its own manifest. Native selection and offload policy remain unchanged. No video representation is accepted; request destinations are restricted to HTTPS and the authorized endpoint host (or its Googlevideo CDN siblings). The pinned source has local bounds and logging modifications. No session token generation, YouTube challenge handling or live session bootstrap is implemented: the resolver must supply an authorized valid session. Device playback is not yet verified.

## On-device session bootstrap

Enable Native audio engine and On-device SABR in its settings (only for content authorized for separate playback). Leave the external resolver blank. On backgrounding, the app reads only the current player's already-present audio metadata, server URL and ustreamer config, checks identity, playability and available init/index ranges, builds single-audio MPDs and chooses a supported format. It never collects login cookies, generates access tokens, deciphers signatures, or bypasses playability restrictions. Signed URLs and session descriptors stay in memory and are not logged. A missing/expired session, rejected request or unsupported stream keeps the WebView active. This is an experimental attempt; YouTube playback restrictions and changes can prevent it working, and successful real playback is not yet verified.

### Review hardening
Native handoff is limited to unbuffered 1× playback until speed synchronization is implemented. User transport aborts preparation, permanent native pauses survive a return to WebView, and unexpected WebView playback immediately stops native audio. SABR diagnostics no longer retain raw protobuf request JSON; processor logs are muted. Malformed stream expiry is rejected. CI and device playback remain subject to verification.

### Server rejection handling
A device test reached native SABR initialization but returned HTTP 403 before audio decoding. HTTP 401/403/410 now fail without Media3 retries, record a status-only reason, keep the WebView engine, and suppress repeated attempts for that video for up to 30 minutes or until the provider is changed/session released. No authorization bypass is attempted. Successful native playback still requires a server-accepted session.
