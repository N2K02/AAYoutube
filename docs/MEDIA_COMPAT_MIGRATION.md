# MediaCompat architecture — AABrowser parity

Reference: [fcaronte/AABrowser](https://github.com/fcaronte/AABrowser), pinned at
`dca3f543c83445ee4b38761eed824eb92208109f`.

## Implemented

- `PlaybackService : MediaBrowserServiceCompat` owns the `MediaSessionCompat`,
  Android Auto audio discovery and transport, media buttons, notifications,
  voice searches, recent and favourite browse trees, and the resumable last video.
- `CompatMediaConnection` connects the phone and the classic projection screen.
  Both use the same media session and issue `Commands.HELLO` to link the WebView.
- `WebHostService` runs in the separate `:web` process with one shared WebView;
  Messenger IPC forwards media controls and player snapshots across processes.
- Registered `LegacyProjectionService : CarActivityService` owns the **only**
  car-video entry, following AABrowser's v4 legacy projection SDK lifecycle.
- Media3, `BridgePlayer`, `MediaController`, NavigationTemplate,
  `CarVideoService`, and the experimental virtual-display renderer have been removed.
- WebView registration is acknowledged and bounded by a five-second timeout.
  Pending play/open/search commands are replayed only *after* acknowledgement.
  Playback snapshots carry a connection epoch: stale/disconnected messages
  cannot overwrite the current media session after a new connection.
- The current YouTube Up next queue is exposed as a live, bounded, deduplicated
  MediaCompat browser folder, alongside recent and favourite videos.
- Quit is an explicit cross-process handshake with a bounded fallback.

## Deliberate differences from AABrowser

AAYouTube keeps its **single YouTube player** instead of AABrowser's browser tab
manager and local playback backends. Its media service and WebView run in
separate processes for background playback, so IPC and reconnect logic are
still necessary even though both sides now use MediaCompat. Spotify, local files,
weather widgets and multi-tab navigation are excluded.

The archived Google projection SDK has unverified redistribution rights. Its
binary is fetched from the pinned upstream commit, verified by size and Git blob
SHA, and restricted to personal experimental evaluation. Neither this code nor
the upstream app establishes Android Auto video support on every head unit.

## Validation requirements

Compile, run unit tests and JS tests, then test a **parked** physical Android Auto
head unit: car/phone WebView handoff, rotation/configuration changes, media
browse/search, steering-wheel keys, buffering, seek, screen off, process death,
reconnect, background playback and Quit. CI does not substitute for device tests.


## Lifecycle hardening (2026-10-31)

- A Messenger disconnect does not automatically navigate to the last video.
  Once registration is acknowledged, the media service requests a player snapshot;
  only a confirmed missing player can be reopened from the last playing position.
  PAUSE, STOP, OPEN, SEARCH and Quit cancel recovery intent.
- The primary WebView preserves car ownership through a renderer crash, restores
  the previous playing or paused video from its last known position, and stops
  automatic restarts after repeated crashes. Old JS bridge reports are rejected
  using WebView generation checks.
- A crash of the independent mini-browser WebView replaces that browser without
  resetting the player. Its media is always muted, protecting the primary audio.
- Car input is rebound to a surviving WebView after the input-manager wrapper is
  recreated. Phone/car media-link callbacks use explicit owner checks.
- Lyrics cache entries are qualified by title/artist metadata so a weak early
  result cannot mask a later timed match. Provider connections are disconnected
  on coroutine cancellation and response reads are bounded.

The instrumentation/device matrix remains required; static contracts and JVM
logic tests cannot prove Android Auto host and decoder behavior.

- An idle media service stops automatic reconnect attempts after four failures;
  playback recovery and open phone/car clients retry with capped exponential
  backoff and terminate on explicit disconnect or Quit.
- Artwork loads asynchronously only from trusted YouTube/Google image hosts;
  stale downloads cannot overwrite subsequent media entries. Worker tasks are
  shut down with the MediaCompat service.


## Mobile mini-player, Android PiP and Settings audit (2026-10-31)

- The in-app 16:9 mini player now measures the actual Android WebView to
  mini width/height. The former fullscreen WebView + Compose graphicsLayer
  scale could leave Chromium's separate video surface unscaled, letterboxed
  or grey on some devices. The Compose controls remain independently draggable.
- In system PiP, the player WebView occupies the whole activity; the browse
  WebView is hidden and paused. PiP params use the visible native rectangle,
  landscape ratio and non-seamless resize (video textures are not generally
  safe for seamless scale animations). PiP entry failures restore normal CSS.
  The OS, not the app, determines the resulting floating window size.
- CSS now targets video-only elements without globally hiding accelerated
  ancestors; mini and system PiP have separate mode classes. YouTube's
  redundant 'Tap to unmute' overlay is suppressed, without fake clicks.
- Background quality reduction is only used when truly backgrounded, not
  during visible mini or system PiP playback; it is restored on reentry.
- Feature audit: video options are persisted by Features, applied to both
  primary and browse WebViews, and the page script receives live updates.
  Android auto-PiP eligibility now responds to Settings switches and tab
  changes. Prior SponsorBlock, dislikes, lyric-provider and updater behavior
  remains unchanged unless independently tested.
- Regression tests cover the DOM video-only classes, the mini surface layout,
  autoplay prompt removal, native PiP configuration, and Settings wiring.

Pending physical verification: YouTube can change its DOM and system PiP
window size is chosen by Android; Android Auto, multiple WebView versions,
PiP transitions, subtitle visibility and real video decoding require
device testing beyond CI compilation and JavaScript unit tests.

- Settings > Logs now combines shared diagnostic files from both processes.
  The media-service process initializes logging as well; a file marker keeps
  opt-in logging enabled/disabled across processes without cached preference
  divergence. The Logs screen refreshes disk data off the main thread.


## Android Auto AABrowser-style WebView playback continuity

- Upstream AABrowser's primary WebView overrides onPause() with a no-op and
  always marks the window visible. AAYouTube previously handled hidden-window
  visibility but allowed WebView.onPause() to suspend Chromium video decoding.
- The shared video WebView now ignores automatic onPause/window-hide events
  **only** while projected in a car, in PiP, or when background playback is
  both active and enabled. An idle non-projected page still freezes normally.
- The projection page is marked active before Compose creates the player WebView.
  On projection start, explicitly resume/invalidate the player renderer and
  clear transient WebView hold; do not reload the surviving WebView.
- Newly selected YouTube watch/live URLs opened through the app's navigation
  receive the existing `aay=1` autoplay hint while on the car display. Existing
  paused sessions and explicit user pauses remain respected.
- No new permission or Android Auto manifest category is required: the app
  already uses the upstream legacy CarActivityService projection categories,
  hardware acceleration, INTERNET, WAKE_LOCK and foreground media playback.
  This patch does not attempt to defeat vehicle/host driver-distraction policy.
- Device test: on a parked vehicle, select and play a video, switch phone ↔ car,
  interrupt/reconnect Android Auto, pause/resume, and verify frames with audio.


## AABrowser exact primary-WebView lifecycle parity

Following device playback feedback, the previously conditional playback-preservation
policy was removed. The primary `BackgroundWebView` now mirrors upstream
`BrowserScreen.kt`: Android lifecycle `onPause()` is a no-op and
`onWindowVisibilityChanged()` always reports `View.VISIBLE` to Chromium,
irrespective of the active car/phone screen, playing state or Background play
preference. A normal user Pause still targets YouTube's HTML player directly.
To avoid losing idle cleanup, `WebHost.freeze()` calls a separate
`pauseForIdle()` which invokes `super.onPause()` only after the existing
45-second idle check. Quit destroys the WebView.

The app already has the AABrowser legacy projection service registration and
foreground media permissions; none were added or changed for this patch.

## AABrowser three-path Android Auto parity (2026-10-31)

- Corrected the previous media-only routing change: car *services* (not a
  CAR_LAUNCHER activity category) announce video entry points. The archived
  legacy projection, AndroidX CarApp wide-surface fallback, and MediaCompat
  browser coexist as in AABrowser. They are distinctly labelled Video vs Audio.
- The old car Wide implementation has been restored with host allowlist in
  release builds and temporary DHU/debug openness. Surface replacement now
  rebinds/resizes the same VirtualDisplay without recreating the WebView.
- Visible/stable host insets determine actual video bounds and input hit region,
  with pure policy tests for resizing and split-screen sidebars.
- The projected frame ignores transient invisibility, matching AABrowser's
  CarFrameLayout. Legacy IME handles null input connections and SDK method
  variations. Stale disabled service components are re-enabled together.
- A five-second grace on surface loss preserves the decoder across brief
  reconnects. Final teardown releases the display, media controller, coroutines,
  and WebView owner, and lets the phone reclaim the player.
- Distinct from AABrowser: AAYouTube intentionally retains one playing YouTube
  WebView instead of independent website tabs and local/Spotify/weather engines.
  Do not assume in-motion video is permitted or device video proven by CI.

- On wide mode, the shared WebView's renderer generation is now observed. A
  crashed/recreated renderer reattaches to the existing car Presentation even
  if Android Auto never destroys or replaces the Surface. The old test that
  prohibited a second video service was updated to check both video paths.

- The base AndroidX Car App API alone does not provide the projected Android
  Auto host implementation. Pinned app-projected:1.7.0 to match AABrowser's
  dual app + app-projected dependency graph, with an integration regression check.

## Shared phone and projected video top-menu auto-hide (2026-10-31)

- The phone/secondary Android Auto display watch header is rendered as a
  transparent-to-layout overlay rather than Scaffold topBar. Hiding it does not
  resize Chromium's WebView or disrupt video surfaces.
- The AABrowser-style legacy projection menu uses the same persisted timeout:
  default 3 seconds; choices Never, 1, 2, 3, 5, 10, 15 seconds.
- Tap the top 28dp edge to reveal the header; each reveal rearms the timeout.
  Non-video Settings/subpages retain their always-visible single header,
  while fullscreen/PiP hide app-owned header as before.
- The AndroidX NavigationTemplate action strip is rendered by the Android Auto
  host, so this preference cannot force-hide system-owned controls.

## Two distinct Android Auto icons and top-edge swipe (2026-10-31)

The user confirmed **legacy projection video works** and requested the other
icon to open a native music player, rather than the competing AndroidX
navigation side-panel that stole the video WebView. Exported PlaybackService
as a MediaBrowserServiceCompat named **AAYouTube Music**, alongside the single
AABrowser legacy video projection. The media process creates no car WebView;
browse and media session controls stay independent of legacy projection. Both
automotive descriptors advertise media+projection, not navigation templates.
On both phone and legacy car video, a hidden app-owned menu now reappears only
after a downward drag of at least 24dp starting in the top 28dp touch region.
Tapping or swiping upward does not reveal the menu. The existing Features
setting still defaults to three seconds, with the Never option preserved.
Car host chrome remains host-controlled. Verify both entries on a parked
Android Auto unit; a working APK build does not establish host behavior.

## Real-head-unit feedback: video launcher unresponsive, Music missing skip actions

The video projector is still the AABrowser legacy `CarActivityService` and is
not replaced with a navigation side-panel. Added service and activity-entry
logs for diagnosing launch stages on a real head unit, and applied the upstream
CarActivity theme before superclass initialization. Media session's click
PendingIntent now uses AABrowser's private no-op activity so tapping the music
player cannot hijack the WebView/video route. Video remains separate from the
exported 'AAYouTube Music' media browser.

Android Auto's media UI chooses its visible controls from session
PlaybackState actions. YouTube's per-video canNext/canPrev flags were false in
normal Mix playlists even though 25 tracks were known, hiding the buttons.
For a loaded video, Next/Previous are now advertised; they prefer YouTube's
own handlers but safely fall back to known queue IDs. Published the queue via
MediaSession.setQueue, a queue title, active-item ID and a working skip-to-queue
callback. Pure queue navigation tests cover boundaries and missing items. The
notification's standard Previous/Play-Pause/Next controls now agree with the
media-session capabilities. CI does not prove the video launch failure is fixed:
check CAR logs for service requested, activity resolved and activity launched.

## Log-based media reconnection refinement

The 09:00:00/09:00:37 logs show repeated WebHost OPEN commands for the same
video. The web handler now reuses an existing matching watch-page player
instead of navigating and reloading the decoder. It seeks only if a requested
restore timestamp actually differs, and resumes only if the surviving video
was paused. Android Auto's Native queue is published before evaluating the
available playback actions, and an active queue-item change forces a playback
state refresh even if the position/speed remain unchanged.

- `CarMode.resetToCarScreen()` now emits a phone-side PackageManager
  self-check for the legacy projection service's MAIN + PROJECTION intent filter,
  even when Android Auto never calls the service. Compare the
  `Video projection manifest discoverable=` result to subsequent
  `Android Auto requested legacy projection service` logs after selecting
  the Video icon. This distinguishes a manifest registration issue from a
  projection-host dispatch failure without requiring guesswork.

## Android Auto legacy projection startup crash — verified stack trace

**Device evidence (2026-10-31):** Android Auto successfully discovers
`LegacyProjectionService`, invokes its `getCarActivity()`, then the host
fails before `LegacyProjectionActivity.onCreate()`:
`NoClassDefFoundError: android.support.v4.app.FragmentManager` at
`com.google.android.gms.car.e.<clinit>` / `CarActivityHostImpl.T`.

**Difference from working AABrowser 2.4:** AABrowser uses the identical
pinned `browser/libs/v4/aauto.aar`, **enables Jetifier**, includes
`androidx.appcompat` (and its AndroidX Fragment dependency), and **does not
minify** its release APK. AAYouTube had disabled Jetifier implicitly, omitted
AppCompat, and applied R8/shrinking to release.

**Correction:** enable `android.enableJetifier=true` so Android Gradle's
AAR transform rewrites the archived support-library references; depend on
`androidx.appcompat:appcompat:1.8.0` for Fragment/Activity compatibility;
temporarily disable release code/resource shrinking to match the proven
reference while the projection reflection calls are validated on hardware.
No old `com.android.support` dependency or fake FragmentManager shim is
included, avoiding mixing duplicate framework namespaces.

**Regression:** `LegacyProjectionContractTest` asserts Jetifier,
AppCompat, the pinned projection SDK and no R8 shrinking. The prior
service/host logs will show whether execution reaches
`Video CarActivity launching` with the new APK. Compilation and CI cannot
prove runtime linkage into the proprietary car host: verify with parked-car
video startup and check logcat for this exact missing-class exception.

## Android Auto black screen after legacy crash fixed — virtual-display WebView

Latest device logcat repeatedly says ViewRootImpl display ID 323 while
Chromium InputMethodManager stays on display ID 0. This is not a fatal
exception or sufficient proof of the black screen's cause, but it exposes
a concrete difference from AABrowser: AABrowser creates WebView inside
the car AndroidView context, while AAYouTube constructed a background
WebView against application/phone context, then merely rebased a
MutableContextWrapper when moving to the car.

WebHost now tracks the WebView's creation display ID. On phone/car display
changes, it recreates only the Chromium instance against a context bound
to the actual projected display, preserving the selected YouTube URL,
playlist, position, play/pause intent, cookies and MediaSession ownership.
This costs a short reload instead of reusing a potentially black surface.
It never recreates for a temporary null/unknown display or unchanged ID.
LegacyProjectionActivity also rechecks display context after the frame
attaches to the car ViewRoot, and logs the projection root/player display
IDs and frame dimensions. The phone Compose tree no longer takes ownership
of the WebView while a car projection is active.

Added isolated display-transition tests and lifecycle contract assertions.
Hardware validation remains necessary: WebView rendering could still be
affected by proprietary projection host graphics beyond IME context.

- Car's AndroidView.update no longer detaches the already-parented Chromium
  WebView on every Compose recomposition. WebHost.attach accepts an optional
  destination frame and preserves its hardware Surface if parent is unchanged.

## Black projected video persists — direct display context + attach-stage checks

Hardware test still produced black video; logcat displayed
`InputMethodManager displayId=0` vs `ViewRootImpl displayId=420`.
Android's InputMethodManager source labels the mismatch as a nonfatal
fallback/performance condition. It does not prove the black surface's cause.

A further difference from working AABrowser: AAYouTube's WebView used
`MutableContextWrapper` even after selecting a car display. It could
also be constructed by `AndroidView.factory` *before* the car frame
was attached to its virtual display. The car path now waits for
`onViewAttachedToWindow`, then creates a fresh WebView using the
projected `createDisplayContext` directly (no mutable wrapper).
The standard phone/background lifecycle continues to use the wrapper.

New non-sensitive `[CAR]` diagnostics log the attached frame display,
WebView created display, hardware acceleration, attached/visible state,
native frame dimensions, page readyState and HTML video geometry/decoded
frame count. This distinguishes 0-sized or hidden AndroidView, unloaded
YouTube page, and hardware decoded video surface blackness. These
changes cannot guarantee a fix on an untested Android Auto host.

## Brief video, unsolicited keyboard, then black (14:05 hardware test)

Uploaded Android log `31_10-14-05-26_452.log` records a real
Android Auto projection **stop** at monotonic 1793441086.285:
`CAR.PROJECTION.PPUIHOST ... LegacyProjectionActivity stop()`,
followed by `Removing contentView from parent on UI stop`.
Chromium `onDetachedFromWindow` and the Exynos VP9 codec surface releases
occur immediately afterward. The repeated
`InputMethodManager displayId=0 / ViewRoot displayId=578` traces
are nonfatal diagnostics. Later `CAR.CLIENT` warns that an optional
`CarDisplayInfoManager` class isn't present while the host reinitializes;
that warning follows projection stop, not proven as its trigger.

AABrowser asks `CarInputManager.startInput(view)` only after explicit
keyboard choice. AAYouTube's `LegacyCarInput.bind()` previously called
`manager.startInput(this)` when the *entire WebView* received focus
at car activity startup, even with only video active. The user's brief
visible video and unwanted keyboard make that a concrete lifecycle risk.

**Correction:** WebView/car focus no longer starts car text input.
`LegacyCarInput` requests car IME only after a real user ACTION_UP on an
HTML input/textarea/contenteditable field (including shadow-root focus);
never for video/player touches. It stops a previously activated IME on
subsequent non-edit touches and cleans up touch listeners on shutdown.
The projected frame takes initial focus instead of the WebView. Explicit
`onStart/onResume/onPause/onStop/onDestroy` markers make it possible
to distinguish host-initiated projection closure from a renderer glitch.
No old auto-focus keyboard behavior is retained. Actual on-head-unit
verification is still needed: the log does not prove the car host's
reason for calling stop().

## Video visible ~1 s, then black with audio — replaced car window (14:37 hardware test)

Uploaded logcat `31_10-14-37-54_991.log` (legacy projection, the first video path):

- `22.533`: the WebView is already attached to a car `ViewRootImpl` on display 620;
  VP9 decoder `#449` started at `21.1`.
- `23.580–23.702`: Android Auto hands over a **new Surface** and the car activity
  is set up again on the **same display**: `PPUIHOST setup(spec)` ×2, a new
  Presentation, our `onStart` (`MediaBrowserCompat: Connecting`),
  `CSL.MenuController onRestoreInstanceState`, `show()`, then a new window
  `VRI[LegacyProjectionActivity]#4`.
- The decoder is never released until `34.950` (`onStop → WebHost.detach`), and
  audio keeps playing. Because the display ID is unchanged, `PlayerDisplayPolicy`
  kept the renderer and the live WebView was simply **moved into the replacement
  window**. That is the screen that stays black.
- The video stays at 256×144 for the whole session: background "tiny" quality. Every
  instance used the same `"legacy-projection"` owner key, so the old instance's
  `onStop/onDestroy` could clear the new instance's projection (`carShown=false`,
  car layout off, `setBg(true)`) and bump `webVersion` under it.
