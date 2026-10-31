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
