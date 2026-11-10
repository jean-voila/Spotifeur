package fr.spotifeur.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

data class CatalogDetail(
    val item: Music,
    val tracks: List<Music>,
    val related: List<Music> = emptyList(),
    val nextPage: String? = null,
    val total: Int = tracks.size,
)

/** Native Extended Metadata and Playlist4 contracts carried by the supplied Android APK. */
internal class SpotifyCatalog(
    private val transport: SpotifyTransport,
    private val session: SpotifySession,
) {
    private val musicCache = ConcurrentHashMap<String, Music>()
    private val metadataCache = ConcurrentHashMap<String, ByteArray>()
    private val requests = Semaphore(4)
    /** Library sync reads one batch at a time, so a page the user opens is never queued behind it. */
    private val backgroundRequests = Semaphore(1)

    /** Metadata already on the device, so only what is new gets requested. */
    fun seed(items: List<Music>) = items.forEach { if (it.title.isNotEmpty()) musicCache.putIfAbsent(it.id, it) }

    suspend fun hydrate(uris: List<String>): List<Music> = coroutineScope {
        val valid = uris.mapNotNull(::canonicalMusicUri)
        val missing = valid.distinct().filter { musicCache[it] == null }
        val background = kotlin.coroutines.coroutineContext[BackgroundRequestsKey] != null
        missing.filter { metadataKind(it) != null }.chunked(40).map { chunk ->
            async {
                if (background) backgroundRequests.withPermit { requests.withPermit { fetchMetadata(chunk) } }
                else requests.withPermit { fetchMetadata(chunk) }
            }
        }.awaitAll()
        // Keep playlist order and duplicate tracks. Unsupported entities never enter the music model.
        valid.mapNotNull(musicCache::get)
    }

    suspend fun metadataBytes(uri: String): ByteArray {
        val canonical = canonicalMusicUri(uri) ?: throw SpotifyFailure("Invalid Spotify ID.")
        if (metadataKind(canonical) == null) throw SpotifyFailure("Metadata unavailable for this item.")
        if (metadataCache[canonical] == null) requests.withPermit { fetchMetadata(listOf(canonical)) }
        return metadataCache[canonical] ?: throw SpotifyFailure("This item is not available in the catalog.")
    }

    /** Metadata of several entities by URI, fetched in batches for the ones not cached yet. Unavailable ones are left out. */
    suspend fun metadata(uris: List<String>): Map<String, ByteArray> = coroutineScope {
        val valid = uris.mapNotNull(::canonicalMusicUri).filter { metadataKind(it) != null }.distinct()
        valid.filter { metadataCache[it] == null }.chunked(40)
            .map { chunk -> async { requests.withPermit { fetchMetadata(chunk) } } }.awaitAll()
        valid.mapNotNull { uri -> metadataCache[uri]?.let { uri to it } }.toMap()
    }

    suspend fun detail(item: Music, page: String? = null): CatalogDetail {
        if (item.kind == MusicKind.Playlist || item.kind == MusicKind.Radio) return playlist(item, page)
        val metadata = Proto(metadataBytes(item.id))
        return when (item.kind) {
            MusicKind.Album -> {
                val album = decodeMetadataAlbum(metadata) ?: item
                val tracks = hydrate(metadata.messages(11).flatMap { disc ->
                    disc.messages(3).mapNotNull { gidUri("track", it.bytes(1)) }
                })
                remember(tracks)
                CatalogDetail(album, tracks, metadata.messages(3).mapNotNull(::decodeMetadataArtist))
            }
            MusicKind.Artist -> {
                val artist = decodeMetadataArtist(metadata) ?: item
                val groups = metadata.messages(4)
                val top = groups.firstOrNull { it.text(1) == java.util.Locale.getDefault().country }
                    ?: groups.firstOrNull()
                val tracks = hydrate(top?.messages(2).orEmpty().mapNotNull { gidUri("track", it.bytes(1)) })
                val albums = (5..8).flatMap { field ->
                    metadata.messages(field).flatMap { it.messages(1) }.mapNotNull(::decodeMetadataAlbum)
                }.distinctBy(Music::id)
                val relatedArtists = metadata.messages(15).mapNotNull(::decodeMetadataArtist)
                remember(tracks + albums + relatedArtists)
                CatalogDetail(artist, tracks, albums + relatedArtists)
            }
            MusicKind.Track -> {
                val track = decodeMetadataTrack(metadata) ?: item
                val album = metadata.message(3)?.let(::decodeMetadataAlbum)
                val artists = metadata.messages(4).mapNotNull(::decodeMetadataArtist)
                remember(listOf(track) + listOfNotNull(album) + artists)
                CatalogDetail(track, listOf(track), listOfNotNull(album) + artists)
            }
            else -> throw SpotifyFailure("This page is not available.")
        }
    }

    // Decoding hundreds of entities is CPU work: keep it off the caller's (often main) thread.
    private suspend fun fetchMetadata(uris: List<String>): Unit = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val body = proto {
            uris.forEach { uri ->
                val kind = metadataKind(uri) ?: return@forEach
                writeByteArray(2, proto {
                    writeString(1, uri)
                    writeByteArray(2, proto { writeEnum(1, kind) })
                })
            }
        }
        val response = authenticatedSpotifyRequest(
            transport, session, spotifyUrl("extended-metadata/v0/extended-metadata"),
            "Spotify metadata", body,
        )
        decodeMetadataExtensions(response).forEach { (uri, bytes) ->
            metadataCache[uri] = bytes
            val entity = Proto(bytes)
            val music = when (metadataKind(uri)) {
                8 -> decodeMetadataArtist(entity)
                9 -> decodeMetadataAlbum(entity)
                10 -> decodeMetadataTrack(entity)
                else -> null
            }
            if (music != null) {
                musicCache[uri] = music
                musicCache[music.id] = music
            }
        }
    }

    private suspend fun playlist(item: Music, page: String?): CatalogDetail {
        val uri = canonicalMusicUri(item.id) ?: throw SpotifyFailure("Invalid Spotify playlist.")
        val id = uri.substringAfterLast(':')
        if (!uri.startsWith("spotify:playlist:")) throw SpotifyFailure("Invalid Spotify playlist.")
        val from = page?.toIntOrNull()?.takeIf { it >= 0 } ?: 0
        val url = spotifyUrl("playlist/v2/playlist/$id").newBuilder()
            .addQueryParameter("from", from.toString())
            .addQueryParameter("length", "100")
            .addQueryParameter("decorate", "revision,attributes,length,owner,capabilities,status_code")
            .build()
        val response = authenticatedSpotifyRequest(transport, session, url, "Spotify playlist")
        val decoded = decodePlaylistPage(response, item)
        hydrate(decoded.uris)
        // Keep the playlist's order, local file references included.
        val tracks = decoded.entries.mapNotNull { entry -> spotifyLocalTrack(entry) ?: musicCache[entry] }
        val artwork = decoded.item.imageUrl ?: playlistMosaic(tracks)
        val playlist = decoded.item.copy(imageUrl = artwork)
        musicCache[item.id] = playlist
        return CatalogDetail(
            item = playlist,
            tracks = tracks,
            nextPage = if (decoded.hasMore && decoded.rawCount > 0) (from + decoded.rawCount).toString() else null,
            total = decoded.total,
        )
    }

    private fun remember(items: List<Music>) = items.forEach { musicCache[it.id] = it }
}

