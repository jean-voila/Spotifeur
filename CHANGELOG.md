# Changelog

## Unreleased

Nothing yet.

## v1.5.0

### Added

- Sign in to Spotify with Google. The button opens Google's authorization page for Spotify's own Google client (the one `accounts.spotify.com` and the 9.2 app use) in a WebView, asking for a code and an ID token (`response_type=code id_token`, with a state and a nonce). The redirect back to `accounts.spotify.com/login/google/redirect` is stopped before it loads, and Login5 gets `GoogleSignInCredential` (field 108): the ID token alone first (v4 field 3, as the app's Credential Manager path), then the code with its redirect address (fields 1 and 2), as the app's Google Sign-In path. The WebView's user agent no longer announces a WebView, which Google refuses for its sign-in, and its cookies are cleared afterwards. A Google account that is not linked to a Spotify account gets a plain message (Login5 error 5). Checked live: made-up codes and tokens are refused as invalid credentials (error 1), and a real Google account no longer linked to Spotify gets error 5. A sign-in with a linked account was not run before this release.
- Liking a YouTube Music track works. The heart in the player did nothing for these tracks and the track menu did not show it. Spotify's liked songs cannot hold them, so they are kept on the device and listed at the top of Liked songs (with source marks in that list, the count includes them); with a Google account connected they are also liked on YouTube Music (`like/like`, `like/removelike`). Without a Spotify account, Liked songs shows them alone.
- Recent searches show where each result comes from (Spotify, YouTube Music or a device file), like the result list.
- The popular tracks of an artist page show their covers.
- Crashes go to the diagnostic report: an uncaught exception with the top of its stack (class and method names, no addresses or identifiers), and, at the next start, native crashes and ANRs that Android recorded for the app (`ApplicationExitInfo`).

### Fixed

- Monthly listeners were wrong or missing. The count came first from extension 185 field 1, which is not the monthly listeners on every artist (415 for an artist with 27,382); it now comes from `creatorabout` only, with `globalChartPosition` as the world rank. An artist page opened by the user is read again after an hour instead of a week, so the figures stay current; the background sync keeps its weekly pace.
- Search showed the YouTube Music version of an artist (or album, or track) when YouTube Music ranked it higher, so its page had no Spotify figures, like or radio. A result both services have is now shown at the better rank of the two, as Spotify's version.
- YouTube Music conversion and pages after a burst of requests: during a long conversion YouTube Music started answering HTTP 403 (an HTML page, no reason) to every request from the connection, anonymous or signed in, for a while. The searches kept retrying into the block, which made the conversion slow, and creating the playlist at the end then failed with 403; artist and album pages failed the same way. Now a refused catalogue request is retried once with a fresh anonymous identity; if it is refused again, YouTube Music is left alone for 15 s (doubling up to 2 min) and every lookup waits for that instead of failing. Account changes (create, add, edit, like) are tried again after 4 and 12 s. If YouTube Music still refuses, the converter says so in plain words and the playlist can be created again from the same screen. The YouTube Music conversion runs four searches at a time instead of three.
- Listening history showed the same play twice or more when the queue was rebuilt (shuffle, a new queue around the same track, the app restarted while playing). A play is no longer recorded when the newest entry is the same track started less than 90% of its length earlier (30 s minimum), and repeats already stored are merged when the history is shown.
- Playlists without their tracks on some Free accounts: Extended Metadata over HTTP answered 200 with every entity refused (statuses 502 and 403, reported in two diagnostic reports). When most of a batch stays without data after the `catalogue` retry, the entities are now read through the native core's `ClassicMetadataService/MultigetEntity`, the path the official app's pages use (the engine sends its own request to the regional spclient host). The library sync stops asking for metadata for 2 min (doubling up to 30 min) after such a refusal instead of requesting thousands of saved tracks into it; what the user opens still goes through.
- The play button of the Liked songs card crashed the app when the count was known but no track had loaded yet; it now opens the list.
- A native playlist read that failed was reported as `InvocationTargetException "null"`; the engine's own error is reported now.

### Changed

- The native HTTP layer logs the shape of the engine's metadata requests (route and field numbers, no content) to logcat.

## v1.4.0

### Added

- Search reads every source: Spotify (with an account), YouTube Music and the device's files, for tracks, albums, artists and playlists, in one list ordered by relevance (how closely the name matches, then each service's own rank). YouTube Music contributes its unfiltered search (top result, songs, videos, albums, artists, playlists; episodes, podcasts and profiles are left out) plus its songs shelf for paging. A track, album or artist found on both Spotify and YouTube Music is shown once. The kind filters work for every source. Settings → Behavior → Choose the search source shows All, Spotify, YouTube and Device above the results (hidden by default).
- Results appear as each source answers instead of after the slowest one, and what is on screen stays while the next text is searched. The previous YouTube Music search also ran a videos search after the songs one; measured on the phone, that second request took 4 to 6 s or failed, and the whole search waited 6 to 9 s for it. It is gone (the unfiltered search already lists videos): every source now answers in 0.3 to 0.55 s after a 250 ms pause in typing.
- In the queue, the tracks you queued are under "Next in queue", separated from the rest of the list being played ("Next from: …").
- A long press on any track row opens its ⋮ menu (artist page rows did nothing).

