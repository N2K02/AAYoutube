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
  Playback snapshots carry a connection epoch: stale/disconnected messages
  cannot overwrite the current media session after a new connection.
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