- Three host `stop()` calls at the end (`35.014`, `35.270` ×2) suggest the old
  and new car instances were both still alive.

AABrowser never moves a live WebView: each `MainCarActivity` window creates its
own WebView in its Compose factory and destroys it with the window.

**Correction**

- `LegacyProjectionActivity` has a per-instance owner token. `WebHost` keeps a
  `CarHostRegistry`: the latest claimed (`onCreate`/`onStart`) instance is the only
  one allowed to bind the player or show the fullscreen view. An old instance's
  teardown releases only its own token, so the newer window keeps car layout,
  foreground quality and the WebView (`requestReattachment` stays a no-op).
- `WebHost.attachToCarWindow()` records the window token the renderer draws into.
  A bind into a **different car window** (or display) builds a fresh renderer at
  the same position and play intent (`aay=1` also when the old window's teardown
  paused the page < 8 s earlier, unless the user paused). This matches AABrowser.
  Re-binding inside the same window keeps the decoder.
- The phone screen no longer reparents or rotates for YouTube's fullscreen view
  while the car projects it. An older car input bridge no longer removes the
  newer instance's touch listener.
- Diagnostics: `[CAR]` lines are mirrored to logcat under tag `AAYouTube-CAR`
  (no URLs/titles), so a plain logcat shows host claims, `Car WebView bind …
  renderer=kept|new(window|display)` and render probes at 2/6/12 s. The probes add
  decoded/dropped frames, current time, the real page visibility, the `aay-car`
  class and the element painted at the screen centre.

