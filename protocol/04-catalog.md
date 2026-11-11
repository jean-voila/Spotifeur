# Catalog: metadata, search, images, lyrics and radio

## Extended Metadata

Entities are hydrated in batches. The native library prepares three routes in the procedure at `0xcc574c` (`ADRP` at `0xcc6248`, `0xcc6318` and `0xcc6614`):

```text
POST spclient/extended-metadata/v0/extended-metadata
POST spclient/extended-metadata/v3/extensions/batch-get
GET  spclient/extended-metadata/v3/configs/get-all
```

v0 is enough for everything below.

### Request: `BatchedEntityRequest`

Descriptor `extended_metadata.proto` `[0x66df3c, 0x66e52d)`.

| Field | Content |
| --- | --- |
| 1 `header` | Optional: country, catalogue |
| 2 `entity_request[]` | 1 `entity_uri`, 2 `query[]` |

Each `ExtensionQuery` has 1 `extension_kind` (enum) and 2 `etag`.

### Response: `BatchedExtensionResponse`

| Path | Content |
| --- | --- |
| 2 `extended_metadata[]` | One array per extension kind |
| 2 → 1 | Array header; field 1 = provider status (0 or 200 when fine) |
| 2 → 3 `extension_data[]` | Per entity |
| 2 → 3 → 1 | Entity header; field 1 = status. The header also carries a cache TTL and an offline TTL. |
| 2 → 3 → 2 | `entity_uri` |
| 2 → 3 → 3 | `google.protobuf.Any`; field 2 = the serialized extension |

Skip arrays or entities whose status is neither 0 nor 200.

### Through the native core

The engine has its own entry point, `spotify.metadata_esperanto.proto.ClassicMetadataService` (descriptor `metadata_esperanto.proto`):

| Method | Request | Response |
| --- | --- | --- |
| `GetEntity` | `GetEntityRequest`: 1 `uri` | `GetEntityResponse`: 1 `MetadataItem` |
| `MultigetEntity` | `MultiRequest`: 1 `uris[]` | `MultiResponse`: 1 `items[]` |

`MetadataItem` (`metadata_cosmos.proto`) is a oneof: 1 `error` (sint32), 2 `artist`, 3 `album`, 4 `track`, 5 `show`, 6 `episode`, each the `metadata.proto` message described below.

**Live, 2026-11-11:** `MultigetEntity` for five tracks and two albums returned seven items in request order, in 0.65 s on a warm engine. The engine sent its own `extended-metadata/v0/extended-metadata` request to the regional host given by `apresolve` (`gew1-spclient.spotify.com`), not to `spclient.wg.spotify.com`. Spotifeur uses this path when the HTTP request leaves most of a batch without data.

The request header (`BatchedEntityRequestHeader`, descriptor at `0x66df3c`: 1 `country`, 2 `catalogue`, 3 `task_id`) is optional. Spotifeur sends none, and only when most of a batch comes back without data asks again with the account's `catalogue` attribute; the statuses of such a response go to the diagnostic report. Whether the header changes anything for an account was not established.

### Extension kinds used

| Kind | Extension | Payload |
| --- | --- | --- |
| 5 | `AUDIO_FILES` | `AudioFilesExtensionResponse`, see [07-audio-delivery.md](07-audio-delivery.md) |
| 8 | Artist | `metadata.Artist` |
| 9 | Album | `metadata.Album` |
| 10 | Track | `metadata.Track` |

### Artist page extensions

The official artist page reads more than `metadata.Artist`. The `ExtensionKind` enum (`extension_kind.proto`, values listed from the descriptor at `0x66eb0f`) names them; these were requested on an artist or track URI like any other kind:

