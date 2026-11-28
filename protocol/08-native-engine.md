# Running the native engine outside the official app

The audio processing for HLS mode 4 lives in Spotify's native core, Orbit (`liborbit-jni-spotify.so`), driven from Java classes in the app's DEX files. Both can be loaded by another Android app, unchanged, without the official app being installed. This page describes what that takes.

Nothing here modifies the engine. It is loaded as shipped, after checking its SHA-256.

## Inputs

From the official APKM, version 9.2.0.26:

- `base.apk` → every `classes*.dex`, repackaged into one JAR loaded by a `DexClassLoader`.
- `split_config.arm64_v8a.apk` → `lib/arm64-v8a/liborbit-jni-spotify.so`, loaded with `System.load` from a class of the same child class loader.

The engine is ARM64 only. Keep its class loader separate from the host app so its dependencies (RxJava 3, Protobuf, and others) do not clash.

## JNI constraints

- The JNI code calls `FindClass` with dotted names (for example `FindClass("android.media.MediaCodecList")`). CheckJNI rejects these and aborts the process. CheckJNI is on for debuggable apps, so the host app must be **non-debuggable**, like the official one.
- Callback interfaces passed to native code must be **concrete Java classes**. A `java.lang.reflect.Proxy` implementation triggers a CheckJNI abort in `CallVoidMethodV`.
- `NativeHttpConnection.initialize(factory)` and `ConnectivitySdk.setMobileDeviceInfo(info)` must run **before** `NativeApplicationScope.create`. Otherwise native code calls `terminate` (routine `0xb7c8ac`, when the flag at `0x18a97f0` is not set; global init is `0xb75924`).

## Construction order

Everything below runs on the native timer thread.

1. `NativeTimerManagerThreadImpl.create("Core Thread")`, then `start`.
2. `NativeFileIOImpl.create()`, `NativePrefs.create()`, `NativeRouter.create()`, `router.initializeScheduling(scheduler)`.
3. `NativeConnectivityManager.create(router, scheduler, analyticsDelegate, true)`, then `DefaultConnectionTypeProvider.create(manager)` and `DefaultConnectivityPolicyProvider.create(manager)`.
4. `PrefsCredentialsStorage.create(prefs, deviceId)` and `NativeTrafficSetupImpl.create()`.
5. `NativeApplicationScope.create(timer, router, analytics, connectionProvider, policyProvider, credentialsStorage, connectivityConfig, reachabilityTransport, trafficSetup, fileIO)`.
6. `NativeLogin5SetupImpl.create(Login5Configuration, timer, appScope)` with the client ID, device ID and protocol 3.
7. `NativeOAuthSetupImpl.create(Login5Configuration, timer, login5Setup, AccessToken, UnencryptedCredentials, router, appScope, boolean, boolean)`. `AccessToken` is `Bearer`, the current token, and `expiresAt` in Unix seconds.
8. `NativeSession.createNativeSessionWithoutAp(timer, appScope, UnencryptedCredentials, oauthSetup, userAttributes, deviceId)`. This is the path the official app takes; no Access Point login controller is involved.
9. `NativePubSubSetupV2Impl.create(webSocketTransport, timer, oauthSetup, appScope)`.
10. `NativeAuthenticatedScope.create(timer, router, appScope, session, pubSub, analytics, authenticatedConfig, fileIO)`.
11. Core application scope: `NativeApplicationScopeImpl.create(timer, router, prefs, coreAppConfig, eventSenderBridge, fileIO)` (the overload without `LoginController`).
12. `FullAuthenticatedScope.create` (20 arguments, below), then `mediaProcessorFactory()` and `contentDeliveryIOTransport()`.

Official call sites: `p.xwn1.run()` (steps 3–6), `p.jmn0.run()` (step 7), `p.g54.run()` (steps 8 and 10), `p.z5n` (step 11), `p.d5n` (step 12).

