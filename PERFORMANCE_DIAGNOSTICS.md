# AAYouTube performance and decoder triage (experimental PR #54)

These checks are **on-device measurements**, not predictions of energy use. On Android, a hardware
decoder may be active while the app's UI, Chromium renderer, compositor
still consume substantial CPU. Never infer active decoder hardware solely from supported codec lists.

## Logging and counters (no private media URLs or video IDs)

* `AAYouTube-PERF` every ~30 seconds of active playback: `webCpuPct`, rolling 10-sample
  `webAvgPct`/`webPeakPct`, `domTicks`/`domMs`/`mutations`/`longTasks`, `resourceLoads`,
  `layoutChanges`, `refits`, `recoveries`, `frames`, `droppedPct`, video dimensions,
  reported codec, quality tier, thermal status.
  `resourceLoads` measures page *loadstart* notifications, **not** native decoder creations.
  `frames=0`, `droppedPct=-1` or `codec=unknown` may mean the browser API could not supply
  a measurement. `domMs` is sampled JS wall time, **not** CPU time.
* `AAYouTube-HEALTH` every 60 seconds while the media service runs: main **media-service**
  process CPU usage, 10-sample average and peak, wake-lock and screen state.
* `rendererCpuPct=adb-only`: sandboxed Chromium renderer CPU cannot be read reliably
  by the application's JavaScript bridge. Read the process directly through ADB.
  This proves mode only: Web Audio does **not** expose its audio thread's CPU percentage.

## ADB experiments

Run at least 2 minutes per scenario, with the **same video and brightness**.
After the first 30-second warmup, compare the last 3+ performance samples:

1. Foreground playback 360p or 720p, no PiP.
2. Identical playback in background, compare CPU and temperature.
3. Fullscreen → mini-player → fullscreen → PiP → fullscreen. Count changes to
   `layoutChanges` and `resourceLoads`; inspect black frames and audible continuity.
4. Screen-off audio-only, then screen on. Check background saver and wake-lock state.

Example commands from a trusted workstation with an authorized device:

```sh
adb logcat -c
adb logcat -v time | grep -E 'AAYouTube-(PERF|HEALTH|VIDEO)|CCodec|Codec2Client|MediaCodec|BufferQueue|GraphicsTracker'
# In a second terminal; Android may restrict process visibility and output.
adb shell top -b -n 1 | grep -E 'aayoutube|webview.*sandboxed|surfaceflinger'
adb shell dumpsys thermalservice
adb shell dumpsys battery
```

For a real decoder lifecycle audit, inspect the **system** `CCodec`/`MediaCodec` lines
for creation, release, configured output surface and allocation of new block pools.
A single initial codec setup is expected. Repeated setup in the same uninterrupted
stream, especially at the same time as `resourceLoads`/`layoutChanges`, is suspicious
but still requires timestamp correlation. A single `setOutputSurface` is not a failure.

## Interpretation

* Main-app, media-service and sandboxed-renderer CPU **must be compared separately**.
  `Process.getElapsedCpuTime()` measures only the caller process; multicore process
  percentages may exceed 100%.
* Hardware VP9/AV1/H.264 capability is not evidence that **every** stream is accelerated.
  Compare active decoder names (e.g., vendor `c2.*.decoder` vs `c2.android.*`) from logcat.
* A high count of full-page scans, repeated media source restarts, sustained frame drops, or
  renderer CPU spikes narrows the likely cause. No single metric proves causality.
* Avoid changing codecs/quality or forcibly recreating the WebView as an automatic
  response to a CPU sample; preserve playback, pause and audio-focus intent.
* Stop testing if temperature rises noticeably or Android reports severe thermal throttling.

## Adaptive performance engine (experimental PR #57)

`AdaptivePerformance.kt` (pure policy, unit-tested) and `PerformanceMonitor.kt` (Android sampling)
form one central level that every component follows:

| Level | Raised by (any one) | Effect |
|---|---|---|
| NORMAL | — | Secondary (phone copy) at its size-based ceiling (240p small view, 360p larger/PiP); primary at the saved quality. |
| ELEVATED | thermal LIGHT, memory LOW, battery ≥ 40 °C, sustained CPU, primary buffering ≥ 4 s, ≥ 10 % dropped frames (2 windows) | Secondary 240p; page nonessential DOM work ×2 slower; hidden secondary released after 30 s. |
| HIGH | thermal MODERATE, battery ≥ 43 °C, primary buffering ≥ 15 s, ≥ 25 % dropped frames (2 windows) | Secondary suspended (renderer released after 12 s); primary 1080p ceiling (Auto stays adaptive below it); diagnostics every 60 s. |
| SEVERE | thermal SEVERE+, memory CRITICAL, battery ≥ 46 °C | Audio first: primary 720p ceiling, page work ×3 slower, secondary suspended. |

