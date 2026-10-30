# Media3 to AABrowser MediaCompat migration

The upstream media implementation is MediaBrowserServiceCompat with MediaSessionCompat.
AAYouTube currently has a Media3 library service plus a two-process Messenger bridge.

The non-exported CompatPlaybackService foundation provides the upstream media-browser
API and media-session lifecycle without replacing the active playback session yet.
It intentionally does not claim to implement playback controls: those callbacks
must be connected to HostLink, and existing phone/car clients must migrate from
MediaController before Media3 is removed.

Remaining cutover work:
- Connect MediaSessionCompat callbacks and state/metadata to HostLink and Snap.
- Implement recent/favourite browse results, search, playFromMediaId and voice.
- Replace Media3 MediaController clients in MainActivity and LegacyProjectionActivity.
- Replace the media-button receiver, notification, and manifest service registration.
- Remove BridgePlayer and Media3 dependency, migrate tests, verify APK/Android Auto.
