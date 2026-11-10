# Changelog

## Unreleased

### Fixed

- Connecting a Google account (Settings → Accounts) could stop on YouTube's home page and never import the playlists ([#4](https://github.com/jean-voila/Spotifeur/issues/4)). Google sometimes ends the sign-in on www.youtube.com or m.youtube.com instead of following `next` to YouTube Music, for instance in the mobile WebView or after the consent page. Once the session cookies are set, the sign-in screen now opens YouTube Music itself, at most twice. If the sign-in still stops on the home page, with or without a session, the screen says so and offers Try again, and the diagnostic report records the page and whether a session was open. The landing page is also checked by its exact host, so a look-alike host such as `music.youtube.com.example.org` can no longer end the sign-in.
- Leaving the Google sign-in screen before the end now removes the WebView's cookies. Until now they were removed only after a successful connection, so an abandoned sign-in left the Google session cookies in the WebView's storage, outside the encrypted credential.

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
