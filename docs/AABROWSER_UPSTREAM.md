# AABrowser as the upstream Android Auto reference

Reference repository: https://github.com/fcaronte/AABrowser

The user chose this source as the canonical reference for future **AAYouTube Android Auto** updates. Compare against actual upstream source before implementing changes. Do not invent an alternative implementation when an equivalent upstream mechanism exists.

## Scope to track
- Classic Android Auto projection (`CarActivityService` / `CarActivity`, upstream `browser/libs/v4/aauto.aar`).
- Widescreen navigation template, SurfaceCallback, virtual display reattachment, host visible/stable areas and touch input.
- Media browser entry, metadata/media actions/voice searches, WebView background-play integration, reconnection and audio-focus handling.
- YouTube-specific browsing, playback, seeking and video rendering.
- Reverse-gear-related audio interruptions **only as substantiated in upstream code**; AABrowser README advertises this, but the inspected `CarMediaService` registers a no-op audio focus listener and no separate gear-event sensor was found. Do not claim a special reverse gear API without inspecting and citing implementation.

## Explicit exclusions
Do not add AABrowser's multi-tab browser, Spotify integration, local media player or weather interface.

## Binary dependency safety
The upstream repo is MIT licensed but third-party bundled SDK binaries may have separate terms. The legacy SDK source must be reviewed for redistribution rights; pinned binary use is for experimental evaluation, not an assertion that Google officially supports it. Always pin upstream commit and verify Git blob SHA; do not silently substitute third-party SDK versions.

## Workflow
Reuse the one open AAYouTube experimental pull request. Add comprehensive business-logic and lifecycle tests including Quit/shutdown; CI should pass before merge. Keep phone WebView, media process, car video and media metadata behavior coherent.

## Implemented legacy projection integration

AAYouTube now registers `LegacyProjectionService : CarActivityService` for the exact
`CATEGORY_PROJECTION` and `CATEGORY_PROJECTION_OEM` actions used by upstream
`browser/src/main/AndroidManifest.xml`. It returns
`LegacyProjectionActivity : CarActivity`, following upstream
`browser/src/main/java/com/fcaronte/aabrowser/CarService.kt` and
`MainCarActivity.kt`. The car activity provides Compose lifecycle, ViewModel
and saved-state owners as upstream does, attaches **AAYouTube's existing WebView**,
and connects to its current Media3 service instead of AABrowser's media service.
The virtual-display navigation-template backup remains independent.

The projection activity must not be inserted as a regular Android manifest activity:
it is instantiated by the SDK service. The pinned `aauto.aar` is already fetched
and checked by `app/build.gradle.kts`; these service/activity classes are the
previously missing runtime use of it.

Regression tests check both projection categories, owner lifecycles, WebView and
Media3 handoff, teardown/reattachment, SDK pin and no unrelated AABrowser apps.

**Not yet established:** compatibility with each Android Auto host, car video
rendering on a specific vehicle, or permission to redistribute Google's bundled
proprietary SDK. Keep the upstream artifact in an experimental build and validate
on a stationary device/test head unit first.
