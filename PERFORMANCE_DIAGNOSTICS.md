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