Charging moves the battery thresholds 1.5 °C earlier. Escalation is immediate; recovery is one level
per 20 s of calm (and never sooner than 10 s after the previous change), so a signal hovering at a
threshold cannot switch quality back and forth. The phone-copy gate keeps its own calm period on top,
returning first at reduced quality. Transitions are logged as `AAYouTube-PERF-STATE` with the reason,
how long the previous level lasted and every signal value.

### Measurements added

* `PERF` (page window, 30 s; 60 s at HIGH+; 120 s with the screen off): `decodedFrames`,
  `presentedFrames` (decoded − dropped), `stalls`/`stallMs` (audio interruption **estimate** from
  `waiting` → `playing`; audible output is not measured), `dupReports` (bridge reports suppressed),
  `domObserved` (false while the hidden page's MutationObserver is disconnected), `perfLevel`,
  `mirrorRenderer`.
* `PERF engine …`: level, reason, renderer allocations/releases (primary and secondary separately,
  plus live count), level transitions, thermal changes, battery temperature.
* `HEALTH` (60 s; 180 s with the screen off): `wakeHeldMs` (total held), `wakeAcquisitions`,
  `wakeStallReleases`, `perfLevel`. `POWER` logs each acquisition and release with its reason and held time.
* Existing: main-process CPU (`webCpuPct`, `mainCpuPct`), mode-transition latency (`Mode handoff complete
  elapsedMs`), phone-copy sync drift (`MIRROR interval stats`). Renderer CPU stays ADB-only.

### Power and CPU changes

* CPU wake lock: always acquired with a 10-minute timeout, refreshed every 5 minutes while wanted
  (state reports or the health tick); a recovery that has not produced playback for 3 minutes releases it.
* Last position: Pause, Stop, Quit, end and new videos save at once; while playing every 15 s
  (30 s screen off), down from every 5 s.
* Page script: the document MutationObserver is disconnected while nothing shows the page (a 12 s
  keep-alive tick still binds the player and applies quality); identical state reports are not resent
  within 15 s; the 10 s position heartbeat pauses while buffering/seeking; ambient/cinematic layers and
  `backdrop-filter` blur are disabled (hardware acceleration untouched).
* Compose: the app screen no longer recomposes on every position report; only the lyrics overlay and
  the progress lines read the position, and their 500 ms clocks stop while buffering.

### Investigated, not adopted

* **Surface frame-rate hint**: Chromium owns the video `SurfaceView` inside the WebView; the app cannot
  call `Surface.setFrameRate` on it. The window already requests 60 Hz while a video plays (`RefreshRatePolicy`);
  24/30 fps content divides evenly into 60 Hz except 24 fps pulldown, which a window hint cannot fix without
  making controls and scrolling stutter. Revisit only with a measured display-power gain.
* **Audio offload / direct playback**: Chromium renders WebView audio itself (AudioTrack/AAudio in its own
  process); offload is not reachable from the app. Genuine audio-only or offloaded playback needs a native
  player fed with extracted stream URLs, which would replace the reliable YouTube engine. Not adopted;
  the retained audio-only renderer remains the audio path.
* **Primary mini-player size ceiling**: lowering the phone mini player below 720p would switch quality on
  every mini/full toggle, which existing behaviour deliberately avoids. Only the secondary phone copy is sized
  to its view.

### Benchmark before adopting permanently

Use the same video, brightness, network and start temperature; 2 minutes warm-up, then compare at least
5 minutes per run, with and without the change (ADB `McpAdbAndroidLogger` tools or the commands above):

1. Car playback with the phone copy visible (full, mini, PiP): `MIRROR interval stats`, `PERF`, thermal.
2. Phone foreground playback, then screen off for 10 minutes: `HEALTH wakeHeldMs`, CPU, battery temperature.
3. Heat soak (charging + navigation): count `PERF-STATE` transitions; there must be no back-and-forth
   within 20 s, and primary audio must not stall (`stalls`, `stallMs`).
4. Quit: `POWER ... released`, `PERF engine ... live=0` on the next session, no `HEALTH` after Quit.
