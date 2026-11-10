# Library and playlists (read side)

The user's library is split between two systems that sync differently:

- **Collection v2** for saved tracks, albums and artists: sets synced with page and sync tokens.
- **Playlist4** for the playlist list (rootlist) and playlist contents: revisions and diffs.

## Collection v2

Native references: `collection/v2/initialized` (`0x13c6a24`), `paging` (`0x13c572c`), `delta` (`0x13c50e4`), `write` (`0x13c4e30`). The procedure at `0x13c4db4` loads `application/vnd.collection-v2.spotify.proto` for `Accept` and `Content-Type`, with separate branches for pending updates, required delta and missing tokens.

```text
POST spclient/collection/v2/paging
POST spclient/collection/v2/delta
POST spclient/collection/v2/write
POST spclient/collection/v2/initialized
Accept: application/vnd.collection-v2.spotify.proto
Content-Type: application/vnd.collection-v2.spotify.proto
```

Descriptor `collection2v2.proto` `[0x601880, 0x601d37)`:

| Message | Fields |
| --- | --- |
| `InitializedRequest` | 1 `username`, 2 `set` |
| `InitializedResponse` | 1 `initialized` |
| `PageRequest` | 1 `username`, 2 `set`, 3 `pagination_token`, 4 `limit` |
| `PageResponse` | 1 `items[]`, 2 `next_page_token`, 3 `sync_token` |
| `DeltaRequest` | 1 `username`, 2 `set`, 3 `last_sync_token` |
| `DeltaResponse` | 1 `delta_update_possible`, 2 `items[]`, 3 `sync_token` |
| `WriteRequest` | 1 `username`, 2 `set`, 3 `items[]`, 4 `client_update_id` |
| `CollectionItem` | 1 `uri`, 2 `added_at` (seconds), 3 `is_removed`, 4 `context_uri`, 5 `item_data` |

Sets used: `collection` for saved tracks and albums, `artist` for followed artists.

**Paging.** Start with an empty token, limit 300, and follow `next_page_token` until it is empty. Items with `is_removed = 1` cancel earlier entries. Stop if a token repeats. Sort by `added_at` descending to match the official order.

**Writes.** Saving or removing an item is a `WriteRequest` with one `CollectionItem` (`is_removed` set for removal). `client_update_id` exists, but its presence does not prove the server deduplicates writes.

**Live:** adding a track that was not saved, reading the collection back, then removing it in a `finally` block and confirming the removal.

## Rootlist (playlist list)

```text
GET spclient/playlist/v2/user/{username}/rootlist
    ?decorate=revision,attributes,length,owner,capabilities,status_code
    &from={offset}
    &length=300
x-accept-list-items: audio-track
```

Static: a block at `0x13908b4` loads `playlist/v2/user/` with `/rootlist`, `/changes` and `/diff`.

Response `SelectedListContent`:

| Field | Content |
| --- | --- |
| 1 | `revision` |
| 2 | `length` (total) |
| 3 | `attributes` |
| 5 | `contents` (`ListItems`): 1 `pos`, 2 `truncated`, 3 `items[]` (1 = URI, 2 = attributes), 4 `meta_items[]`, 6 `continuation_token` |
| 6 | `diff` |
| 10 | `up_to_date` |
| 16 | `owner_username` |
| 18 | `capabilities` |
| 19 | `geoblock` |
| 20 | `changes_require_resync` |

`meta_items[i]` describes `items[i]` in the rootlist: 2 = attributes (1 name, 2 description, 3 picture ID, 13 sized pictures with 2 = URL), 3 = length, 5 = owner username, 9 = status code (ZigZag). Skip entries whose status is 4xx or 5xx (deleted or forbidden playlists).

Folders appear in the rootlist as `spotify:start-group:…` and `spotify:end-group:…` items. A client that does not show folders can drop these markers and keep the playlists in order.

Continue paging while `truncated = 1` or `pos + items < length`.

## Playlist contents

```text
GET spclient/playlist/v2/playlist/{id}
    ?decorate=revision,attributes,length,owner,capabilities,status_code
    &from={offset}
    &length=100
x-accept-list-items: audio-track
```

Same `SelectedListContent`. Items keep their order and duplicates. Hydrate the track URIs with [Extended Metadata](04-catalog.md#extended-metadata). Playlist attributes give the name (1), description (2), picture (3, 20 bytes) and sized pictures (13).

Static: the block at `0x13917e8` builds `playlist/v2/` with `GET` and `/changes` or `/diff`, the fields `revision`, `hint_revision`, `handlesContent`, and `x-accept-list-items` containing `audio-track`.

**Live:** paging a 1,600+ track playlist 100 items at a time, order and duplicates preserved.

**Reported, not reproduced:** a Free account (`on-demand=false`, `ads=true`) got HTTP 400 on the first page of a Spotify-owned playlist whose rootlist entry had length 0. On the Premium account used here, the Spotify-owned lists of the rootlist all answered 200, the AI DJ list (format attributes `lexicon_set_type=your_dj`, `lexicon_context_url=hm://lexicon-session-provider/…`) with length 0 and no items. Another account's personalized list answered 403 or 404 to the Free test account.

### Reading through the native core

`PlaylistDataService/Get` (see [06-playlist-writes.md](06-playlist-writes.md)) also reads contents, as the official app does. Request `{1: playlist_uri, 2: {4: {1: start, 2: length}, 8: true}, 3: {1: metadata policy, 4: {1: true, 9: true}}}`. Response `2 → 1` items (7 row ID, 18 URI), `2 → 2 → 1` playlist metadata: 1 URI, 2 name, 6 length, 7 description, 8 → 1 picture (`spotify:image:…`). **Live:** a 354-track playlist returned its first rows and length 354; the AI DJ list returned no items. Spotifeur uses it when Playlist4 over HTTP answers 400 (or another 4xx other than 401, 403 and 404), then the public embed page for the first page.

### Writes over HTTP

The Java layer also has `POST playlist/v2/playlist/{id}/changes` (`p.efe.a`, `classes10.dex`, body `ListChanges`: 1 `base_revision`, 2 `deltas`, 6 `nonces`) and `PUT playlist/v2/playlist/{id}/items` (`p.hrh1.a`, `classes3.dex`, body `ListUpdateRequest`: 1 `base_revision`, 2 `attributes`, 3 `items`, 4 `info`). Spotifeur writes through the native services instead; see [06-playlist-writes.md](06-playlist-writes.md).

## Folders

`your_library_request.proto` `[0x5d7c9c, 0x5d837f)`: `YourLibraryRequestHeader` has 16 `folder_id`, 17 `all_playlists`, 34 `fill_folders`. The response header `[0x5d770c, 0x5d7b67)` has 15 `folder_name` and 20 `parent_folder_uri`. Mutations go through `RootlistModificationService/Modify` (`rootlist_modification_request.proto` `[0x5e2f7c, 0x5e3175)`: 1 `folder_uri`, 2 `ModificationRequest`).

## Permissions

The procedure at `0x13b0464` builds `playlist-permission/v1/playlist/{id}/permission`. `playlist_permission.proto` `[0x642284, 0x644b4d)` lists `can_view = 1`, `can_administrate_permissions = 2`, `can_edit_metadata = 4`, `can_edit_items = 5`. Read access and permissions are separate from the list contents. A client should only offer "add to playlist" for playlists the user owns (compare `owner_username` with the logged-in username) or can edit.

## Pinned items

Pins in the official app are a client feature. Spotifeur keeps its pins on the device only; nothing is sent to Spotify.