- Volume normalization (Settings → Playback, off by default). Spotify tracks use the `loudness_db` and `true_peak_db` of their AUDIO_FILES extension (field 2, floats, now kept in the audio index), YouTube Music tracks the `loudnessDb` of their player response. The target is −14 LUFS with 1 dB of headroom under the true peak. The gain is applied as the output volume of the track being heard (a `ForwardingAudioSink` that multiplies the player volume), so it only lowers loud tracks; local files are unchanged. Tracks indexed before this version read their loudness once more.
- A ⋮ button at the top right of the full player opens the options of the current track (like, queue, album, artist, radio, share). Settings → Appearance → Now playing can hide it.
- Swiping the mini player changes track: left plays the next one, right the previous one, the card leaving and coming back from the other side. Settings → Behavior → Swipe on the mini player can bring back closing the player (the previous behavior) or turn the swipe off. Requested in issue #7.
- Artist pages show what the official ones show: monthly listeners (`ON_PLATFORM_REPUTATION_TRAIT`, extension 185, field 1) and the play counts of popular tracks (the same extension on each track, field 3), popular releases (152), albums, singles and EPs, compilations and "appears on" as separate sections with "Show all", related artists (from metadata, or extension 165), and an About card with the header picture, world rank and biography (`creatorabout/v0`). The release groups of artist metadata only carry album IDs; they are now hydrated (60 per group, 20 for "appears on"), so artist pages list albums again. The library sync stores artist pages without these requests; the page completes itself when opened.
- Search history lists the albums, artists, playlists and tracks opened or played from search, with their picture and kind, instead of typed text. Typed queries kept by earlier versions are dropped; backups keep the new entries.
- Search includes the device's audio files, matched on title, artist and album.
- Search inside a playlist (Liked songs included): pull down at the top of the list to reveal the field. It matches title, artists and album, ignores case and accents, and reads the rest of the playlist while filtering. Plays and removals apply to the rows shown.
- YouTube Music album and artist pages (InnerTube `browse` on `MPREb_…` and `UC…`), opened from a track menu or the player. The IDs come from search rows, then the `next` endpoint, then a search for the track.

### Fixed