Expected on device: video, then a short reload (≈1–2 s, same position) when
Android Auto replaces the car window, then video again. Not proven by CI: confirm on
a parked head unit and send `adb logcat -s AAYouTube-CAR` if the screen stays black.

## Follow-up audit: late old-activity onStart owner race

Commit `851be754` already handled the observed 14:37 same-display
window replacement and per-instance teardown. A code audit found a
separate lifecycle ordering race: both `onCreate` and `onStart`
called `CarHostRegistry.claim(token)`, which previously removed and
reinserted *existing* tokens, making a delayed `onStart` on the older
activity steal the newer car window's active ownership.

The registry now treats `claim` as an idempotent registration and
`resume` as a non-promoting onStart refresh; a previously stopped host
can register again only when no newer car host is registered.
`LegacyProjectionActivity.onStart` touches shared WebHost playback,
media callbacks and car projection only when the host is active. The
new activity takes `requestLink` immediately in `onCreate`, preventing
an old instance's teardown from restoring a phone callback before the
new host starts. Unit tests cover delayed onStart, duplicate claims,
stopped-host reentry and last-host resume. These guard against another
black-screen ownership race but do not substitute for head-unit tests.

- Restored-host callback now performs the full active-host recovery
  (projection state, full layout, playback hold, media-process callback,
  media connection and WebView bind) rather than only reparenting a
  WebView. An old instance's late onStart returns before shared
  projection or media work when another instance is active.