**Static (9.2.4.2135):** `NativeOAuthSetupImpl.create` now takes a `Login5BackoffConfig` after `NativeApplicationScopeAPI`. `NativeSession.createNativeSessionWithoutAp` takes a `NativeProductStateSetup` in place of the attribute map. Create it on the timer with `NativeProductStateSetup.create(timer, attributes)` and destroy it after the session. `p.n0q0.a()` returns the platform crypto provider for the full scope.

### `FullAuthenticatedScope.create` arguments

| # | Type | Value |
| --- | --- | --- |
| 1 | `NativeTimerManagerThread` | timer |
| 2 | `NativeRouter` | shared Cosmos router |
| 3 | `NativePrefs` | from step 2 |
| 4 | `NativeRemoteConfig` | `NativeRemoteConfigImpl.create(transport)` |
| 5 | `NativeConnectivityManager` | step 3 |
| 6 | `NativeApplicationScope` | connectivity scope, step 5 |
| 7 | `NativeSession` | step 8 |
| 8 | `NativeAuthenticatedScope` | step 10 |
| 9 | `NativeApplicationScope` | core scope, step 11 |
| 10 | `NativeLocalFilesDelegate` | `.noop()` |
| 11 | `NativeUserDirectoryManager` | `NativeUserDirectoryManagerImpl.create(cachePath, settingsPath, username)` |
| 12 | `FullAuthenticatedScopeConfiguration` | default config with private cache/settings paths and client identity |
| 13 | `NativeOfflinePluginRegistry` | empty list |
| 14 | `NativeForegroundProvider` | `.noop()` |
| 15 | `NativeFileIO` | step 2 |
| 16 | `Transport` | null (the official app passes the default) |
| 17 | `Transport` | `spotify.device_location_service.proto.DeviceLocationService` |
| 18 | `Transport` | `spotify.connectivity.websocket.esperanto.proto.WebSocketClient` |
| 19 | `Transport` | a second `WebSocketClient` |
| 20 | `NativeCryptoProvider` | plain Java provider (`p.amn0.a()`), platform algorithms |

A 21-argument overload also takes a `NativeTokenExchangeSetup` after argument 8. Passing dummy native pointers is not a working substitute for this graph.

## Credentials and HTTP

- `UnencryptedCredentials` is `(String username, byte[] storedCredential)`: the raw bytes from `LoginOk`. A host app can keep the persistent copy encrypted and hand the engine an in-memory snapshot only.
- The engine makes its HTTP requests through the `HttpConnection` factory you register. That is where the host app can attach the current Login5 bearer and ClientToken, renew them before expiry, retry once on 401, and refuse to send credentials to CDN hosts or after cross-host redirects. Decompress gzip and keep the engine's own platform headers.
- The engine also generates ClientTokens on its own. A host that already manages one can disable that and serve its own, so the two do not race.
- The engine needs the account attributes (`nebula/v1/configuration/attributes`) at startup. Re-read them for every new online engine session; keep the last copy for offline starts.
- **Live, 2026-11-10 (9.2.0.26, local attribute substitution):** the tested Free account had `offline=true`. Changing only `offline` to `false` in the engine let the clear lead-in decode and returned a key response. The processor failed at the first encrypted sample, about nine seconds in. The old app forced `offline=true` in the engine. The server's response for an account with its own `offline=false` was outside that test.
- **Live, 2026-11-20 (9.2.4.2135, account as returned by Spotify):** the account reports `type=free`, `audio-quality=0`, `offline=true`, `ads=false`, `on-demand=true` and `player-license=premium`. Feeding those attributes through `NativeProductStateSetup` gives decoded PCM at 128 kbit/s after 20 seconds, after seeks and across an automatic transition. Spotifeur now initializes the engine with the account attributes as returned.
- **Live service with local attribute substitution, 2026-11-20 (9.2.4.2135):** with `offline=false`, `ads=true`, `on-demand=false` and `pick-and-shuffle=true` substituted in the engine only, the 128 kbit/s key request returned HTTP 200 and the clear lead-in produced PCM. `createProcessor` refused the handle when playback reached encrypted samples. The account still had `offline=true` at the service.
- **Live without attribute overrides, 2026-11-25 (9.2.4.2135):** the test account now reports `type=free`, `audio-quality=0`, `offline=false`, `ads=true`, `on-demand=false` and `pick-and-shuffle=true`. A new 128 kbit/s key request returned HTTP 200. The test measured non-silent PCM in the clear lead-in, then the native factory refused to create a processor at the encrypted boundary. A repeat run on the same file reported `CreateProcessorResult.Fail.code=5`. The signal test stopped there, before the seek and transition checks. The installed app also logged HTTP 403 for other 128 kbit/s key requests.