internal fun spotifyUrl(path: String): HttpUrl = "https://spclient.wg.spotify.com/".toHttpUrl()
    .newBuilder().addPathSegments(path).build()

internal suspend fun authenticatedSpotifyRequest(
    transport: SpotifyTransport,
    session: SpotifySession,
    url: HttpUrl,
    operation: String,
    body: ByteArray? = null,
    contentType: String = "application/x-protobuf",
): ByteArray {
    suspend fun send(headers: SpotifySession.Headers): ByteArray {
        val builder = Request.Builder().url(url)
            .header("Authorization", "Bearer ${headers.user}")
            .header("client-token", headers.client)
            .header("Accept", contentType)
        if (body != null) builder.post(body.toRequestBody(contentType.toMediaType()))
        else builder.header("x-accept-list-items", "audio-track")
        return transport.send(builder.build(), operation)
    }
    val headers = session.headers(url.host)
    return try {
        send(headers)
    } catch (failure: SpotifyFailure) {
        if (failure.status != 401) throw failure
        send(session.headers(url.host, rejected = headers))
    }
}

internal fun decodeMetadataExtensions(response: ByteArray): Map<String, ByteArray> = buildMap {
    Proto(response).messages(2).forEach { array ->
        val providerStatus = array.message(1)?.number(1) ?: 0
        if (providerStatus != 0 && providerStatus != 200) return@forEach
        array.messages(3).forEach extension@ { data ->
            val status = data.message(1)?.number(1) ?: 0
            if (status != 0 && status != 200) return@extension
            val uri = data.text(2)?.let(::canonicalMusicUri) ?: return@extension
            val value = data.message(3)?.bytes(2) ?: return@extension
            put(uri, value)
        }
    }
}

internal fun metadataKind(uri: String): Int? = when {
    uri.startsWith("spotify:artist:") -> 8
    uri.startsWith("spotify:album:") -> 9
    uri.startsWith("spotify:track:") -> 10
    else -> null
}

