# Experimental video (Android Auto)

Reference: [GitMag/AnyAuto](https://github.com/GitMag/AnyAuto).

## Current opt-in (phase 1)

Settings > Experimental video holds a persistent, **off-by-default** switch.
The setting is read once when a legacy Android Auto video activity is created.
Changing it does not stop the current decoder or move a live WebView.

When on, a bounded four-check watchdog checks the already active legacy
projection frame for a missing/unattached, zero-sized or wrong-display WebView.
Only those anomalies trigger reattachment using the **existing**
`WebHost.attachToCarWindow` path and `WebHost.refitCarViewport`. No duplicate
player, new media session, background video or navigation-template service is
started. Standard projection remains the default and underlying rendering
implementation. This limited resilience experiment is **not** AnyAuto's
virtual-display/GL/Shizuku architecture.

Checks cease when the attempt budget expires or the activity stops; no
ongoing polling, renderer replacement on healthy frames or automatic audio
restart. Surface geometry is not proof of video paint: the existing CAR
render diagnostics must still be reviewed.

## Device validation

On a parked Android Auto host test off and on with the same video:

1. Confirm Video and Music remain separate launcher entries and audio controls
   continue working; neither mode should duplicate audio.
2. Resume video, use Next/Previous repeatedly, toggle controls, and seek.
3. Disconnect/reconnect the car, replace the projection window, rotate the
   phone, put it in the background, and verify playback position survives.
4. Observe CAR logs for `Experimental video at connection=` and the bounded
   checks; turning off and reconnecting restores exactly the normal path.
5. Verify Quit stops car media and video; a stale car activity must not rebind.

JVM tests cover opt-in defaults and the pure recovery conditions. CI does not
validate WebView paint, head unit touch, decoder health, or host restrictions.

## Later research, NOT implemented yet

- Adapt AnyAuto concepts for preview sizing, visible/stable surface areas,
  touch-region transform and headless car testing.
- Prototype a **separate** virtual-display GPU video backend, only after
  validating runtime permissions, actual video frames, input fidelity, car-host
  policies, and safe switching without double playback.
- Evaluate licensing before reusing source. AnyAuto's GPL license should not
  be copied into this project without an explicit compatibility decision.

Do not expose an unavailable backend as if it worked. No in-motion video
permission is implied by this flag.

## Phase 2 — passive rendering instrumentation and safe preview

- A geometry-only preview in Settings simulates common Android Auto head-unit
  viewports (800×480, 1280×720, 1920×1080 and 1024×600) and letterboxes a
  16:9 video without launching a second player. The same pure geometry policy
  runs in JVM tests.
- The active legacy car session reports its display ID, WebView/frame size,
  global visible area and a reference video rectangle to an in-memory
  diagnostics card. Stale car hosts cannot replace the newest diagnostic state.
- When the experimental flag is on, the existing Chromium render probes also
  expose decoded/dropped frame counts, video pixel size and paused state.
  These are **diagnostics, not a black-frame detector**. They do not restart
  a healthy WebView or authorize switching output.
- Input events are **observed, not injected or intercepted**. Pure gesture
  tests guard against false taps after drags and multi-pointer touches; the
  original MotionEvent continues to YouTube's WebView unchanged.
- Rebind recovery is capped at two attempts over four timed checks. Reaching
  the limit or an exception disables further experimental checks for that car
  session and reports standard fallback. The saved user toggle is unchanged.
- The current live renderer is still **legacy projection**, not AnyAuto's
  Shizuku/NavigationTemplate virtual-display compositor. GPL-licensed
  upstream implementation was **not** copied.

### Phone-only headless preview/screenshot

Build and install the debug APK, then connect a phone with USB debugging:

```bash
bash tools/car-video-preview-capture.sh /tmp/aay-car-preview.png
```

The script launches a **debug-only** preview intent on the phone and captures
its screenshot. It restarts the phone activity; do not run it while listening to
media. It does **not** launch an Android Auto host, inspect car SurfaceCallbacks,
test the GPU video compositor, or assert real YouTube frame decoding.

### Hardware prerequisites for future independent renderer

A second real renderer needs a supported host surface, input routing, single
player ownership across host teardown and permitted API/privilege behavior.
AnyAuto uses an AndroidX NavigationTemplate with Shizuku shell privileges.
AAYouTube removed the same navigation-template path after it interfered with
its existing WebView. Reintroducing that host path should be a separately
testable backend that cannot run concurrently with legacy video, and should
only be offered after parked-car and licensing validation. A toggle must never
claim an unavailable renderer is running.