## Calling services

The Cosmos router carries Esperanto calls: a service name, a method name and a Protobuf body. `Transport` (`classes.dex`) exposes `callSingle`, `callStream` and `callSync`. Release each call's `Lifetime` when done. Examples used in these notes:

- `spotify.download.esperanto.proto.Download/GetMediaHandleId` (stream)
- `spotify.playlist_esperanto.proto.PlaylistService/Modify`
- `spotify.playlist_esperanto.proto.RootlistModificationService/Modify`
- `spotify.playlist_esperanto.proto.PlaylistDataService/Get`

## Media handles and sample processing

### `GetMediaHandleId`

| | Field | Content |
| --- | --- | --- |
| Request | 1 | `MediaType` enum: `AUDIO = 1`, `VIDEO = 0` |
| | 2 | **Full 20-byte `file_id` as 40 lowercase hex characters** |
| Response | 1 | `handleId` (uint32, opaque) |
| | 2 | status: `OK = 0`, `FAILED = 1`, `SUNSET = 2` |

`OK` with handle 0 means pending; keep listening. Keep the subscription open as long as the handle is in use.

The official code (`o5n.a` → `qpa0` → `fxu.c`, then `e8m`) normalizes a **16-byte KID** and the handle is stored keyed by that KID. That is a lookup key only. **Live:** sending the 32-character KID in the request returns `FAILED`; sending the full 40-character `file_id` returns a handle. Map the KID from the sample back to the full `file_id` from the media URL (the first 32 hex characters match).

### Processor

`factory.createProcessor(handleId, ProcessingMode)` returns `CreateProcessorResult.Success.getProcessor()`. Failures are reported as code + 400. The processor (`NativeMediaProcessorImpl`) exposes:

```text
setContext(byte[] context): boolean
process(ByteBuffer direct, int offset, int length): boolean
processSample(ByteBuffer direct,
              int sampleOffset, int segmentCount,
              int[] skipByteCounts, int[] processByteCounts,
              int processBlockCount, int skipBlockCount,
              byte[] context,
              boolean resetContextPerSegment,
              boolean processPartialFinalUnit): int
destroy(): void
```

`processSample` takes the IV as `context`, the clear and encrypted subsample sizes, and the pattern block counts. It returns 0 on success; 501–526 report a missing processor, buffer, arrays or context, a non-direct buffer, out-of-bounds sizes, or a failed block operation. The buffer must be direct and is processed in place before `queueInputBuffer`. The same sample must not also go through `MediaCrypto`.

| MP4 scheme | Mode | `resetContextPerSegment` | `processPartialFinalUnit` |
| --- | --- | --- | --- |
| `cenc` | STREAM | false | true |
| `cens` | STREAM | false | true |
| `cbc1` | BLOCK | false | false |
| `cbcs` | BLOCK | true | false |

These values are explicit comparisons in `cv70.run`. Cache processors per `(handleId, mediaSessionId)`.

### Lifecycle

`ppt0.release` → `o5n.b(mediaSessionId)` destroys every processor of the media session. Shut down in reverse order: close subscriptions and processors, then transports, call `prepareForShutdown`, let the shutdown callbacks run, destroy the scopes in reverse creation order, and stop and destroy the timer last. Dropping Java references does not free native state.

## Verification

**Live**, on an ARM64 phone:

- the engine loads and the unauthenticated scopes build and tear down cleanly, in an app with no INTERNET permission;
- an authenticated session returns a media handle for a real track;
- decoded PCM has signal after 20 s, after several seeks and on a second track;
- the same holds across automatic track transitions in the background, on a 1,600+ track playlist.

None of this proves that every account, region or future build behaves the same.