/** Track.alternative is supplied by the account-aware metadata service, not a title search. */
internal fun playableTrackUri(original: String, metadata: Proto): String {
    if (metadata.messages(12).isNotEmpty()) return original
    val alternatives = metadata.messages(13)
    val available = alternatives.firstOrNull { it.messages(12).isNotEmpty() && gidUri("track", it.bytes(1)) != null }
        ?: alternatives.firstOrNull { gidUri("track", it.bytes(1)) != null }
    return available?.let { gidUri("track", it.bytes(1)) } ?: original
}

internal fun canonicalMusicUri(value: String): String? {
    val pieces = value.split(':')
    val pair = when {
        pieces.size == 3 && pieces[0] == "spotify" -> pieces[1] to pieces[2]
        pieces.size == 5 && pieces[0] == "spotify" && pieces[1] == "user" && pieces[3] == "playlist" ->
            "playlist" to pieces[4]
        else -> return null
    }
    if (pair.first !in setOf("track", "album", "artist", "playlist") ||
        pair.second.length !in 21..22 || pair.second.any { it !in BASE62 }
    ) return null
    return "spotify:${pair.first}:${pair.second}"
}

private const val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

internal fun gidUri(kind: String, gid: ByteArray?): String? {
    if (gid == null || gid.size != 16) return null
    var value = BigInteger(1, gid)
    val base = BigInteger.valueOf(62)
    val text = StringBuilder()
    while (value.signum() != 0) {
        val pair = value.divideAndRemainder(base)
        text.append(BASE62[pair[1].toInt()])
        value = pair[0]
    }
    return "spotify:$kind:${text.reverse().toString().padStart(22, '0')}"
}

internal fun decodeMetadataTrack(track: Proto, parentAlbum: Music? = null): Music? {
    val uri = gidUri("track", track.bytes(1)) ?: return null
    val title = track.text(2)?.takeIf(String::isNotBlank) ?: return null
    val album = track.message(3)?.let(::decodeMetadataAlbum) ?: parentAlbum
    val artists = track.messages(4).mapNotNull { it.text(2)?.takeIf(String::isNotBlank) }
    return Music(
        id = uri, title = title, subtitle = artists.joinToString(", ").ifBlank { album?.subtitle.orEmpty() },
        kind = MusicKind.Track, artwork = Math.floorMod(uri.hashCode(), 6), imageUrl = album?.imageUrl,
        durationMs = track.number(7)?.let(::unZigZag)?.coerceAtLeast(0) ?: 0,
    )
}

internal fun decodeMetadataAlbum(album: Proto): Music? {
    val uri = gidUri("album", album.bytes(1)) ?: return null
    val title = album.text(2)?.takeIf(String::isNotBlank) ?: return null
    val artists = album.messages(3).mapNotNull { it.text(2) }.joinToString(", ")
    val images = album.message(17)?.messages(1).orEmpty() + album.messages(9)
    val tracks = album.messages(11).flatMap { it.messages(3) }.mapNotNull { gidUri("track", it.bytes(1)) }
    val year = album.message(6)?.number(1)?.let(::unZigZag)
    return Music(
        id = uri, title = title, subtitle = artists, kind = MusicKind.Album,
        artwork = Math.floorMod(uri.hashCode(), 6), imageUrl = metadataImage(images), tracks = tracks,
        note = year?.takeIf { it > 0 }?.toString().orEmpty(),
    )
}

internal fun decodeMetadataArtist(artist: Proto): Music? {
    val uri = gidUri("artist", artist.bytes(1)) ?: return null
    val title = artist.text(2)?.takeIf(String::isNotBlank) ?: return null
    val images = artist.messages(17).flatMap { it.messages(1) } + artist.messages(11)
    return Music(
        id = uri, title = title, subtitle = "Artist", kind = MusicKind.Artist,
        artwork = Math.floorMod(uri.hashCode(), 6), imageUrl = metadataImage(images),
        note = artist.messages(12).firstNotNullOfOrNull { it.text(1) }.orEmpty(),
    )
}

private fun metadataImage(images: List<Proto>): String? = images.sortedByDescending {
    it.number(3)?.let(::unZigZag) ?: 0
}.firstNotNullOfOrNull { image ->
    image.bytes(1)?.takeIf { it.size == 20 }?.let { "https://i.scdn.co/image/${it.metadataHex()}" }
}

internal fun unZigZag(number: Int): Int = (number ushr 1) xor -(number and 1)
private fun ByteArray.metadataHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

/**
 * Spotify image identifiers carry their size after an 8-character family prefix. Search and some
 * lists name the thumbnail; the same picture exists at the largest size under the same hash.
 */
