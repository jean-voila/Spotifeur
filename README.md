# Spotifeur

<p align="center"><img src="docs/assets/img/banner.png" alt="Spotifeur — open-source music player for Android for Spotify, YouTube Music and local files, with a preview of the player" width="1200"></p>

[Download APK](https://github.com/jean-voila/Spotifeur/releases/latest) · [Website](https://jean-voila.github.io/Spotifeur/) · [FAQ](docs/FAQ.md) · [Build from source](#build-and-contribute) · [Protocol notes](protocol/)

[![CI](https://github.com/jean-voila/Spotifeur/actions/workflows/build.yml/badge.svg)](https://github.com/jean-voila/Spotifeur/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/jean-voila/Spotifeur?color=0f9d63)](https://github.com/jean-voila/Spotifeur/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/jean-voila/Spotifeur/total?color=0f9d63)](https://github.com/jean-voila/Spotifeur/releases/latest)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-0f9d63)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-0f9d63)](#install-in-three-steps)

Spotifeur is an open-source **music player for Android**. It plays your Spotify
library with Spotify's own Android protocols and audio engine, the YouTube Music
catalog with or without a Google account, and audio files on your phone. A Spotify
account is optional. The interface uses Material 3 Expressive and is available
in English and French.

## What it looks like

![Spotifeur home screen, player, synced lyrics and queue in illustrated phone frames](docs/assets/img/preview-listening.png)

![Spotifeur album page, playlist, settings and appearance options in illustrated phone frames](docs/assets/img/preview-library.png)

These are screenshots from the app, also used on the website. The phone frames
are illustrations. Editable artwork is in [`design/`](design/).

## Features

- **Spotify, YouTube Music and local files.** Search, play and combine tracks in
  playlists. Import playlist links or CSV files, and export playlists as CSV.
- **Library and playlists.** Saved tracks, albums, artists and Spotify playlists
  sync with your account. Create and edit playlists, including their covers.
- **Player, queue and lyrics.** Seek, shuffle, repeat, sleep timer, synced lyrics,
  a queue you can reorder and listening history across all three sources.
- **Offline playback.** Download tracks, albums and playlists. Adjust the audio
  cache size, prefetch depth and quality for Wi-Fi, mobile data and downloads.
- **Appearance and controls.** Light and dark themes, album colors, player styles,
  configurable home shelves, gestures and an equalizer.
- **Application updates.** Checks GitHub releases at startup, shows release notes,
  downloads the APK and opens Android's installation confirmation. Dismissed updates
  are offered again at startup after 24 hours; manual checks are always available.
- **Music only.** No podcasts, audiobooks, videos, Canvas loops or promoted content.

<details>
<summary><b>All features and settings</b></summary>

- **Home and library.** Pinned items, recently played tracks, downloads, liked songs, playlists, albums, artists and local files. Each home shelf can be shown or hidden, moved, and laid out as a row, a large row, a grid, a list or pills, with the number of items you choose. The buttons of album and playlist pages can be arranged as well, the others staying in a ⋮ menu. Pins are stored on the device only.
- **Player.** Full-screen player with seek bar, queue, shuffle, repeat, sleep timer, a ⋮ button for the options of the current track (can be hidden), and the codec and bitrate of the current track if you want them. Swiping the mini player left or right changes track (it can also close the player, or do nothing).
- **Audio quality.** Separate settings for Wi-Fi, mobile data and downloads: Normal (AAC 128 kbit/s), Very high (up to AAC 256 kbit/s) or Automatic. Playback and downloads use the quality authorized by the Spotify account: 128 kbit/s for Free, up to 256 kbit/s for Premium. Optional volume normalization from the loudness Spotify and YouTube Music publish for each track.
- **Listening history.** Tracks played in Spotifeur from Spotify, YouTube Music and local files, together with what Spotify reports from your other apps and devices, grouped by day.
- **Lyrics.** Synced lyrics from Spotify, with an immersive mode. When Spotify has none, Spotifeur can look them up on LRCLIB.
- **Queue.** Reorder by dragging, swipe to remove, optional history of played tracks. The tracks you queued are shown apart from the rest of the list.
- **Playlists.** Create a playlist on your Spotify account, rename it, set a cover from your photos, publish it on your profile, add Spotify's recommendations and remove tracks. Multi-service playlists are stored on the device, take a cover from your photos and can mix Spotify, YouTube Music and local tracks, with a badge showing each track's source. Local files can be added to Spotify playlists as Spotify's own local file references, and Spotify playlists that refer to local files play them from the phone's storage. Long-press a playlist to delete it.
- **Converter.** Convert a playlist between Spotify, YouTube Music and multi-service playlists, in every direction (playlist page → ⋮ → Convert to…). Matches on the destination appear as they are found, uncertain ones are highlighted, and each track can be replaced by another search result or left out before the new playlist is created.
- **Import and export.** Export a playlist as CSV, and import a CSV exported by Spotifeur or a YouTube, YouTube Music or Spotify playlist link.
- **YouTube Music.** Search and play the YouTube Music catalog without a Google account, with the same downloads, cache, queue and playlists as Spotify tracks. Without a Spotify account, Spotify tracks play from their closest YouTube Music match (title, artist, album and duration).
- **Google account.** Connect a Google account in Settings → Accounts to see your YouTube Music playlists in the library, create playlists on the account, rename them, make them public or private, add and remove tracks, and delete them. Audio still plays without the account.
- **Liked YouTube Music tracks.** YouTube Music tracks can be liked like Spotify ones. They appear at the top of Liked songs, and are also liked on YouTube Music when a Google account is connected.
- **Albums and artists.** Album pages, and artist pages with monthly listeners, popular tracks and their play counts, popular releases, albums, singles and EPs, compilations, appearances, related artists and the biography. YouTube Music tracks open YouTube Music's album and artist pages. Radios based on a track, an album, an artist or a playlist.
- **Swipe on tracks.** Swiping a track right adds it to the queue. Each direction can be set to add to the queue, play next, save to Liked songs, remove from the playlist, or nothing.
- **Search.** One search across Spotify, YouTube Music and the device's own files, for tracks, albums, artists and playlists, most relevant first, shown as each source answers, with paging. A setting shows a choice to search one source alone. Recent searches list what you opened or played. Each playlist has its own search by title, artist or album, above its first track.
- **Offline.** Download albums, playlists or tracks. Recently played and upcoming tracks are kept in a cache, encrypted for Spotify (512 MB to 32 GB, up to 20 tracks ahead). An offline mode cuts all network access from the app.
- **Background playback.** Media notification, lock screen and Bluetooth controls, resume when headphones reconnect.
- **Equalizer.** Presets, custom presets, bass boost, virtualizer and loudness.
- **Appearance.** Light, dark or system theme, dynamic colors from the album art with several palette styles, floating or full-width navigation bar, home collage patterns, carousel style, blur on or off, smooth corners, compact lists, English or French.
- **Cast.** Google Cast for local files.
- **Backup.** Export and import your settings, pins and multi-service playlists.
- **Diagnostic report.** Recent errors and the account's plan entitlements, without usernames or identifiers, to paste into a bug report.

</details>

### Compared with the official app

| | Official Spotify app | Spotifeur |
| --- | --- | --- |
| Your library, playlists, saved tracks, albums and artists | Yes | Yes, same account, synced both ways |
| Full-length playback, seek, queue, shuffle, repeat | Yes | Yes, with Spotify's own audio engine |
| Search, radios, lyrics, playlist recommendations | Yes | Yes |
| Create and edit playlists, custom covers | Yes | Yes |
| Offline downloads and audio cache | Yes | Yes, with an adjustable cache size and prefetch depth |
| Podcasts, audiobooks, videos | Yes | No |
| Canvas loops, promoted content, ads banners | Yes | No |
| YouTube Music and local files, in the same playlists | No | Yes |
| YouTube Music playlists of a Google account | No | Yes |
| Convert playlists between Spotify and YouTube Music | No | Yes |
| Use without a Spotify account | No | Yes, with YouTube Music and local files |
| Themes, player styles, navigation bar, equalizer, gestures | Few options | Many options |

Spotifeur uses the same services as the official Android app, so what you save, follow or edit in one shows up in the other.

## Install in three steps

1. **Download.** Get `Spotifeur-vX.Y.Z.apk` from the
   [latest release](https://github.com/jean-voila/Spotifeur/releases/latest) and
   check its SHA-256 against the release notes.
2. **Install.** Open the APK on an ARM64 phone with Android 8.0 or later.
3. **Set up.** Sign in with your Spotify email address and the code Spotify sends
   you, or with the Google account linked to your Spotify account, or continue
   without an account for YouTube Music and local files.

See the [FAQ](docs/FAQ.md) for account, playback and installation details.

## Technical details

<details>
<summary><b>Protocols and audio engine</b></summary>

For Spotify, Spotifeur does not use the public Web API, App Remote, a WebView or the installed Spotify app. It talks to Spotify the way the official Android app does:

- **Sign-in** with Login5 and ClientToken, using a code sent to your email address (or a password). The session is encrypted with Android Keystore.
- **Library and catalog** through Playlist4, Collection v2, Extended Metadata and the search service.
- **Audio** through HLS manifests processed by Spotify's own native engine, loaded unchanged from the official APK you supply at build time.

YouTube Music goes through the anonymous InnerTube requests of music.youtube.com for search and playlists, and through the player endpoint for audio streams, read in byte ranges. With a Google account, the library and playlist edits use the same InnerTube requests as the music.youtube.com web client, with the cookies left by Google's sign-in page and its `SAPISIDHASH` authorization; audio requests stay anonymous. This part is adapted from [Resona](https://github.com/code-saksham-hash/Resona). Without a Spotify account, a Spotify playlist link is read from Spotify's public embed page, which lists its first 100 tracks.

Everything learned along the way is documented in [`protocol/`](protocol/), as a reference you can read without the code.

Spotify downloads stay encrypted and are processed by Spotify's engine at playback
time. Connections go directly from the app to the services. The build uses the
official Spotify APK you supply.

</details>

<details>
<summary><b>Account and privacy</b></summary>

- There is no Spotifeur server, no analytics and no tracking. The app talks to Spotify, to YouTube Music, and to [LRCLIB](https://lrclib.net) for lyrics Spotify does not have (title, artist, album and duration only; you can turn it off).
- Your password is never stored. The email-code sign-in does not use one at all. Signing in to Spotify with Google happens on Google's page; Spotifeur only passes the one-time code it returns to Spotify and keeps nothing else from it.
- The session credential stays on your device, encrypted with Android Keystore.
- A Google account is signed in on Google's own page. Spotifeur keeps the cookies it leaves, encrypted with Android Keystore, and only sends them to music.youtube.com.
- Each release lists the SHA-256 of the APK and of its signing certificate. You can also build the APK yourself from this source and your own copy of the official Spotify APK.

</details>

## Documentation

- [Protocol notes](protocol/): ClientToken, Login5, catalog, library, playlists, audio delivery, native engine, offline, Spotify Connect
- [Native runtime](native-runtime/README.md)
- [FAQ](docs/FAQ.md)
- [Privacy](docs/PRIVACY.md)
- [Contributing](CONTRIBUTING.md)
- [Code of conduct](CODE_OF_CONDUCT.md)
- [Security policy](SECURITY.md)

## Build and contribute

<a id="build-from-source"></a>

Requirements: JDK 21, Android SDK with platform 37, an ARM64 Android device (Android 8.0 or later), and the official Spotify Android **APKM, version 9.2.0.26** (build 148373686). The build checks the SHA-256 of the files it extracts and stops if they differ.

```sh
git clone https://github.com/jean-voila/Spotifeur.git
cd Spotifeur
./gradlew :app:assembleDebug -PspotifyArtifact=/path/to/com.spotify.music_9.2.0.26.apkm
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You can also place the APKM at `native-runtime/artifacts/spotify-9.2.0.26.apkm`. Nothing is downloaded automatically. See [`native-runtime/README.md`](native-runtime/README.md) for details.

Without the APKM, `-PnativeRuntime=false` builds the app and runs the tests, but playback stops after the first seconds of each track. The [CI](.github/workflows/build.yml) uses this mode.

```sh
./gradlew -PnativeRuntime=false :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

A build you make yourself is signed with your own key: uninstall a release build first (this erases the session and downloads).

See [contributing](CONTRIBUTING.md), [issues](https://github.com/jean-voila/Spotifeur/issues)
and [security reporting](SECURITY.md). The [CI](.github/workflows/build.yml) builds
the app without the native runtime and runs unit tests and lint.

## Support

[GitHub Sponsors](https://github.com/sponsors/jean-voila) · [Buy me a coffee](https://buymeacoffee.com/jean_voila)

## Credits and license

- [librespot](https://github.com/librespot-org/librespot) for its public work on Spotify's protocols.
- [Resona](https://github.com/code-saksham-hash/Resona) for its YouTube Music catalog and stream access.
- [AndroidX Media3](https://github.com/androidx/media), [Jetpack Compose](https://developer.android.com/compose), [OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/) and [LRCLIB](https://lrclib.net).

[GNU General Public License v3.0](LICENSE).
