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
