# Reproducing the static observations

You need your own copy of the official APKM for version 9.2.0.26 (build 148373686), the Android SDK command-line tools (`apkanalyzer`, `aapt2`), LLVM (`llvm-objdump`, `llvm-readelf`) and `protoc`.

## Extract and check

```sh
unzip -o com.spotify.music_9.2.0.26.apkm base.apk split_config.arm64_v8a.apk
shasum -a 256 base.apk
unzip -p split_config.arm64_v8a.apk lib/arm64-v8a/liborbit-jni-spotify.so > orbit.so
shasum -a 256 orbit.so
llvm-readelf -h -SW -d -n orbit.so
```

Expected hashes are in [README.md](README.md#scope).

## DEX

```sh
apkanalyzer manifest print base.apk
apkanalyzer dex code --class com.spotify.connectivity.auth.NativeSession base.apk
apkanalyzer dex code --class p.xwn1 --method 'run()V' base.apk
apkanalyzer dex code --class p.jmn0 --method 'run()V' base.apk
apkanalyzer dex code --class p.g54 --method 'run()V' base.apk
apkanalyzer dex code --class p.rd51 base.apk
apkanalyzer dex code --class p.g370 base.apk
apkanalyzer dex code --class p.efe base.apk
apkanalyzer dex code --class p.hrh1 base.apk
apkanalyzer dex code --class p.tuz base.apk
apkanalyzer dex code --class p.vpp0 base.apk
apkanalyzer dex code --class com.spotify.playbacknative.AudioDriver base.apk
aapt2 dump xmltree base.apk --file res/xml/network_security_config.xml
```

Strings across all DEX files, for example the attestation classes:

```sh
unzip -o base.apk 'classes*.dex' -d dex/
for d in dex/classes*.dex; do strings -n 6 "$d"; done |
  grep -iE 'StandardIntegrity|requestExpressIntegrityToken|cloudProjectNumber|integrityimpl'
```

## Native code

```sh
llvm-objdump -d --start-address=0xba6ca4 --stop-address=0xba6cdc orbit.so   # Login5 v3
llvm-objdump -d --start-address=0xba70c4 --stop-address=0xba70f8 orbit.so   # Login5 v4
llvm-objdump -d --start-address=0xdb8a40 --stop-address=0xdb8d74 orbit.so   # storage-resolve path
llvm-objdump -d --start-address=0xdb0550 --stop-address=0xdb0ee8 orbit.so   # range requests
llvm-objdump -d --start-address=0x14d5d74 --stop-address=0x14d60cc orbit.so # response check
llvm-objdump -d --start-address=0x13c4db4 --stop-address=0x13c561c orbit.so # collection v2
llvm-objdump -d --start-address=0x139179c --stop-address=0x1391e88 orbit.so # playlist v2
```

To check a string reference, take the page loaded by `ADRP`, add the `ADD` immediate, and compare with the string's address. Disassemble with raw addresses: LLVM may label unsymbolized code with the name of a nearby exported symbol.

## Protobuf descriptors

Descriptor ranges are `[start, end)`. Decode one with:

```sh
dd if=orbit.so bs=1 skip=$((0x5b8ab4)) count=$((0x5b8bb9-0x5b8ab4)) 2>/dev/null |
  protoc --decode=google.protobuf.FileDescriptorProto google/protobuf/descriptor.proto
```

To find a descriptor, search for its file name prefixed by a length byte, for example `\n<len>client_token.proto`, then walk the wire format from there.

| Descriptor | Range |
| --- | --- |
| `client_token_http.proto` | `[0x59bb94, 0x59c882)` |
| `storage-resolve.proto` | `[0x5b8ab4, 0x5b8bb9)` |
| Net Fortune | `[0x5b889c, 0x5b8a7c)` |
| `your_library_request.proto` | `[0x5d7c9c, 0x5d837f)` |
| `rootlist_modification_request.proto` | `[0x5e2f7c, 0x5e3175)` |
| `collection2v2.proto` | `[0x601880, 0x601d37)` |
| `es_offline.proto` | `[0x603358, 0x606310)` |
| `es_set_queue_request.proto` | `[0x63012c, 0x630365)` |
| `playlist_api.proto` | `[0x63ca58, 0x63f67d)` |
| `playlist_permission.proto` | `[0x642284, 0x644b4d)` |
| `extended_metadata.proto` | `[0x66df3c, 0x66e52d)` |
| `audio_files_extension.proto` | `[0x67064c, 0x670934)` |
| `metadata.proto` | `[0x6713c0, 0x6737f4)` |
| `metadata_esperanto.proto` | starts at `0x5b48c0` (search `\n\x18metadata_esperanto.proto`) |

## Runtime measurements

To test a Free account without replacing an existing installation, build with
`-PspotifeurTestApplicationId=fr.spotifeur.freecheck`, install both APKs and sign in
to the separate installation. The regular installation keeps its account and files.
Use `fr.spotifeur.freecheck/fr.spotifeur.MainActivity` to launch it. Then run:

```sh
adb shell am instrument -w -r -e spotifyAudioStress true -e spotifyAccountType free \
  -e spotifyTrackUris spotify:track:4uLU6hMCjMI75M1A2tKUQC,spotify:track:3n3Ppam7vgaVa1iaRUc9Lp \
  -e class fr.spotifeur.playback.NativePlaybackSignalTest,fr.spotifeur.playback.AudioQualitySignalTest \
  fr.spotifeur.freecheck.test/androidx.test.runner.AndroidJUnitRunner
```

`-e spotifyAttributeOverrides 'offline=false;ads=true'` (quoted for the device shell)
replaces attributes in the engine session only, to reproduce another plan's entitlements;
`offline` is then forced back to `true` as in the app.

These tests measure decoded PCM after the clear lead-in and seeks; the first also
checks an automatic track transition. The quality test expects the requested level
within the account's grant. `SpotifyEntitlementsDiagnosticTest` logs only a whitelist
of non-secret attribute values; it never logs the playback license itself.
`OfflineCacheSignalTest#downloads` accepts the same track/account arguments with
`-e spotifyOfflinePhase downloads` to test downloaded audio after network cutoff.
Build the regular app again without the application ID property when finished.

```sh
adb shell dumpsys media.metrics
adb shell dumpsys media_session
adb shell cat /proc/net/dev
```

Perfetto with the `android.network_packets` data source gives per-UID packet timing; see the [Perfetto docs](https://perfetto.dev/docs/analysis/stdlib-docs#android.network_packets).

Read-only checks added with the native metadata fallback and the Google credential:

```sh
T() { adb shell am instrument -w -r "$@" fr.spotifeur.test/androidx.test.runner.AndroidJUnitRunner; }
T -e spotifyNetwork true -e class fr.spotifeur.data.NativeMetadataProbeTest,fr.spotifeur.data.Login5NetworkTest
T -e spotifyArtist true -e artistQuery Wailtoad -e class fr.spotifeur.data.SpotifyArtistProbeTest
T -e youtubeAccount true -e class fr.spotifeur.data.YouTubeAccountProbeTest
```

