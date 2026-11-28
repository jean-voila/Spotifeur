# Working in this repository

- Work on the current branch. Do not create branches or worktrees unless asked.
- Before changing the player, read `native-runtime/README.md` and `protocol/07-audio-delivery.md` and `protocol/08-native-engine.md`.
- The client uses the native Android protocols. Do not substitute Spotify for Developers OAuth, the Web API, App Remote, a playback WebView or a dependency on the installed Spotify app.
- The Android client constants in network messages are those of the analysed package. They are not a developer app registration.
- A moving position, the PLAYING state or a full buffer does not prove there is sound. New tests must check the decoded signal past the clear lead-in, after seeks and across track transitions.
- Keep three things apart: contracts checked statically, tests with doubles, and successes against the real services. Do not call full playback working before the encrypted path is validated.
- The engine binaries are produced from the supplied APKM by the `prepareSpotifyRuntime` task, with verified hashes. Never add them to Git.
- The player uses HLS mode 4 and NativeMediaProcessor. `GetMediaHandleId(AUDIO)` needs the full 20-byte file ID (40 hex characters). The 16-byte MP4 KID is only used to find the context. Do not truncate the ID in the request and do not reintroduce MediaCrypto on this path.
- Shuffle reorders the queue inside `SpotifyPlayer`. Media3's own shuffle mode stays off.
- YouTube Music tracks, and Spotify tracks played without a Spotify account, use `ytm://` addresses read by `YouTubeDataSource` (progressive, byte ranges). That path never goes through the Spotify engine, and the Spotify path never falls back to it while signed in, except in Skibidisong mode (restricted account, setting on), where `Skibidisong` resolves `ytm://match/` addresses after comparing the Spotify clear lead-in with each candidate.
- Everything in the repository is written in English. The app ships English (default) and French strings.
