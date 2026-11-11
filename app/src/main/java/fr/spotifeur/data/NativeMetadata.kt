package fr.spotifeur.data

import fr.spotifeur.playback.SpotifyNativeRuntime

internal const val CLASSIC_METADATA_SERVICE = "spotify.metadata_esperanto.proto.ClassicMetadataService"

/** `MultiRequest`: 1 `uris[]`. */
internal fun nativeMetadataRequest(uris: List<String>): ByteArray = proto { uris.forEach { writeString(1, it) } }

/**
 * `MultiResponse`: 1 `items[]`, each a `MetadataItem` oneof (1 error, 2 artist, 3 album, 4 track) holding
 * the same `metadata.proto` messages as Extended Metadata. Items follow the request order; when the
 * counts differ, each entity is keyed by its own gid.
 */
internal fun decodeNativeMetadata(response: Proto, uris: List<String>): Map<String, ByteArray> {
    val items = response.messages(1)
    return buildMap {
        items.forEachIndexed { index, item ->
            val (kind, bytes) = listOf(2 to "artist", 3 to "album", 4 to "track")
                .firstNotNullOfOrNull { (field, kind) -> item.bytes(field)?.let { kind to it } } ?: return@forEachIndexed
            val own = gidUri(kind, Proto(bytes).bytes(1)) ?: return@forEachIndexed
            val requested = uris.getOrNull(index)?.takeIf { items.size == uris.size && it.startsWith("spotify:$kind:") }
            put(requested ?: own, bytes)
        }
    }
}

/** Metadata of [uris] read through the native engine, decoded. */
internal suspend fun nativeMetadata(runtime: SpotifyNativeRuntime, uris: List<String>): Map<String, Music> =
    decodeNativeMetadata(runtime.serviceRequest(CLASSIC_METADATA_SERVICE, "MultigetEntity", nativeMetadataRequest(uris)), uris)
        .mapNotNull { (uri, bytes) ->
            val entity = Proto(bytes)
            when (metadataKind(uri)) {
                8 -> decodeMetadataArtist(entity)
                9 -> decodeMetadataAlbum(entity)
                10 -> decodeMetadataTrack(entity)
                else -> null
            }?.let { uri to it }
        }.toMap()