- Album and artist entries of a track menu did nothing without a Spotify account (issue #8): they were only enabled for Spotify tracks with an account. YouTube Music tracks, and Spotify tracks played through their YouTube Music match, now open YouTube Music's pages.
- Google sign-in for YouTube Music (issue #4): the check of a new session (`account/account_menu`) could get HTTP 403 right after Google's page, and the account was then not saved. It is now tried again after 2.5 s, then without the brand-account delegation and without the page's visitor identity; a stored delegation refused later is dropped. The status and reason Google gives with a 4xx go to the diagnostic report. On the phone, the same cookies that got 403 during the sign-in opened the account a few minutes later. Before that, the sign-in also only saved the account when the WebView landed on music.youtube.com. When Google ended on www.youtube.com or m.youtube.com, the YouTube page stayed open and nothing was saved. The sign-in now continues to music.youtube.com once the session cookies exist, and leaving the page with the back button after signing in keeps the account. `accounts.youtube.com` and the consent page are never interrupted.
- Converting a long playlist to YouTube Music stopped with "YouTube Music refused the change (STATUS_FAILED)" after the first hundred tracks, leaving a partial playlist on the account. `browse/edit_playlist` refuses a whole batch when one video is already in the playlist unless each action carries `dedupeOption: DEDUPE_OPTION_SKIP`. Batches now carry it; a batch refused anyway is split until the refused videos are isolated, the others are added, and the count is shown. Tracks are added after creating an empty playlist, which is deleted again if filling it fails. Reproduced on a 1,655-track playlist.
- Converter searches that fail (network, rate limits) are tried three times with growing waits before the row asks for a choice. Failures are recorded in the diagnostic report.
- A Spotify playlist answered with HTTP 400 by Playlist4 over HTTP is read through the native core (`PlaylistDataService/Get`), as the official app reads it, then through the public embed page for its first page. Reported on a Free account; not reproduced on the accounts available here.
- A playlist or Liked songs page whose track count is known but whose tracks did not load says so and offers a retry, instead of "This playlist is empty".
- Extended Metadata batches that come back mostly without data are recorded in the diagnostic report (provider and entity statuses only) and asked again with the account's `catalogue` in the request header.

### Changed

- Spotify password sign-in uses Login5 v4 (`NativePassword`, field 101, with a flow ID), as the 9.2 app does, and falls back to v3 only if v4 is refused as a protocol. A challenge Spotifeur cannot show (interaction, reCAPTCHA) is reported with a suggestion to use the email code. Requested in issue #1.

## v1.3.0

### Added

- Converter: playlist page → ⋮ → Convert to… turns a Spotify playlist (Liked songs included), a YouTube Music playlist or a multi-service playlist into one of the two other kinds. Tracks already from the destination are kept; the others are looked up on the destination (two Spotify searches or three YouTube Music searches at a time, YouTube Music videos only when no song is close) and scored like the YouTube Music matching of Spotify tracks: title 45 %, artists 35 %, duration 20 %, minus version words absent from the original. A score of 0.8 or more is a match, 0.55 to 0.8 is highlighted for review, below is left out. Each row opens the candidates, a search of the destination and a preview, and can be confirmed, replaced or left out. A device file converted to Spotify can stay as a `spotify:local:` reference. A track that appears twice is looked up once. Spotify playlists are created with their first 100 tracks, then completed in batches.
- Google account for YouTube Music (Settings → Accounts). Google's sign-in page opens in a WebView with fresh cookies; once it lands on music.youtube.com, the cookies, `VISITOR_DATA` and `DATASYNC_ID` are checked with `account/account_menu`, then kept encrypted with an Android Keystore key and removed from the WebView. Disconnecting also deletes the playlist pages read with the account. Signed-in requests use the WEB_REMIX client with `Authorization: SAPISIDHASH`, `X-Goog-AuthUser: 0`, and `onBehalfOfUser` for brand accounts only; `Set-Cookie` rotations are written back. A 401 disconnects the account and says so.
- YouTube Music playlists of the account in the library, the home Playlists shelf and an optional YouTube Music playlists shelf: `FEmusic_liked_playlists`, then each page read with the account (private playlists included) and kept on the device. The owner's playlists (editable header) can be renamed, made public or private, receive tracks from the add sheet or "Add to playlist", lose one row by its `playlistSetVideoId`, and be deleted; saved playlists can be removed from the library. New playlists can be created on the account. Audio of these tracks still goes through the anonymous `ytm://` path.

### Changed

- Settings → Spotify account is now Settings → Accounts, with Spotify and YouTube Music.

### Fixed

- The header of a multi-service playlist showed its track count twice ("30 tracks · 30 tracks"). It now shows the source name before the count; YouTube Music playlists without a known owner do the same.

## v1.2.5

### Fixed

- Spotify Free accounts without the offline entitlement (`offline=false`, the usual Free plan) stopped after the clear lead-in, about 9 seconds into each track, then restarted in short bursts. The native engine's `Download` service, which provides Spotifeur's media handles, accepts the key and then fails the handle when that attribute is false. The engine session now always receives `offline=true`; the account's other attributes are passed unchanged. The new Free test account had an introductory trial with `offline=true`, `on-demand=true` and `ads=false`, which is why it played.
- The playback retry counter no longer resets each time the player becomes ready. A track that fails after its lead-in is retried four times, then the error is shown, instead of stopping and restarting forever.
- `audio-quality` follows the official values (`0` standard, `1` very high, `2` lossless). Without a known value, only Premium accounts select 256 kbit/s. A key refused by the service (HTTP 4xx) for a file above 128 kbit/s lowers that account's limit to 128 kbit/s until its plan attributes change.
- HTTP 429 from Spotify is retried after `Retry-After` (at most 15 s for what the user opened). The background library sync waits out the limit, reads one metadata batch at a time, yields to foreground requests and pauses 1 s between pages. Large libraries no longer exhaust the request budget needed to open albums and playlists.

### Added

- Settings → Diagnostic report: app and Android versions, the account's entitlements (type, catalogue, audio quality, ads, on-demand, offline, shuffle, streaming flags), library sizes and the last 300 recorded events (HTTP statuses by operation, rate limits, key-service statuses with the variant bitrate, media-handle failures, player errors, catalog and library failures). It can be shared or copied and contains no username, e-mail address, token, URL or content identifier.

## v1.2.4

### Added

- GitHub release checks at startup and from Settings, with release notes, an in-app APK download, progress, cancellation, verification and Android's installation confirmation. Installed release notes appear once after an upgrade.
- Dismissing an update defers automatic reminders for that version for 24 hours, persisted across process restarts. The next startup after that delay offers it again. Manual checks bypass the delay, and a newer release can appear immediately.

### Fixed

- Spotify Free playback selects the AAC variant allowed by the account's `audio-quality` attribute for streaming and downloads. Quality 0 permits 128 kbit/s but the service refuses 256 kbit/s with HTTP 403; choosing the latter previously stopped audio after the clear lead-in. Cached variants cannot override this account limit. User quality preferences are retained.

### Changed

- Device signal tests accept explicit catalog track URIs and an expected account type, so new accounts can be tested without saving music. The playback test also measures decoded PCM past the clear lead-in after an automatic transition.

## v1.2.3

### Changed

- New logo. The launcher icon is an adaptive icon drawn as vectors from `design/logo.svg`: an emerald background layer, a foreground layer with the mint pebble, its play recess and its shadow (the SVG blur rebuilt as nested scaled copies whose alpha follows the Gaussian edge), and a monochrome layer for Android 13 themed icons. The launcher applies its own mask.
- The download and playback notifications use the logo glyph as their small icon (`ic_stat_spotifeur`, also overriding Media3's `media3_notification_small_icon`). The previous icon was a full square and showed as a white square.
- The README and the site show the logo.

## v1.2.2

### Fixed

- "Add to queue" follows Spotify's queue model: queued tracks go after the current track and after the tracks already queued, before the rest of the playlist or album. "Play next" puts a track at the head of that queue. Queued tracks are marked on their media item, are never shuffled, and stay queued when another playlist or album starts.
- The skip buttons of the player sent the command 180 ms after the tap and stayed enlarged for 600 ms. The command now goes out at once and the press lasts 280 ms. Measured on a downloaded playlist, tap to sound: about 390 ms before, about 220 ms after.

### Changed

- The listening history is stored as one JSON line per play, appended instead of rewriting the whole file, compacted every 500 plays, and read off the main thread on first use. The v1.2.0 file is migrated.

## v1.2.1

### Fixed

- Opening a playlist that refers to local files no longer blocks the main thread: `TrackMatcher` compiled its regular expressions on every call, and every playlist entry was compared with every device file. The expressions are now compiled once and the device files are indexed once per file list (`LocalFileIndex`). Opening a 1653-track playlist went from a 290 ms frame to 60–110 ms, with the page transition drawn.
- Extended Metadata responses are decoded on `Dispatchers.Default` instead of the caller's thread.
- Player buttons and track changes no longer wait for the queue: `SpotifyPlayer` rebuilt and compared the whole queue on the main thread every 300 ms and at every player event. It now rebuilds it only when the timeline changes.

### Changed

- The player holds a window of the context, 20 tracks before the current one and 100 after it, as Spotify's player does. The rest of the playlist stays in `SpotifyPlayer` and is added as playback moves forward or back. Shuffle still covers the whole context, "Add to queue" still goes after it, and "repeat all" wraps to its start.

## v1.2.0

First public release.

### Added

- Welcome screen: the name, an optional Spotify sign-in card and "Continue without an account", without explanatory text. The media permission is asked once at first launch, so local files are listed immediately.
- Remove tracks from Spotify playlists you own, from the track menu or with a swipe. Spotify removes rows, so one copy of a duplicated track can go alone (`PlaylistService/Modify` with `rows`, see `protocol/06-playlist-writes.md`).
- Swipe on track rows. Right adds to the queue by default, left does nothing. Each direction can be set to add to the queue, play next, save to Liked songs, remove from the playlist, or nothing (Settings → Behavior). A direction set to nothing leaves the gesture to the page, so library tabs still swipe.
- Audio quality settings for Wi-Fi, mobile data and downloads: Automatic, Normal (AAC 128 kbit/s), Very high (AAC 256 kbit/s). The HLS master lists only these two AAC variants; the Vorbis, AAC 24 and FLAC files return 404 on this path (`protocol/07-audio-delivery.md`). A track with a stored copy keeps playing that copy.
- Listening history, from the clock icon on Home and Library: plays recorded in Spotifeur for every source, merged with `recently-played/v3` from the Spotify account, grouped by day. The local part can be cleared.
