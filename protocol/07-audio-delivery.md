# Audio delivery

Getting audio for a track takes four separate steps: identify the media file, get a manifest or CDN URL, download the bytes, then get the right to process them. Each step has its own identifiers.

## Identifiers

| Identifier | Size | Role |
| --- | --- | --- |
| Track URI / GID | 16 bytes | The catalog work. Independent of the file actually played. |
| Context URI | | Playlist, album or other source. The track and its neighbours can change inside a context. |
| `file_id` | 20 bytes | One media variant (codec, bitrate). `metadata.AudioFile` field 1. |
| `audio_id` | 16 bytes | Identifier used by the HLS manifests. `AudioFilesExtensionResponse` field 4. |
| KID | 16 bytes | Key ID in the MP4 `tenc`/PSSH. It is the **first 16 bytes** of the 20-byte `file_id`. |
| `playback_id` | | One playback instance. Sent as `x-playback-id` on some CDN requests. |

`metadata.AudioFile` has 1 `file_id` (bytes) and 2 `format`. In `metadata.proto`, Vorbis 96/160/320 are 0/1/2, AAC 24/48 are 8/9, FLAC is 16 and FLAC 24-bit is 22.

## AUDIO_FILES extension

Ask Extended Metadata for extension kind **5** on the track URI (see [04-catalog.md](04-catalog.md#extended-metadata)). Descriptor `audio_files_extension.proto` `[0x67064c, 0x670934)`:

| Field | Content |
| --- | --- |
| 1 | Extended files: 1 `file` (`AudioFile`), 4 `average_bitrate` |
| 2 | Default file normalization: `loudness_db`, `true_peak_db` (floats) |
| 3 | Default album normalization |
| 4 | `audio_id` (16 bytes) |

`NormalizationParams` has 1 `loudness_db` and 2 `true_peak_db`, both `float` (wire type 5). Spotifeur's volume normalization (off by default) aims at −14 LUFS, keeps 1 dB under the true peak, and only lowers the output volume: quieter tracks keep their level. **Tested with doubles.**

If the track has no files, follow its `alternative` first (see [unavailable releases](04-catalog.md#unavailable-releases)).

## HLS manifests

The current Android path uses HLS. `p.g931` configures both templates and the license URL. `p.f7u0.a` reads `AudioHosted.audioId` and hex-encodes it (`p.zno1.z`, `classes.dex:0x45a96e`).

```text
GET spclient/sneaktables/v2/hls/{mode}/{audio_id_hex}/master.m3u8
```

The master playlist lists variants. For music it holds two AAC-LC variants (`CODECS="mp4a.40.2"`), `BANDWIDTH=128000` and `BANDWIDTH=256000`, each tagged `X-SPOTIFY-KEYID` with the 40-character `file_id` of that variant. The engine also has a per-file template, `sneaktables/v2/hls/4/{file_id}/audio.m3u8`.

**Live:** on four tracks, modes 1, 2 and 4 all listed the same two AAC variants; modes 0, 5–8 and 10 returned 400 and mode 3 returned 404. The per-file template returned a media playlist (`METHOD=SAMPLE-AES`, `mp4a` in an `enca`/`cbcs` init segment) for the two AAC file IDs only. The Vorbis 96/160/320, AAC 24 and FLAC file IDs listed by AUDIO_FILES returned 404 there. Lossless and the other formats are therefore not reachable on the HLS path. A client following HLS can offer 128 and 256 kbit/s; Spotifeur maps its quality presets to these two variants. The media playlist covers the whole track with an `EXT-X-MAP` init segment and byte-range segments on CDN URLs. The CDN URLs contain the 40-character `file_id` and a temporary signature. Strip the signature when you use the URL as a cache key.

| Mode | Media playlist | Protection |
| --- | --- | --- |
| 1 | `METHOD=SAMPLE-AES-CTR`, `KEYFORMAT=urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed` | Widevine. License from `POST spclient/widevine-license/v1/audio/license` with the raw CDM key request (`application/octet-stream`, `p.ua91.f`). Provisioning is standard. |
| 4 | `METHOD=SAMPLE-AES`, no Widevine `KEYFORMAT` | Spotify's own scheme. PSSH system ID `d4a32b78-46e3-4a7b-9f2c-7e1b5c8d0e9a`. Samples are processed by the native engine, not by `MediaCrypto`. |

`p.s6z0.apply` and `yt91.b` choose the DRM provider by policy: OFF and VIDEO keep the standard Widevine provider (`bih1.b`); AUDIO and ALL select the custom provider (`swm0.b` → `ppt0`). The official app plays music through **mode 4**.

### Account quality and Free playback

**Live, 2026-11-09:** the connected Premium account reports `type=premium`,
`catalogue=premium`, `audio-quality=2`. A newly created Free account reports
`type=free`, `catalogue=free`, `audio-quality=0`, with a `player-license` present.
On the Free account, AAC 128 kbit/s produces decoded PCM after the clear lead-in
and after a seek. Preparing an AAC 256 kbit/s file through the same native engine
gets HTTP 403 from `playplay/v1/key/{file_id}`, then the codec reports a preparation
failure when it reaches encrypted samples. A full manifest and ten seconds of sound
therefore do not establish authorization for every variant in that manifest.

**Implementation:** quality 0 limits variant selection to 128 kbit/s before cache
preference is applied. Quality 2 retains the existing 128/256 selection. The same
selection is used for streaming, prefetch and downloads; the stored user setting
is unchanged. The account policy is refreshed for a new session and retained per
account for offline startup. No other attribute values have been measured.

**Live after the selection change:** on this Free account, both quality presets
produce PCM at 128 kbit/s past the lead-in and after seeks. A two-track test also
measures PCM after an automatic transition. The real playback service passes
pause/resume, shuffle/repeat, seek, next and background checks, with PCM measured
past the lead-in and after seeks. Two explicit downloads play encrypted samples
after the app/native HTTP paths are disabled, including seeks and a track change.
A fresh process also plays those downloads with these HTTP paths disabled, past
the lead-in on both tracks. This last test uses the app's offline test hooks,
not an APK without the Android INTERNET permission. Free-account library writes
and the complete playlist lifecycle (including cover and recommendations) pass,
with temporary favorites and playlists removed afterwards.

**Static (9.2.0.26):** the official app reads `audio-quality` as a string mapped to the enum
`p.qzy0`: `"0"` STANDARD, `"1"` VERY_HIGH, `"2"` LOSSLESS (`classes6.dex`, `p.wzy0` and `p.h5y0`).
Spotifeur keeps 256 kbit/s for `1` and `2`, limits `0` to 128 kbit/s, and without a known value
keeps 256 kbit/s only for `type=premium`. A 4xx from the key service for a file above 128 kbit/s
lowers the account's limit at run time; the limit is kept until `type` or `audio-quality` change.

**Live, 2026-11-10:** the body of `POST playplay/v1/key/{file_id}` sent by the engine for
`GetMediaHandleId` has the top-level fields `1=5`, `2` (16 bytes), `4=1`, `5=1` and `6` (a Unix
time in seconds). Read with librespot's `PlayPlayLicenseRequest` names, these are version, token,
interactivity INTERACTIVE and content type AUDIO_TRACK: a streaming request, not a download.
The `Download` service still depends on the `offline` attribute; see
[08-native-engine.md](08-native-engine.md#credentials-and-http).

### Mode 1 is not enough

**Live:** with the platform Widevine CDM, mode 1 plays the unencrypted lead-in (about 10 s), then the encrypted part decodes to invalid AAC frames that Android replaces with silence. Position, `PLAYING` state and buffer level all look normal. Only measuring the decoded PCM reveals the problem. Mode 4 with the native engine is the path that produces signal past the lead-in.

### Mode 4 and the native engine

`ppt0` reads the PSSH data for the custom UUID, extracts the KIDs (`z1a1.i`) and creates the custom `DrmSession` `opt0`. Its `CryptoConfig` is a custom singleton (`sl10.a1`), not framework `MediaCrypto`. Before each input buffer is queued to the platform decoder, `cv70.run` passes the encrypted sample to a native processor that works in place on the direct `ByteBuffer`. Details in [08-native-engine.md](08-native-engine.md#media-handles-and-sample-processing).

The engine needs a **media handle** for the file. It gets one from `spotify.download.esperanto.proto.Download/GetMediaHandleId`, which internally fetches:

```text
GET spclient/playplay/v1/key/{file_id_hex}
```

The response is opaque to the client. Only the native engine interprets it. No key bytes are exposed to Java.

## storage-resolve and CDN (direct file path)

The native engine can also resolve a file to CDN URLs directly. The procedure at `0xdb8a40` builds:

```text
storage-resolve/v2/files/audio/{mode}/{n}/{file_id_hex}?product=0
```

`mode` is `interactive` by default, `offline` for 1 and `interactive_prefetch` for 2. `{n}` is an integer converted with `std::to_string` (call at `0xdb8afc`); its meaning was not established. A branch appends `&partner=…`. The block `0xdb8bc0..0xdb8bd0` prepares `cache-control: no-cache`. [librespot](https://github.com/librespot-org/librespot/blob/dev/core/src/spclient.rs) uses a route without `v2`.

Response `storage-resolve.proto` `[0x5b8ab4, 0x5b8bb9)`:

| Field | Type | Content |
| --- | --- | --- |
| 1 `result` | enum | `CDN = 0`, `STORAGE = 1`, `RESTRICTED = 3` |
| 2 `cdnurl` | string[] | CDN URLs |
| 4 `fileid` | bytes | File ID |

`RESTRICTED` is a refusal, distinct from CDN errors.

CDN transfer, from the ARM64 code:

| Step | Evidence |
| --- | --- |
| Head file | `0xdb33f8`: "Reading head file from cache" (`0xdb35d8`) or "Requesting head file from CDN" (`0xdb3898`); loads `Accept-Encoding` and `x-playback-id`; metric `head_file_latency`. "Head file" is the start of the media, not an HTTP `HEAD`. |
| Range or whole file | `0xdb0550` logs the whole-file path (`0xdb0b08`) and builds the `Range` variant (`0xdb0c2c`). |
| Conditional headers | `x-playback-id` (`0xdb0a78`), `x-segment-timestamp-ms` (`0xdb0ab8`). |
| Response check | `0x14d5d74` reads `Content-Length` and distinguishes 200, 206 and 416; 206 and 416 then read `Content-Range` (`0x14d5e4c`). |
| Errors | `0xdaf120` logs range, error and status. `0xdb0478` and `0xdb0518` separate permanent and transient errors. An incomplete result invalidates the cached file (`0xdab5c4`). |
| Prefetch | Around `0xca517c`: prefetch of the next track and its head file. `0xca64e8` abandons prefetch after a recent key failure. |
| Disk fallback | `0xdb1940`; `BlockScanner` (`0xc00608`) attaches to an ongoing download, serves from memory or storage, falls back to storage, gives up after retries. |

## Protection generations

1. **Legacy AES-128-CTR.** A 16-byte audio key requested from the Access Point session, fixed IV ([librespot](https://github.com/librespot-org/librespot/blob/dev/core/src/audio_key.rs)). **Live:** the Access Point accepted the session but refused key requests with code 1. It is not usable for this account and build.
2. **Widevine (HLS mode 1).** Strings: `spotify.contentagnostic.v2.Widevine`, `licenseUrl`, `player_license.proto`, `spotify.player.proto.PlayerLicense`, `license_server_endpoint`, `switchToLicense`, `GetKeysRequest`, `DRM key request failed`. Containers: `MP4_128_CBCS`, `MP4_256_CBCS`, `cenc/ctr`. The key stays inside the platform CDM.
3. **Spotify's custom scheme (HLS mode 4).** Opaque handles and in-process sample processing by the native engine. See above.

The native DRM module is `cpp-audio-drm_setup_impl` (`0xc1171c`), namespace `spotify::drm`, log file `shared/audio/drm_impl/src/runtime_diag.cpp`. Key prefetch is tied to a track and a file (`0xca4dc8`). `0x11d11a4` distinguishes `wait_for_key` and `key_received`.

The official app's own playback was observed using ordinary (non-secure) AAC and MP3 decoders, with no `MediaDrm` session, and no platform decoder at all for lossless FLAC. That fits in-process processing, not platform Widevine. See [11-official-client-measurements.md](11-official-client-measurements.md#decoders).

## Decoding and output in the official app

| Part | Evidence |
| --- | --- |
| Vorbis | `vorbis_synthesis_init failed` (`0x120122c`); fMP4 Vorbis diagnostics |
| FLAC | 16/24-bit format descriptors and FLAC state diagnostics |
| MediaCodec | `MediaCodecFileDecompressor` (`classes3.dex`), `AMediaCodec_*` imports |
| AudioTrack | `AudioDriver` (`classes2.dex`) writes with `AudioTrack.write(ByteBuffer, int, int)`, reports underruns natively |
| Oboe | `OboeRenderer` (`classes10.dex`), `oboeAaudioDataCallback` (`0x11f8fc0`), `libaaudio.so`, `libOpenSLES.so` |

## Bitrate and buffering hints

The Net Fortune descriptor `[0x5b889c, 0x5b8a7c)` holds bitrate estimates and separate prefetch advice for metered and unmetered connections. `0x11cb618` logs a `BitrateDownshiftAdviser` (critical buffer threshold, averaging window, depletion threshold, waiting for key or initial buffer at `0x11cbf9c`) and optional throttling (rate limit, delay, chunk size). Threshold values were not recovered.

## Queue and downloads in the official engine

- `p.tuz.b(SetQueueCommand)` (`classes2.dex`) calls `spotify.player.esperanto.proto.ContextPlayer/SetQueue`. `es_set_queue_request.proto` `[0x63012c, 0x630365)`: 1 `next_tracks`, 2 `prev_tracks`, 3 `queue_revision`. The same adapter calls `GetQueue` and `AddToQueue`. Removals without a matching UID are dropped (`0x11571c8`).
- `p.vpp0.a` calls `spotify.offline_esperanto.proto.Offline/AddDownload` (`DownloadRequest`: 1 `device`, 2 `uri`). `p.oqp0.c` calls `RemoveDownload`. `es_offline.proto` `[0x603358, 0x606310)` also has progress streams, `LicenseStatus` and `GetOfflineLicenseInfo`. A `GET …/licenseStatus` builder is at `0xd68a08`. This official offline path consumes a device license. Spotifeur does not use it; see [09-offline.md](09-offline.md).
