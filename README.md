# AAYoutube

A personal Android app that plays YouTube in a WebView, with Android Auto support. Material 3 Expressive only.

## Features
- Ad blocking, SponsorBlock, Return YouTube Dislike, hide Shorts, background play, picture in picture (best-effort; they depend on YouTube's website).
- Android Auto: independent native Music player (Recently played, Favourites, voice/text search), plus experimental parked Video car-launcher discovery using the existing WebView.
- Update tab: checks GitHub releases and installs the new APK.

## Design
Two processes keep the car safe from WebView trouble:
- main process: media service answering Android Auto from memory. No WebView.
- `:web` process: the screens and the single WebView. If it dies, playback shows paused and Play reopens the last video.

Battery: wake lock only while playing, lowest video quality when the screen is hidden, page frozen when idle, everything released after 15 minutes paused.

## Android Auto
Experimental PR #47 registers the existing `MainActivity` directly using AABrowser-style `CAR_LAUNCHER`, `APP_MAPS`, and navigation intent categories, and uses the media-only automotive descriptor. There is no separate launcher alias or AndroidX `CarAppService`. The previous legacy projection service remains as a fallback during this single-build compatibility evaluation; the native Music service remains unchanged.

This does **not** establish that a browser/video app is a navigation app or that Android Auto 18 will accept the entry. The car host may filter unsupported categories regardless of installer source. Video is intended only for parked use. Test on a real head unit; never bypass host driving restrictions.

For diagnosis, check the Logs screen for `Video MainActivity car-launcher discoverable=` and `Video MainActivity maps discoverable=`. Those checks prove package-manager matching on the phone, not host launcher acceptance.

## Releases
Every push to `main` builds a signed APK and publishes `v1.0`, `v1.1`, ... Needs the `SIGNING_PASSPHRASE` secret.


## Official download and project links

Android YouTube WebView player with Android Auto audio playback, favourites, search, and playback features.

- **Latest Android APK:** [Download the latest release](https://github.com/N2K02/AAYoutube/releases/latest)
- **All releases and version history:** [GitHub Releases](https://github.com/N2K02/AAYoutube/releases)
- **Source code and issue reporting:** [AAYoutube repository](https://github.com/N2K02/AAYoutube)

The APK is distributed through GitHub Releases. If no release is available, check the repository's Releases page for future published builds. Google indexing is not guaranteed; this section provides clear crawlable links for visitors and search engines.
