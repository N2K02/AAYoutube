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
