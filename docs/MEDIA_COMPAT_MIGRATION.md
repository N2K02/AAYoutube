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
