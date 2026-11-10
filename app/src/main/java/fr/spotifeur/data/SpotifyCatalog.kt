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
    /** Artist pages only: what the official artist page shows besides tracks and releases. */
    val artist: ArtistProfile? = null,
)

/**
 * The parts of an artist page that are not plain metadata. Release lists hold album IDs found in
 * [CatalogDetail.related], in the order Spotify gives them.
 */
data class ArtistProfile(
    val monthlyListeners: Long? = null,
    val worldRank: Int? = null,
    /** Play counts of the popular tracks, by track ID. */
    val playCounts: Map<String, Long> = emptyMap(),
    val popularReleases: List<String> = emptyList(),
    val albums: List<String> = emptyList(),
    val singles: List<String> = emptyList(),
    val compilations: List<String> = emptyList(),
    val appearsOn: List<String> = emptyList(),
    val biography: String = "",
    val headerImageUrl: String? = null,
    /** Counts YouTube Music only gives rounded ("28.3M"): shown in the short form. */
    val approximate: Boolean = false,
)

/** Native Extended Metadata and Playlist4 contracts carried by the supplied Android APK. */
internal class SpotifyCatalog(
    private val transport: SpotifyTransport,
    private val session: SpotifySession,
    /** Reads a playlist page through the native core when Playlist4 over HTTP refuses it. */
    private val nativePlaylist: (suspend (uri: String, from: Int, length: Int, fallback: Music) -> PlaylistPage)? = null,
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
                // The library sync stores artist pages too: it keeps to metadata, the page completes itself when opened.
                val background = kotlin.coroutines.coroutineContext[BackgroundRequestsKey] != null
                val extras = if (background) ArtistExtensions(null, emptyList(), emptyList(), emptyList()) else artistExtensions(artist.id)
                val topUris = top?.messages(2).orEmpty().mapNotNull { gidUri("track", it.bytes(1)) }
                    .ifEmpty { extras.topTracks }
                val tracks = hydrate(topUris)
                // Album, single, compilation and "appears on" groups, as the official page separates them. The
                // groups only carry album IDs: their metadata is read, newest first, up to a limit per group.
                val groupIds = (5..8).associateWith { field ->
                    metadata.messages(field).flatMap { it.messages(1) }.mapNotNull { gidUri("album", it.bytes(1)) }.distinct()
                        .take(if (field == 8) MAX_APPEARS_ON else MAX_RELEASES_PER_GROUP)
                }
                val described = if (background) emptyMap() else hydrate(groupIds.values.flatten()).associateBy(Music::id)
                val grouped = (5..8).associateWith { field ->
                    val inline = metadata.messages(field).flatMap { it.messages(1) }.mapNotNull(::decodeMetadataAlbum).associateBy(Music::id)
                    groupIds[field].orEmpty().mapNotNull { described[it] ?: inline[it] }
                        .sortedByDescending { it.note.toIntOrNull() ?: 0 }
                }
                val listed = grouped.values.flatten().map(Music::id).toSet()
                val popular = extras.popularReleases.filter { it in listed } +
                    hydrate(extras.popularReleases.filterNot { it in listed }).map(Music::id)
                val albums = (grouped.values.flatten() + extras.popularReleases.mapNotNull(musicCache::get)).distinctBy(Music::id)
                val relatedArtists = metadata.messages(15).mapNotNull(::decodeMetadataArtist)
                    .ifEmpty { hydrate(extras.relatedArtists) }
                remember(tracks + albums + relatedArtists)
                val about = if (background) null else artistAbout(artist.id)
                val profile = if (background) null else ArtistProfile(
                    monthlyListeners = extras.monthlyListeners ?: about?.listeners,
                    worldRank = about?.rank,
                    playCounts = trackPlayCounts(tracks.map(Music::id)),
                    popularReleases = popular,
                    albums = grouped[5].orEmpty().map(Music::id),
                    singles = grouped[6].orEmpty().map(Music::id),
                    compilations = grouped[7].orEmpty().map(Music::id),
                    appearsOn = grouped[8].orEmpty().map(Music::id),
                    biography = cleanBiography(artist.note).ifBlank { about?.biography.orEmpty() },
                    headerImageUrl = about?.headerImage,
                )
                CatalogDetail(artist, tracks, albums + relatedArtists, artist = profile)
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
        val missing = requestMetadata(uris, catalogue = null)
        // A few missing items are unavailable releases. Most of a batch missing means the service refused it:
        // ask again stating the account's catalogue, as the request header allows.
        if (missing.isEmpty() || missing.size * 2 < uris.size) return@withContext
        val attributes = session.lastAttributes ?: try { spotifyAttributes(transport, session) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (auth: SpotifyAuthRequired) { throw auth }
            catch (_: java.io.IOException) { null }
        val catalogue = attributes?.get("catalogue")?.takeIf(String::isNotBlank) ?: return@withContext
        val still = requestMetadata(missing, catalogue)
        if (still.size < missing.size) {
            fr.spotifeur.data.diagnostics.DiagnosticLog.record("metadata", "recovered=${missing.size - still.size} of ${missing.size} with catalogue=$catalogue")
        }
    }

    /** One Extended Metadata batch. Returns the URIs the service did not describe. */
    private suspend fun requestMetadata(uris: List<String>, catalogue: String?): List<String> {
        val body = proto {
            if (catalogue != null) writeByteArray(1, proto { writeString(2, catalogue) })
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
        val decoded = decodeMetadataExtensions(response)
        decoded.forEach { (uri, bytes) ->
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
        val missing = uris.filter { metadataKind(it) != null && it !in decoded }
        if (missing.isNotEmpty()) recordMissingMetadata(response, uris.size, missing.size, catalogue)
        return missing
    }

    @Volatile private var lastMissingReport = 0L
    /** Statuses only, at most once every 30 s: enough to tell a refused catalogue from unavailable items. */
    private fun recordMissingMetadata(response: ByteArray, requested: Int, missing: Int, catalogue: String?) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastMissingReport < 30_000) return
        lastMissingReport = now
        val summary = runCatching { metadataStatusSummary(response) }.getOrDefault("unreadable")
        fr.spotifeur.data.diagnostics.DiagnosticLog.record("metadata",
            "missing=$missing of $requested header=${catalogue ?: "none"} $summary")
    }

    private suspend fun playlist(item: Music, page: String?): CatalogDetail {
        val uri = canonicalMusicUri(item.id) ?: throw SpotifyFailure("Invalid Spotify playlist.")
        val id = uri.substringAfterLast(':')
        if (!uri.startsWith("spotify:playlist:")) throw SpotifyFailure("Invalid Spotify playlist.")
        val from = page?.toIntOrNull()?.takeIf { it >= 0 } ?: 0
        val url = spotifyUrl("playlist/v2/playlist/$id").newBuilder()
            .addQueryParameter("from", from.toString())
            .addQueryParameter("length", PLAYLIST_PAGE.toString())
            .addQueryParameter("decorate", "revision,attributes,length,owner,capabilities,status_code")
            .build()
        val decoded = try {
            decodePlaylistPage(authenticatedSpotifyRequest(transport, session, url, "Spotify playlist"), item)
        } catch (failure: SpotifyFailure) {
            if (!playlistFallbackApplies(failure)) throw failure
            fallbackPlaylistPage(uri, from, item, failure)
        }
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

    /**
     * Playlist4 over HTTP refused the page (HTTP 400 was reported for some accounts). The native
     * core reads playlists the way the official app does; the public embed page is the last resort
     * for the first page.
     */
    private suspend fun fallbackPlaylistPage(uri: String, from: Int, item: Music, failure: SpotifyFailure): PlaylistPage {
        val native = nativePlaylist
        if (native != null) {
            try {
                val page = native(uri, from, PLAYLIST_PAGE, item)
                fr.spotifeur.data.diagnostics.DiagnosticLog.record("catalog",
                    "playlist http=${failure.status} fallback=native items=${page.rawCount} total=${page.total}")
                return page
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                fr.spotifeur.data.diagnostics.DiagnosticLog.record("catalog",
                    "playlist http=${failure.status} fallback=native failed ${error.javaClass.simpleName} \"${error.message}\"")
            }
        }
        if (from != 0) throw failure
        val public = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SpotifyPublicPlaylist.read(uri.substringAfterLast(':')) }
        } catch (error: java.io.IOException) {
            fr.spotifeur.data.diagnostics.DiagnosticLog.record("catalog",
                "playlist http=${failure.status} fallback=public failed ${error.javaClass.simpleName}")
            throw failure
        }
        fr.spotifeur.data.diagnostics.DiagnosticLog.record("catalog", "playlist http=${failure.status} fallback=public items=${public.tracks.size}")
        seed(public.tracks.filter { it.id.startsWith("spotify:track:") })
        val entries = public.tracks.map(Music::id)
        return PlaylistPage(
            item = item.copy(title = public.name.ifBlank { item.title }, imageUrl = public.imageUrl ?: item.imageUrl,
                subtitle = fr.spotifeur.utils.formatSongCount(entries.size)),
            uris = entries.filter { it.startsWith("spotify:track:") }, total = entries.size, rawCount = entries.size,
            hasMore = false, entries = entries,
        )
    }

    private class ArtistExtensions(
        val monthlyListeners: Long?, val popularReleases: List<String>, val topTracks: List<String>, val relatedArtists: List<String>,
    )

    /**
     * Extended Metadata extensions of an artist, read by the official artist page:
     * ON_PLATFORM_REPUTATION_TRAIT (185, field 1 = monthly listeners; field 3 on a track is its play count), POPULAR_RELEASES (152),
     * TOP_PLAYABLES_SECTION (169) and RELATED_CREATORS_SECTION (165).
     */
    private suspend fun artistExtensions(uri: String): ArtistExtensions {
        val data = try { extensions(listOf(uri), listOf(185, 152, 169, 165)) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (auth: SpotifyAuthRequired) { throw auth }
        catch (_: java.io.IOException) { emptyMap() }
        fun payload(kind: Int) = data[kind to uri]?.let(::Proto)
        val reputation = payload(185)
        val popular = payload(152)?.messages(1).orEmpty().mapNotNull { it.text(1)?.let(::canonicalMusicUri) }
            .filter { it.startsWith("spotify:album:") }
        val top = payload(169)?.messages(1).orEmpty().flatMap { it.messages(1) }.mapNotNull { it.text(1)?.let(::canonicalMusicUri) }
            .filter { it.startsWith("spotify:track:") }
        val related = payload(165)?.messages(1).orEmpty().flatMap { it.messages(1) }.mapNotNull { it.text(1)?.let(::canonicalMusicUri) }
            .filter { it.startsWith("spotify:artist:") }
        return ArtistExtensions(reputation?.long(1)?.takeIf { it > 0 }, popular, top, related)
    }

    /** ON_PLATFORM_REPUTATION_TRAIT on tracks: field 3 is the play count. Absent when the service gives none. */
    private suspend fun trackPlayCounts(tracks: List<String>): Map<String, Long> {
        if (tracks.isEmpty()) return emptyMap()
        val data = try { extensions(tracks, listOf(185)) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (auth: SpotifyAuthRequired) { throw auth }
        catch (_: java.io.IOException) { return emptyMap() }
        return tracks.mapNotNull { uri -> data[185 to uri]?.let(::Proto)?.long(3)?.takeIf { it > 0 }?.let { uri to it } }.toMap()
    }

    /** Payloads by (extension kind, entity URI), for entities the service answered with 200. */
    private suspend fun extensions(uris: List<String>, kinds: List<Int>): Map<Pair<Int, String>, ByteArray> {
        val body = proto {
            uris.forEach { uri ->
                writeByteArray(2, proto { writeString(1, uri); kinds.forEach { kind -> writeByteArray(2, proto { writeEnum(1, kind) }) } })
            }
        }
        val response = requests.withPermit {
            authenticatedSpotifyRequest(transport, session, spotifyUrl("extended-metadata/v0/extended-metadata"), "Spotify artist", body)
        }
        return buildMap {
            Proto(response).messages(2).forEach { array ->
                val kind = array.number(2) ?: return@forEach
                array.messages(3).forEach { data ->
                    val status = data.message(1)?.number(1) ?: 0
                    if (status != 0 && status != 200) return@forEach
                    val uri = data.text(2)?.let(::canonicalMusicUri) ?: return@forEach
                    data.message(3)?.bytes(2)?.let { put(kind to uri, it) }
                }
            }
        }
    }

    private class ArtistAbout(val listeners: Long?, val rank: Int?, val biography: String, val headerImage: String?)

    /** `creatorabout/v0/artist/{id}/about`, the official "About" section. Optional: the page works without it. */
    private suspend fun artistAbout(uri: String): ArtistAbout? = try {
        val url = spotifyUrl("creatorabout/v0/artist/${uri.substringAfterLast(':')}/about").newBuilder()
            .addQueryParameter("fields", "artist,listenerCount,monthlyListenerRank,isVerified").build()
        val json = org.json.JSONObject(requests.withPermit {
            authenticatedSpotifyRequest(transport, session, url, "Spotify artist", contentType = "application/json")
        }.decodeToString())
        ArtistAbout(
            listeners = json.optLong("monthlyListeners", json.optLong("listenerCount", 0)).takeIf { it > 0 },
            rank = json.optInt("monthlyListenerRank", json.optJSONObject("monthlyListenerRank")?.optInt("rank") ?: 0).takeIf { it > 0 },
            biography = cleanBiography(json.optString("biography")),
            headerImage = json.optJSONObject("headerImage")?.optString("uri")?.let(::spotifyArtwork),
        )
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (auth: SpotifyAuthRequired) { throw auth }
    catch (_: java.io.IOException) { null }
    catch (_: org.json.JSONException) { null }

    private fun remember(items: List<Music>) = items.forEach { musicCache[it.id] = it }
}

private const val PLAYLIST_PAGE = 100
private const val MAX_RELEASES_PER_GROUP = 60
private const val MAX_APPEARS_ON = 20

/** Statuses for which another way of reading the playlist may succeed: not auth, access, rate limits or a missing list. */
internal fun playlistFallbackApplies(failure: SpotifyFailure): Boolean =
    failure !is SpotifyAuthRequired && failure !is SpotifyRateLimited && failure.status != null &&
        failure.status in 400..499 && failure.status !in setOf(401, 403, 404)

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

/** Provider and entity statuses of an Extended Metadata response, for the diagnostic report. */
internal fun metadataStatusSummary(response: ByteArray): String = Proto(response).messages(2).joinToString(" ") { array ->
    val entities = array.messages(3).groupingBy { it.message(1)?.number(1) ?: 0 }.eachCount()
    "kind=${array.number(2) ?: "?"} provider=${array.message(1)?.number(1) ?: 0} entities=$entities"
}.ifEmpty { "arrays=0" }

/** Artist biographies carry HTML links and entities: plain text with paragraphs kept. */
internal fun cleanBiography(text: String): String {
    if (text.isBlank()) return ""
    val noTags = text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")
    val decoded = androidx.core.text.HtmlCompat.fromHtml(noTags.replace("\n", "<br>"), androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    return decoded.replace(Regex("\n{3,}"), "\n\n").trim()
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