## Car home works, video page black — car CSS wrapper boxes over the video (19:25 test)

After the per-window renderer fix the car screen shows YouTube Home and browsing
(screenshot, 19:24). Opening a video turns the screen black while the video plays
and touches still work. In `31_10-19-25-05_357.log` the 2 s probe shows Home
painting. The 6 s probe on the watch page shows `hasVideo:true`, 256×144,
`frames:28`, `paused:false`, `vis:"visible"`, `hidden:false`, `car:true`,
`bounds:[975,696]` (the whole viewport), and the top element
`div.player-controls-background`. So the renderer, the decoder and the `<video>`
element are all fine. The black comes from the page layer.

AABrowser does not restyle YouTube's player. AAYouTube's `CSS_CAR` gives a
dozen player-like wrappers `position:fixed; inset:0; z-index:2147483000;
background:#000`, including m.youtube.com's flow spacers `.player-placeholder` /
`.player-size`. Those come after the player in the DOM and hold no video. At the
same z-index, a later sibling paints above the player: a full-screen black box
under YouTube's controls.

**Correction**

- `carGuard()` (run on every car-layout tick, because YouTube adds wrappers late)
  marks each matched wrapper. One that contains the `<video>` keeps the car box.
  One holding only `#player-control-container` loses its background
  (`aay-car-clear`). One holding neither is hidden and has no z-index
  (`aay-car-off`). Video ancestors stay `visible`. The guard rules use
  `:not(#id)` specificity so they also win over the `#player-container-id` rules.
  All marks are removed when the car layout ends.
- New `Chromium paint probe` (logcat `AAYouTube-CAR`). It lists the hit-test stack
  at the screen centre down to the `<video>` (position, z-index, opacity,
  visibility, background) and any full-screen opaque fixed/absolute box that does
  not contain the video (this catches `pointer-events:none` covers), plus the
  number of guarded wrappers. Probes now also run 3 s and 9 s after the car page
  opens a video.
- JS tests (m.youtube.com-like DOM): the spacer is neutralised, controls-only
  wrapper cleared, ancestors visible, marks removed on exit, phone untouched.

If a video is still black on the car, the paint probe now tells the remaining
cases apart. A `covers=`/stack entry above the `<video>` means another page
overlay. A clean stack while frames advance means Chromium composited the video
outside the car window (an Android video overlay on the virtual display). That
case needs a compositing fix, not CSS.
