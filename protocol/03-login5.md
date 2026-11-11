# Login5

Login5 exchanges a credential for an access token and a reusable stored credential.

```text
POST https://login5.spotify.com/v3/login
POST https://login5.spotify.com/v4/login
Accept: application/x-protobuf
Content-Type: application/x-protobuf
client-token: {ClientToken}
```

The v3 schema is public in [librespot](https://github.com/librespot-org/librespot/blob/dev/protocol/proto/spotify/login5/v3/login5.proto). v4 adds `flow_id` and new credential types. Both are compiled into Orbit.

## Request

| Field | Content |
| --- | --- |
| 1 `client_info` | 1 client ID, 2 device ID |
| 2 `login_context` | Bytes from the previous challenge response, echoed back |
| 3 `challenge_solutions` | 1 `solutions[]` |
| 6 `flow_id` (v4) | UUID generated once per login attempt and repeated on every request of that attempt. Field 6 of `LoginRequest` v4, table at file offset `0x1757204`. |
| 100 `stored_credential` | 1 username, 2 data |
| 101 `password` | 1 identifier (username or email), 2 password |
| 111 `email_address` (v4) | 1 email address |

Only one credential field is set per request.

## Response

| Field | Content |
| --- | --- |
| 1 `ok` (`LoginOk`) | 1 username, 2 access token, 3 stored credential (bytes), 4 `access_token_expires_in` (seconds) |
| 2 `error` | enum, see below |
| 3 `challenges` | 1 `challenges[]` |
| 5 `login_context` | Bytes to echo back |

Errors observed: `1` invalid credentials, `3` unsupported protocol version, `6` and `8` too many attempts (retry later).

## Challenges

`Challenge` is a oneof:

| Field | Challenge | Solution |
| --- | --- | --- |
| 1 | `HashcashChallenge`: 1 prefix (bytes), 2 length | `HashcashSolution`: 1 suffix (16 bytes), 2 duration (1 seconds, 2 nanos) |
| 2 | `CodeChallenge`: 1 method, 2 code length | `CodeSolution`: 1 code |

The solution is wrapped as `ChallengeSolution { 1 hashcash | 2 code }`, so a code answer is `3 → 1 → 2 → 1`. The client sends back the server's `login_context` with the solutions. Hashcash is the algorithm in [02-client-token.md](02-client-token.md#hashcash) with `login_context` as the seed source. The real elapsed time goes in the duration.

The descriptors also contain `Finish.nonce`, `Redirect.interaction_url` and `InteractionSolution.interact_ref`, with the diagnostics `auth_challenge_not_found`, `ChallengeNotSet`, `Nonce mismatch` and `mismatched nonce`. Which challenge the server picks depends on the account and server state. A client should fail clearly on a challenge type it does not implement.

## Flows

### Email code (v4, field 111)

Static: `p.fg8.b` (`classes.dex`) turns `p.ig8` into `EsAuthenticateCredentials.EmailAddress`. In Orbit, `LoginRequest` v4 field 111 at file offset `0x1757294` selects auxiliary 15, whose relocation at `0x175b328` references the `spotify.login5.v4.identifiers.EmailAddress` table.

1. `v4/login` with `client_info`, `flow_id` and `email_address`.
2. The server answers with `challenges` (Hashcash first, then `CodeChallenge`) and a `login_context`.
3. Spotify emails a code. Its length comes from `CodeChallenge` field 2.
4. Send the code solution with the same `flow_id` and `login_context`.
5. `LoginOk` returns the access token and a stored credential.

**Live:** this flow returned `LoginOk`, and the stored credential restored the session after the app was force-stopped. It needs no password and changes nothing on the account. Android autofill should be cancelled for the code field so the code is not offered as a password.

### Google (v4 and v3, field 108)

Static: Orbit names `spotify.login5.v3.credentials.GoogleSignInCredential` (1 `auth_code`, 2 `redirect_uri`) and the v4 message of the same name, whose mini-table lists three string fields in the order `auth_code`, `redirect_uri`, `id_token` (fields 1, 2, 3). librespot's v3 schema places it at `LoginRequest` field 108. In `classes.dex`, `p.fg8.b` builds `EsAuthenticateCredentials.GoogleSignInCredentials` in two ways: an auth code with an optional redirect address (`p.n450`, Google Sign-In), or an ID token alone (`p.o450`, Credential Manager, behind `enable_credential_manager_google_sign_in`). The Google client is `1046568431490-ij1gi5shcp2gtorls09frkc56d4mjbe2.apps.googleusercontent.com` (`serverClientId`, `classes4.dex`), the same one `accounts.spotify.com/login/google` sends to Google with `redirect_uri=https://accounts.spotify.com/login/google/redirect`.

Without Google Play services, Spotifeur opens Google's authorization page for that client and that redirect address with `response_type=code id_token`, a `state` and a `nonce`. Google answers with both in the fragment of the redirect, which the WebView stops before it loads. The ID token is sent alone first (v4, field 3), then the code with the redirect address (fields 1 and 2), each with its own `flow_id`.

Errors seen: 1 (`INVALID_CREDENTIALS`) for a made-up code or token, 5 (`UNKNOWN_IDENTIFIER`) for a real Google account that is not linked to a Spotify account.

**Live, 2026-11-11:** v4 field 108 with a made-up code, and with a made-up ID token in field 3, answered error 1, not HTTP 400: the server takes both as credentials. Google's page opened in the WebView once its user agent no longer announced a WebView, and accepted `response_type=code id_token` for this client. A real Google account no longer linked to its Spotify account got error 5 with the code. A sign-in with a linked account was not run.

### Password (v4 and v3, field 101)

Static: `p.fg8.b` builds `EsAuthenticateCredentials.LegacyV3Password` from `p.lg8` (constructor at `0x32cbb6`) and, in a separate branch, a `NativePassword` message (`0x32cc06`). Both exist in this build. Orbit names `spotify.login5.v4.credentials.NativePassword` with 1 `id` and 2 `password`.

**Live, 2026-11-10, synthetic identifier:** v4 with `flow_id` and field 101 answered error 1 (`INVALID_CREDENTIALS`) at once, without a challenge; v4 with an unused field (113) answered HTTP 400, so field 101 is a credential v4 knows. v3 field 101 answered with a Hashcash challenge, then error 1 after the solution. Spotifeur sends v4 first and falls back to v3 only when v4 is refused as a protocol (HTTP 400 or error 3). Whether a password login succeeds for a real account was not established here. The v4 descriptors also hold `InteractionChallenge`, `Redirect.interaction_url` and `Recaptcha.site_key`: a challenge of those kinds is reported to the user, who can use the email code instead.

### Stored credential (v3, field 100)

Send the username and the stored credential bytes from a previous `LoginOk`. The response is a new access token and possibly a rotated stored credential: replace the saved one when that happens. The stored credential is the only long-lived secret. Keep it encrypted (for example with an Android Keystore key) and never log it.

## Token lifetime

Treat the access token as expired a little before `access_token_expires_in` (for example 10% early, at most 30 s). Renew with the stored credential when a request needs it.

## Native side

The DEX keeps the native Login5 configuration in `com.spotify.authentication.login5.Login5Configuration` (`clientId`, `deviceId`, `protocolVersion`, which defaults to 3). Call sites:

1. `p.xwn1.run()` (`classes3.dex`) builds the connectivity scope, sets protocol 3 at `0x656506` and calls `NativeLogin5SetupImpl.create` at `0x65651a`.
2. `p.jmn0.run()` (`classes4.dex`) builds `UnencryptedCredentials(username, byte[])`, an `AccessToken` of type `Bearer` with `expiresAt` in Unix seconds (`Date.getTime() / 1000`, 0 when unknown), sets protocol 3 at `0x48c69a` and calls `NativeOAuthSetupImpl.create` at `0x48c744`.
3. `p.g54.run()` (`classes2.dex`) calls `NativeSession.createNativeSessionWithoutAp` at `0x265d32`.

`UnencryptedCredentials` takes the raw stored-credential bytes, not a Base64 or UTF-8 string. The mapping from the HTTP `stored_credential` to the native `AuthBlob` happens inside native code and was not located independently. See [08-native-engine.md](08-native-engine.md).

The access-token renewal states logged by `Login5OAuthClient` are: expired or missing from cache (`0x35f1fa`), network fetch (`0x36e83d`), back-off (`0x392daf`), forced renewal after a product change (`0x38764b`) and stored-credential change (`0x36ae14`).