| Kind | Name | Payload (`type_url`) | Content |
| --- | --- | --- | --- |
| 152 | `POPULAR_RELEASES` | `spotify.creators.v1.PopularReleases` | 1 `release[]`, each 1 = album URI |
| 165 | `RELATED_CREATORS_SECTION` | `spotify.creators.v1.RelatedCreators` | 1 → 1 `artist[]`, each 1 = artist URI |
| 169 | `TOP_PLAYABLES_SECTION` | `spotify.creators.v1.TopPlayables` | 1 → 1 `track[]`, each 1 = track URI |
| 185 | `ON_PLATFORM_REPUTATION_TRAIT` | `spotify.contentagnostic.v2.OnPlatformReputationTrait` | artist: 1 = a count that is not always the monthly listeners (see below), 7 → 1 → 1 badge text ("Top Artists"), 9 → 1 not established; track: 3 = play count (varints) |
| 200 | `CREATOR_PLAYLISTS_SECTION` | `spotify.creators.v1.CreatorPlaylists` | 1 → 1 `playlist[]` |
| 203 | `CREATOR_APPEARS_ON_SECTION` | `spotify.creators.v1.CreatorAppearsOn` | 1 → 1 `album[]` |
| 206 | `ARTIST_HEADLINE` | `spotify.artistsectionprovider.v1.ArtistHeadline` | upcoming release card |

**Live, 2026-11-10:** kind 185 on an artist returned its monthly listeners in field 1 (24,738,114), and on four tracks their play counts in field 3 (for example 1,361,093,322). One request for a single track came back with an empty payload, so a missing value is normal. Kind 60 (`STREAM_COUNT`) answered 404 for tracks, albums and artists. On an album URI, kind 185 answered 404.

**Live, 2026-11-11:** on a small artist, kind 185 field 1 was 415, while the official app showed 27.4k monthly listeners and `creatorabout` gave 27,382. Field 1 matches the monthly listeners on some artists only, so Spotifeur takes the count from `creatorabout` alone.

```text
GET spclient/creatorabout/v0/artist/{artistId}/about?fields=artist,listenerCount,monthlyListenerRank,isVerified
Accept: application/json
```

Static: the two query strings are in `classes5.dex`. **Live:** the JSON has `name`, `isVerified`, `mainImageUrl`, `headerImage.uri`, `biography` and `autobiography.links`. **Live, 2026-11-11:** with the `fields` query above, the JSON also has `monthlyListeners`, `monthlyListenersDelta` and `globalChartPosition`; without `fields` it has only `artistGid`. `pathfinder/v2/query` on `api-partner.spotify.com` answered 403 to the Login5 token, whatever the persisted query hash.

### `metadata.proto`

Descriptor `[0x6713c0, 0x6737f4)`. Integers such as duration and year are `sint32` (ZigZag).

**Track:** 1 `gid` (16 bytes), 2 `name`, 3 `album`, 4 `artist[]` (2 = name), 7 `duration` ms, 11 `restriction[]`, 12 `file[]`, 13 `alternative[]`, 15 `preview[]`, 19 `availability`, 24 `original_audio`, 41 `audio_formats`.

**Album:** 1 `gid`, 2 `name`, 3 `artist[]`, 6 `date` (1 = year), 9 `cover[]`, 11 `disc[]` (3 = `track[]`, each with 1 = gid), 17 `cover_group` (1 = `image[]`).

**Artist:** 1 `gid`, 2 `name`, 4 `top_track[]` (1 = country code, 2 = `track[]`), 5–8 album groups (album, single, compilation, appears on; each group has 1 = `album[]`), 11 `portrait[]`, 12 `biography[]` (1 = text), 15 `related[]`, 17 `portrait_group` (1 = `image[]`).

**Image:** 1 `file_id` (20 bytes), 2 `size`, 3 `width`, 4 `height`.

GIDs become URIs with base-62: 16 bytes → 22 characters (`spotify:track:…`).

### Unavailable releases

Playlists can keep a release that is no longer playable. Its Track metadata then has no `file` (field 12) and lists one or more `alternative` tracks (field 13). Play the first alternative that has files, and keep the original URI for display and queue identity. No search by name is needed: Spotify names the replacement. **Live:** some old remastered tracks returned HTTP 403 for audio until the alternative was followed.

### Related projections