private val LARGEST_IMAGE = mapOf(
    "ab67616d" to ("0000b273" to setOf("00004851", "00001e02")),
    "ab676161" to ("0000e5eb" to setOf("0000f178", "00005174")),
)

/**
 * The cover Spotify shows for a playlist without its own picture: the covers of its first four
 * different albums in a 2×2 grid, served by Spotify's mosaic CDN. Fewer than four: the first cover.
 */
internal fun playlistMosaic(tracks: List<Music>): String? {
    val covers = tracks.mapNotNull { largestSpotifyImage(it.imageUrl) }.distinct()
    val ids = covers.mapNotNull { Regex("^https://i\\.scdn\\.co/image/([0-9a-f]{40})$").matchEntire(it)?.groupValues?.get(1) }
    return if (ids.size >= 4) "https://mosaic.scdn.co/640/${ids.take(4).joinToString("")}" else covers.firstOrNull()
}

internal fun largestSpotifyImage(url: String?): String? {
    if (url == null) return null
    val match = Regex("^https://i\\.scdn\\.co/image/([0-9a-f]{8})([0-9a-f]{8})([0-9a-f]{24})$").matchEntire(url) ?: return url
    val (family, size, hash) = match.destructured
    val (largest, smaller) = LARGEST_IMAGE[family] ?: return url
    return if (size in smaller) "https://i.scdn.co/image/$family$largest$hash" else url
}

internal fun spotifyArtwork(value: String?): String? = largestSpotifyImage(rawSpotifyArtwork(value))

private fun rawSpotifyArtwork(value: String?): String? {
    if (value == null) return null
    if (value.matches(Regex("spotify:image:[a-fA-F0-9]{40}"))) {
        return "https://i.scdn.co/image/${value.substringAfterLast(':')}"
    }
    val url = runCatching { value.toHttpUrl() }.getOrNull() ?: return null
    return url.takeIf {
        it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
            (it.host == "i.scdn.co" || it.host == "mosaic.scdn.co" || it.host.endsWith(".spotifycdn.com"))
    }?.toString()
}

internal data class PlaylistPage(
    val item: Music, val uris: List<String>, val total: Int, val rawCount: Int, val hasMore: Boolean,
    /** Tracks and local file references, in playlist order. */
    val entries: List<String> = uris,
)

internal fun decodePlaylistPage(bytes: ByteArray, fallback: Music): PlaylistPage {
    val list = Proto(bytes)
    val contents = list.message(5)
    val raw = contents?.messages(3).orEmpty()
    val entries = raw.mapNotNull { row ->
        val uri = row.text(1) ?: return@mapNotNull null
        if (uri.startsWith(SPOTIFY_LOCAL_PREFIX)) uri else canonicalMusicUri(uri)?.takeIf { it.startsWith("spotify:track:") }
    }
    val uris = entries.filter { it.startsWith("spotify:track:") }
    val attrs = list.message(3)
    val image = playlistImage(attrs) ?: fallback.imageUrl
    val total = list.number(2) ?: raw.size
    return PlaylistPage(
        item = fallback.copy(
            title = attrs?.text(1)?.takeIf(String::isNotBlank) ?: fallback.title,
            subtitle = fr.spotifeur.utils.formatSongCount(total),
            imageUrl = image, note = attrs?.text(2).orEmpty(), ownerUsername = list.text(16) ?: fallback.ownerUsername,
        ),
        uris = uris, total = total, rawCount = raw.size, entries = entries,
        hasMore = contents?.number(2) == 1 || (contents?.number(1) ?: 0) + raw.size < total,
    )
}

internal fun playlistImage(attrs: Proto?): String? {
    attrs ?: return null
    val sized = attrs.messages(13).firstNotNullOfOrNull { spotifyArtwork(it.text(2)) }
    if (sized != null) return sized
    val picture = attrs.bytes(3) ?: return null
    return if (picture.size == 20) "https://i.scdn.co/image/${picture.metadataHex()}"
    else spotifyArtwork(picture.decodeToString())
}

/**
 * A local file reference of a Spotify playlist: `spotify:local:{artist}:{album}:{title}:{seconds}`,
 * each part URL-encoded. The audio is looked up among the device's files.
 */
internal fun spotifyLocalTrack(uri: String): Music? {
    if (!uri.startsWith(SPOTIFY_LOCAL_PREFIX)) return null
    val parts = uri.removePrefix(SPOTIFY_LOCAL_PREFIX).split(':')
    if (parts.size < 4) return null
    fun decode(value: String) = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    val title = decode(parts[2]).ifBlank { return null }
    return Music(
        id = uri, title = title, subtitle = decode(parts[0]), kind = MusicKind.Track,
        album = decode(parts[1]), durationMs = (parts[3].toIntOrNull() ?: 0) * 1000,
    )
}
