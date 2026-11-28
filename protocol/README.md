# Spotify Android protocol notes

This folder documents how the official Spotify Android client talks to Spotify's services: authentication, catalog, library, playlists, audio delivery and the native playback engine. It was written while building Spotifeur, but it does not depend on Spotifeur's code. You can read it as a standalone reference.

## Scope

Unless a section gives a different version, class names, offsets and measurements refer to this build of the official app:

| Item | Value |
| --- | --- |
| Package | `com.spotify.music` |
| Version | 9.2.0.26 |
| Version code | 148373686 |
| `base.apk` SHA-256 | `cc165669d31851b1193cae7d54c9261579c431b432080970f460704d06e4612f` |
| `lib/arm64-v8a/liborbit-jni-spotify.so` SHA-256 | `2353f26f5e174917b36c3c9de4538717c6348cc0bacf8b2c094f22d1447538e2` |
| Orbit build ID | `a80ecaa87cb98759` |

Obfuscated class names (`p.xxxx`) and code offsets are specific to this build. Service paths, Protobuf field numbers and enum values tend to be more stable, but every update can change them.

The current Spotifeur runtime input comes from the following build:

| Item | Value |
| --- | --- |
| Package | `com.spotify.music` |
| Version | 9.2.4.2135 |
| Version code | 148912737 |
| `base.apk` SHA-256 | `97db061e215bbf5cb1b7f2ee2a6042ce37a41024e5e0339bb9bff1271917b842` |
| `lib/arm64-v8a/liborbit-jni-spotify.so` SHA-256 | `25911630321ee57f033a1191291becbd84a7f266224c2fbb522b3cf1a8659f65` |
| Orbit build ID | `13c44aff331d9c7d` |

## Contents

| File | Topic |
| --- | --- |
| [01-hosts-and-headers.md](01-hosts-and-headers.md) | Hosts, client identity, common HTTP headers |
| [02-client-token.md](02-client-token.md) | ClientToken: device registration, challenges, attestation |
| [03-login5.md](03-login5.md) | Login5 v3/v4: email code, password, stored credentials, Hashcash |
| [04-catalog.md](04-catalog.md) | Extended Metadata, search, artist and album pages, images, lyrics, radio |
| [05-library-and-playlists.md](05-library-and-playlists.md) | Collection v2, Playlist4 rootlist and contents, folders, permissions |
| [06-playlist-writes.md](06-playlist-writes.md) | Creating and editing playlists, cover upload, recommendations |
| [07-audio-delivery.md](07-audio-delivery.md) | Audio identifiers, HLS manifests, storage-resolve, CDN, protection schemes |
| [08-native-engine.md](08-native-engine.md) | Loading the Orbit engine outside the official app, media handles, sample processing |
| [09-offline.md](09-offline.md) | What can be stored for offline playback and how it is replayed |
| [10-spotify-connect.md](10-spotify-connect.md) | Connect state and device control |
| [11-official-client-measurements.md](11-official-client-measurements.md) | Runtime measurements of the official app (buffering, decoders, traffic) |
| [12-public-apis.md](12-public-apis.md) | Public SDKs and Web API, and how they differ from the internal services |
| [reproduce.md](reproduce.md) | Tools and commands to check the static observations |

## Conventions

- **Field numbers.** `field 3` means Protobuf field number 3. Nested paths are written `3 → 1 → 2`.
- **Offsets.** Hexadecimal offsets refer to `liborbit-jni-spotify.so` from the ARM64 split unless a DEX is named. For the sections cited, ELF virtual addresses and file offsets are equal. Descriptor ranges are written `[start, end)`.
- **DEX offsets.** `+0x33` after a method name is a code-unit offset from its first opcode.
- **Hosts.** `spclient` stands for `https://spclient.wg.spotify.com/`.

## How much each statement is worth

Each page separates three levels of evidence. They are not interchangeable.

| Level | Meaning |
| --- | --- |
| **Static** | Read from the APK: DEX bytecode, embedded Protobuf descriptors, string references in ARM64 code. It proves a contract exists in the binary, not that the server accepts it. |
| **Tested with doubles** | Exercised by unit tests against hand-built messages. It proves the encoder and decoder agree with the documented layout. |
| **Live** | Observed against Spotify's real services with a real account. It proves the request works for that account, that build and that date. |

A playing state, a moving position or a full buffer does not prove that audio is audible. Audio claims marked **live** in these notes were checked by measuring decoded PCM signal past the unencrypted lead-in, after seeks and across track transitions.

## Method

- `apkanalyzer dex code` and JADX for the DEX side.
- `llvm-objdump` and `llvm-readelf` for the ARM64 library. String references are confirmed by resolving `ADRP` + `ADD` pairs to the literal's address, which is stronger than the string merely being present.
- Protobuf schemas are recovered from the `FileDescriptorProto` blobs compiled into Orbit and decoded with `protoc --decode=google.protobuf.FileDescriptorProto`.
- Runtime behaviour of the official app was observed on an ARM64 Android 17 phone with `dumpsys`, Perfetto and `/proc/net/dev`, without root and without modifying the app.

See [reproduce.md](reproduce.md) for the exact commands.

## What is not here

No binaries, key material, account data, tokens or captured traffic are included. The notes describe interfaces, message layouts and behaviour. To check them, use a copy of the official APK with the hashes above.

## Status

This is independent interoperability research. Spotify can change any of these interfaces with a new app version.