`spotify.cosmos_util.proto.ArtistMetadata` (`0x639560`: link, name, portraits) and `AlbumMetadata` (`0x63931c`: artists, link, name, covers, year) exist for Cosmos views. The collection artist view goes through `CollectionService/StreamArtistView` (`p.r5h.d` in `classes.dex`, descriptor `collection_get_artist_view_request.proto` `[0x5aa764, 0x5aaa81)`). Where editorial artist sections come from was not established.

## Images

```text
https://i.scdn.co/image/{image_id_hex}
https://mosaic.scdn.co/640/{id1}{id2}{id3}{id4}
```

An image ID is 20 bytes, so 40 hex characters. The first 8 characters are a family and the next 8 a size:

| Family | Largest | Smaller sizes |
| --- | --- | --- |
| `ab67616d` (album covers) | `0000b273` | `00004851`, `00001e02` |
| `ab676161` (artist portraits) | `0000e5eb` | `0000f178`, `00005174` |

Replacing a smaller size with the largest under the same hash gives the full-size image. A playlist without its own picture uses a mosaic of the covers of its first four distinct albums, or the first cover when there are fewer than four.

## Search

```text
GET spclient/searchview/v3/search
    ?query={text}
    &locale={BCP 47}
    &entity_types=album,artist,playlist,track
    &limit=20
    &page_token={token or empty}
    &album_states=live,prerelease
    &request_id={UUID}
    &timestamp={ms}
Accept: application/protobuf
```

Static: `p.rd51.a/d` (`classes11.dex`), called from `p.vi11.Q +0x33` and `p.vi11.R +0x4e` (`classes7.dex`). `p.g370.b` (`classes2.dex`) maps the annotations `Lp/ay30;`, `Lp/wyq0;` and `Lp/yyq0;` to GET, POST and PUT.

`MainViewResponse` field 1 is `hits[]`. Each `Entity` has 1 `uri`, 2 `name`, 3 image URI, then a projection by type: 5 track (4 = artists, each with 2 = name; 14 = track type, where 3 and 4 are video entries; 18 → 1 = duration in seconds), 6 album (1 = artist names), 7 playlist (5 = owner name). Field 4 of the response is the next page token; pass it back as `page_token`.

Related routes: `GET searchview/v3/native`, `GET searchview/v3/autocomplete` (`p.rd51.c/e`). Typed autocomplete (`p.kea1.a` via `p.m021.l +0x62`) sends `query`, `locale`, comma-separated `entity_types`, `limit`, `request_id` and `timestamp`.

## Other annotated routes

| Route | Interface |
| --- | --- |
| `GET track-entity-view/v2/track/{id}` | `p.rm30.a` (`classes11.dex`); a Cosmos variant `hm://track-entity-view/v2/track/` also exists |
| `GET creatorabout/v0/artist/{artistId}/about` | `p.gxn.a/b` (`classes5.dex`) |
| `GET playlist/v2/resolve-uri/{uri}` | `p.dss0.a` (`classes2.dex`) |

## Lyrics

```text
GET spclient/color-lyrics/v2/track/{track_id}?format=json&vocalRemoval=false&market=from_token
```

HTTP 404 means no lyrics. The JSON has `lyrics.syncType` (`UNSYNCED`, `LINE_SYNCED`, `SYLLABLE_SYNCED`) and `lyrics.lines[]` with `startTimeMs` (as a string) and `words`. Lines containing only `♪` mark instrumental passages.

## Radio

```text
GET spclient/inspiredby-mix/v2/seed_to_playlist/{seed_uri}?response-format=json
```

The answer is JSON. The radio is the playlist in `mediaItems[0].uri`; read it like any playlist. Seeds can be tracks, artists, albums or playlists.

**Live:** for an album seed, the service returns the radio of the album's main artist, the same playlist for every album of that artist. To get a radio that reflects the album, seed five tracks spread evenly over the album and interleave the five resulting radios without duplicates.

## Account attributes

```text
GET spclient/nebula/v1/configuration/attributes
```

Protobuf. Field 1 lists entries: 1 = name, 2 = value (2 = bool, 3 = int, 4 = string). These are the attributes the native session reads at startup. They include playback-license related values, so the native engine needs them before it can prepare audio. See [08-native-engine.md](08-native-engine.md).
