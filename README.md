# AAYoutube

A personal Android app that plays YouTube in a WebView, with Android Auto support. Material 3 Expressive only.

## Features
- Ad blocking, SponsorBlock, Return YouTube Dislike, hide Shorts, background play, picture in picture (best-effort; they depend on YouTube's website).
- Android Auto: audio only, with Recently played, Favourites, voice and text search.
- Update tab: checks GitHub releases and installs the new APK.

## Design
Two processes keep the car safe from WebView trouble:
- main process: media service answering Android Auto from memory. No WebView.
- `:web` process: the screens and the single WebView. If it dies, playback shows paused and Play reopens the last video.

Battery: wake lock only while playing, lowest video quality when the screen is hidden, page frozen when idle, everything released after 15 minutes paused.

## Android Auto
Enable developer mode in Android Auto, then turn on "Unknown sources".

## Releases
Every push to `main` builds a signed APK and publishes `v1.0`, `v1.1`, ... Needs the `SIGNING_PASSPHRASE` secret.


## Official download and project links

Android YouTube WebView player with Android Auto audio playback, favourites, search, and playback features.

- **Latest Android APK:** [Download the latest release](https://github.com/N2K02/AAYoutube/releases/latest)
- **All releases and version history:** [GitHub Releases](https://github.com/N2K02/AAYoutube/releases)
- **Source code and issue reporting:** [AAYoutube repository](https://github.com/N2K02/AAYoutube)

The APK is distributed through GitHub Releases. If no release is available, check the repository's Releases page for future published builds. Google indexing is not guaranteed; this section provides clear crawlable links for visitors and search engines.
