package fr.spotifeur.data

import android.content.Context
import fr.spotifeur.playback.SpotifyNativeRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** The Android RootlistModificationService/PlaylistService contracts from 9.2.0.26. */
internal class SpotifyPlaylists(private val context: Context) : AutoCloseable {
    private val lock = Mutex()
    private var runtime: SpotifyNativeRuntime? = null
    private var runtimeSession: SpotifySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountListener = SpotifyAccount.onSessionReplaced {
        scope.launch { lock.withLock { runtime?.close(); runtime = null; runtimeSession = null } }
    }
    private suspend fun request(service: String, method: String, body: ByteArray): Proto = withContext(Dispatchers.IO) {
        val account = SpotifyAccount.require(context)
        lock.withLock {
            if (runtimeSession !== account) { runtime?.close(); runtime = null; runtimeSession = null }
            val current = runtime ?: SpotifyNativeRuntime.open(context).also { runtime = it; runtimeSession = account }
            current.serviceRequest(service, method, body)
        }
    }
    override fun close() {
        accountListener.close()
        scope.launch { lock.withLock { runtime?.close(); runtime = null; runtimeSession = null }; scope.cancel() }
    }
    private suspend fun modify(service: String, request: ByteArray): Proto {
        if (!NetworkMonitor.online.value) throw SpotifyFailure("Sign in to edit a Spotify playlist.")
        val response = request("spotify.playlist_esperanto.proto.$service", "Modify", request)
        val status = response.message(1)?.number(1)
        if (status !in 200..299) throw SpotifyFailure("Spotify rejected the playlist change (code $status): ${response.message(1)?.text(2).orEmpty()}", status)
        return response
    }
    suspend fun create(name: String, tracks: List<Music> = emptyList(), published: Boolean = false): Music {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 100) { "The name must be between 1 and 100 characters." }
        val uris = spotifyTracks(tracks)
        val response = modify("RootlistModificationService", createPlaylistRequest(clean, uris))
        val uri = response.message(2)?.text(2)?.let(::canonicalMusicUri)?.takeIf { it.startsWith("spotify:playlist:") }
            ?: throw SpotifyFailure("Spotify did not return the new playlist.")
        val result = Music(uri, clean, fr.spotifeur.utils.formatSongCount(uris.size), MusicKind.Playlist, ownerUsername = SpotifyAccount.require(context).username())
        // Publication is a rootlist property, independent of collaborative/private access.
        setPublished(uri, published)
        // Modify acknowledges the local native transaction. Keep its owner alive until the
        // backend exposes the new list, otherwise shutdown can abandon synchronization.
        val session = SpotifyAccount.require(context)
        withTimeout(30_000) {
            while (true) {
                try {
                    authenticatedSpotifyRequest(SpotifyAccount.transport, session,
                        spotifyUrl("playlist/v2/playlist/${uri.substringAfterLast(':')}").newBuilder().addQueryParameter("length", "0").build(), "New playlist")
                    break
                } catch (failure: SpotifyFailure) { if (failure.status != 404) throw failure }
                delay(500)
            }
        }
        return result
    }
    suspend fun rename(uri: String, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 100)
        setAttributes(uri, proto { writeString(3, clean) })
    }
    private suspend fun rootEntry(uri: String): Pair<String, Boolean>? {
        val target = playlistUri(uri)
        val session = SpotifyAccount.require(context)
        val username = session.username()
        var offset = 0
        while (true) {
            val url = spotifyUrl("playlist/v2/user").newBuilder().addPathSegment(username).addPathSegment("rootlist")
                .addQueryParameter("from", offset.toString()).addQueryParameter("length", "300").build()
            val response = Proto(authenticatedSpotifyRequest(SpotifyAccount.transport, session, url, "Spotify library"))
            val contents = response.message(5) ?: return null
            val items = contents.messages(3)
            items.firstOrNull { entry -> entry.text(1)?.let(::canonicalMusicUri) == target }?.let { row ->
                return checkNotNull(row.text(1)) to (row.message(2)?.number(10) == 1)
            }
            if (contents.number(2) != 1 || items.isEmpty()) return null
            offset += items.size
        }
    }
    suspend fun setPublished(uri: String, published: Boolean) {
        val canonical = rootEntry(uri)?.first ?: playlistUri(uri)
        // A fresh core has not loaded the account rootlist yet. Explicitly request it before
        // resolving a row; unlike a newly created list, an existing list has no local row yet.
        val username = SpotifyAccount.require(context).username()
        request("spotify.playlist_esperanto.proto.PlaylistDataService", "Get", proto {
            writeString(1, "spotify:user:$username:rootlist")
            writeByteArray(2, proto { writeByteArray(4, proto { writeInt32(1, 0); writeInt32(2, 1) }) })
        })
        withTimeout(25_000) {
            while (true) {
                try {
                    modify("RootlistModificationService", proto { writeByteArray(2, proto {
                        writeString(1, "set"); writeString(8, canonical)
                        writeByteArray(6, proto { writeBool(1, published) })
                    }) })
                    break
                } catch (failure: SpotifyFailure) {
                    if (failure.status != 400 || !failure.message.orEmpty().contains("not found in rootlist")) throw failure
                }
                delay(500)
            }
        }
        withTimeout(20_000) {
            while (rootEntry(uri)?.second != published) delay(500)
        }
    }
    suspend fun add(uri: String, tracks: List<Music>, onAccepted: suspend () -> Unit = {}) {
        val uris = spotifyTracks(tracks)
        if (uris.isEmpty()) return
        val session = SpotifyAccount.require(context)
        val fallback = Music(playlistUri(uri), "", "", MusicKind.Playlist)
        suspend fun page(from: Int, length: Int): PlaylistPage {
            val url = spotifyUrl("playlist/v2/playlist/${playlistUri(uri).substringAfterLast(':')}").newBuilder()
                .addQueryParameter("from", from.toString()).addQueryParameter("length", length.toString()).build()
            return decodePlaylistPage(authenticatedSpotifyRequest(SpotifyAccount.transport, session, url, "Add to playlist"), fallback)
        }
        val before = page(0, 0).total
        // Native modifications are bounded; keep order and duplicates, as Spotify does.
        uris.chunked(100).forEach { chunk ->
            modify("PlaylistService", playlistModification(uri, proto {
                writeString(1, "add"); chunk.forEach { writeString(7, it) }
            }))
        }
        onAccepted()
        // Modify acknowledges the local transaction before the HTTP catalogue catches up.
        // Keep the native owner alive and avoid replacing the accepted edit with stale data.
        withTimeout(30_000) {
            while (true) {
                val tail = page(before, minOf(100, uris.size))
                if (tail.total >= before + uris.size && tail.entries.take(minOf(100, uris.size)) == uris.take(100)) break
                delay(500)
            }
        }
    }
    /**
     * Removes one appearance of a track: the [occurrence]-th (from 0) row holding [trackUri].
     * The core removes rows, not URIs, so a duplicated track loses only the chosen copy.
     */
    suspend fun remove(uri: String, trackUri: String, occurrence: Int, onAccepted: suspend () -> Unit = {}) {
        val target = playlistEntryUri(trackUri) ?: throw SpotifyFailure("A Spotify playlist only holds Spotify tracks and local files.")
        val row = rows(uri).filter { (entry, _) -> entry == target }.getOrNull(occurrence)?.second
            ?: throw SpotifyFailure("This track is no longer in the playlist.")
        val session = SpotifyAccount.require(context)
        suspend fun total(): Int {
            val url = spotifyUrl("playlist/v2/playlist/${playlistUri(uri).substringAfterLast(':')}").newBuilder()
                .addQueryParameter("from", "0").addQueryParameter("length", "0").build()
            return decodePlaylistPage(authenticatedSpotifyRequest(SpotifyAccount.transport, session, url, "Remove from playlist"),
                Music(playlistUri(uri), "", "", MusicKind.Playlist)).total
        }
        val before = total()
        modify("PlaylistService", playlistModification(uri, proto { writeString(1, "remove"); writeString(8, row) }))
        onAccepted()
        // As for additions, keep the native owner alive until Playlist4 shows the change.
        withTimeout(30_000) { while (total() >= before) delay(500) }
    }
    /** Every row of the playlist as the core sees it: entry URI and row ID, in playlist order. */
    private suspend fun rows(uri: String): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        while (true) {
            val response = request("spotify.playlist_esperanto.proto.PlaylistDataService", "Get", playlistRowsRequest(uri, result.size, ROW_PAGE))
            val status = response.message(1)?.number(1)
            if (status !in 200..299) throw SpotifyFailure("The playlist could not be read.", status)
            val items = response.message(2)?.messages(1).orEmpty()
            items.forEach { item ->
                val row = item.text(7) ?: return@forEach
                val entry = (item.text(18) ?: item.message(4)?.text(1))?.let(::playlistEntryUri) ?: return@forEach
                result += entry to row
            }
            if (items.size < ROW_PAGE) return result
        }
    }
    /** Removes only a playlist explicitly created by a test or requested by its owner. */
    suspend fun unfollow(uri: String) {
        val row = rootEntry(uri)?.first ?: playlistUri(uri)
        modify("RootlistModificationService", proto { writeByteArray(2, proto {
            writeString(1, "remove"); writeString(8, row); writeBool(9, false)
        }) })
        withTimeout(20_000) { while (rootEntry(uri) != null) delay(500) }
    }
    private suspend fun setAttributes(uri: String, attributes: ByteArray) {
        modify("PlaylistService", playlistModification(uri, proto { writeString(1, "set"); writeByteArray(6, attributes) }))
    }
    /** Image bytes go directly to the same dedicated upload host as SetPlaylistPictureWorker. */
    suspend fun setCover(uri: String, jpeg: ByteArray) {
        require(jpeg.isNotEmpty() && jpeg.size <= 4 * 1024 * 1024)
        val session = SpotifyAccount.require(context)
        val host = "image-upload.spotify.com"
        val headers = session.headers(host)
        val uploaded = SpotifyAccount.transport.send(Request.Builder().url("https://$host/v4/playlist")
            .header("Authorization", "Bearer ${headers.user}").header("client-token", headers.client)
            .header("Spotify-App-Version", SpotifyAndroid.VERSION).header("App-Platform", "Android")
            .header("Content-Encoding", "identity").post(jpeg.toRequestBody("image/jpeg".toMediaType())).build(), "Spotify cover")
        val token = JSONObject(uploaded.decodeToString()).getString("uploadToken")
        val registered = authenticatedSpotifyRequest(SpotifyAccount.transport, session,
            spotifyUrl("playlist/v2/playlist/${playlistUri(uri).substringAfterLast(':')}/register-image"),
            "Cover registration", proto { writeString(1, token) }, "application/protobuf")
        val picture = Proto(registered).bytes(1)?.let { value ->
            if (value.size == 20) value.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
            else value.decodeToString().takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }?.lowercase()
        }
            ?: throw SpotifyFailure("Spotify did not save the cover.")
        setAttributes(uri, proto { writeString(6, picture) })
    }
    private suspend fun metadata(uri: String): Proto {
        val response = request("spotify.playlist_esperanto.proto.PlaylistDataService", "Get", proto {
            writeString(1, playlistUri(uri))
            writeByteArray(2, proto { writeByteArray(4, proto { writeInt32(1, 0); writeInt32(2, 0) }) })
            writeByteArray(3, proto { writeByteArray(1, proto {
                writeBool(2, true); writeBool(5, true); writeBool(19, true); writeBool(22, true)
            }) })
        })
        val status = response.message(1)?.number(1)
        if (status !in 200..299) throw SpotifyFailure("Playlist information is unavailable.", status)
        return response.message(2)?.message(2)?.message(1) ?: throw SpotifyFailure("Missing playlist information.")
    }
    /**
     * A page of a playlist read through the native core (`PlaylistDataService/Get`), as the official
     * app reads it. Used when Playlist4 over HTTP refuses the request.
     */
    suspend fun page(uri: String, from: Int, length: Int, fallback: Music): PlaylistPage {
        val response = request("spotify.playlist_esperanto.proto.PlaylistDataService", "Get", nativePageRequest(uri, from, length))
        val status = response.message(1)?.number(1)
        if (status !in 200..299) throw SpotifyFailure("Spotify playlist: native read refused (code $status).", status)
        return decodeNativePlaylistPage(response, fallback, from)
    }
    /**
     * Track, album and artist metadata through the native core (`ClassicMetadataService/MultigetEntity`),
     * as the official app's pages read it. Used when Extended Metadata over HTTP refuses the entities.
     */
    suspend fun entities(uris: List<String>): Map<String, ByteArray> =
        decodeNativeMetadata(request(CLASSIC_METADATA_SERVICE, "MultigetEntity", nativeMetadataRequest(uris)), uris)
    suspend fun isOwned(uri: String): Boolean = metadata(uri).number(4) == 1
    suspend fun published(uri: String): Boolean = rootEntry(uri)?.second ?: false
    suspend fun recommendations(uri: String, tracks: List<Music>, fallback: List<Music>): List<Music> {
        val excluded = tracks.map(Music::id).toSet()
        val native = request("spotify.playlist_esperanto.proto.PlaylistDataService", "Get", proto {
            writeString(1, playlistUri(uri))
            writeByteArray(2, proto { writeBool(10, true); writeByteArray(4, proto { writeInt32(1, 0); writeInt32(2, 0) }) })
            writeByteArray(3, proto {
                writeByteArray(1, proto { writeBool(2, true) })
                writeByteArray(2, proto { writeByteArray(1, proto { writeBool(2, true); writeBool(3, true) }) })
            })
        })
        val recommended = native.message(2)?.messages(10).orEmpty().mapNotNull { it.message(1)?.text(3) }
            .mapNotNull(::canonicalMusicUri).filter { it.startsWith("spotify:track:") && it !in excluded }.distinct()
        if (recommended.isNotEmpty()) {
            android.util.Log.i("SpotifeurPlaylist", "Native recommendations=${recommended.size}")
            val session = SpotifyAccount.require(context)
            return SpotifyCatalog(SpotifyAccount.transport, session).hydrate(recommended.take(30))
        }
        val seeds = tracks.takeLast(3).ifEmpty { fallback.take(1) }
        if (seeds.isEmpty()) return emptyList()
        val session = SpotifyAccount.require(context)
        val radio = SpotifyRadio(SpotifyAccount.transport, session)
        val stations = seeds.map { seed -> radio.station(seed).tracks }
        return interleaveStations(stations).filter { it.id !in excluded }.take(20)
    }
}

internal fun playlistUri(uri: String): String = canonicalMusicUri(uri)?.takeIf { it.startsWith("spotify:playlist:") }
    ?: throw SpotifyFailure("Invalid Spotify playlist.")
private fun spotifyTracks(tracks: List<Music>): List<String> = tracks.map { track ->
    canonicalMusicUri(track.id)?.takeIf { it.startsWith("spotify:track:") }
        ?: spotifyLocalUri(track)
        ?: throw SpotifyFailure("A Spotify playlist only accepts Spotify tracks and local files.")
}

/**
 * The reference Spotify's apps use for a local file in a playlist:
 * `spotify:local:{artist}:{album}:{title}:{seconds}`, each part form-encoded. Every Spotify app
 * that lists the device's local files plays the file whose tags match.
 */
internal fun spotifyLocalUri(track: Music): String? {
    if (track.id.startsWith(SPOTIFY_LOCAL_PREFIX)) return track.id
    if (!track.id.startsWith(LOCAL_TRACK_PREFIX) || track.title.isBlank()) return null
    fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
    return SPOTIFY_LOCAL_PREFIX + listOf(encode(track.subtitle), encode(track.album), encode(track.title), (track.durationMs / 1000).toString())
        .joinToString(":")
}
internal fun createPlaylistRequest(name: String, uris: List<String>): ByteArray = proto {
    writeString(1, "")
    writeByteArray(2, proto {
        writeString(1, "create"); writeString(2, "start"); writeString(4, name); writeBool(5, true)
        uris.forEach { writeString(7, it) }
        writeByteArray(14, proto { writeEnum(1, 1) })
    })
}
private const val ROW_PAGE = 500

/** A playlist entry as stored by Spotify: a track URI, or a `spotify:local:` reference kept as written. */
internal fun playlistEntryUri(uri: String): String? =
    if (uri.startsWith(SPOTIFY_LOCAL_PREFIX)) uri else canonicalMusicUri(uri)?.takeIf { it.startsWith("spotify:track:") }

/** `PlaylistDataService/Get` for a range of rows, with each item's URI and row ID, unavailable items included. */
internal fun playlistRowsRequest(uri: String, start: Int, length: Int): ByteArray = proto {
    writeString(1, playlistUri(uri))
    writeByteArray(2, proto {
        writeByteArray(4, proto { writeInt32(1, start); writeInt32(2, length) })
        writeBool(8, true)
    })
    writeByteArray(3, proto { writeByteArray(4, proto { writeBool(1, true); writeBool(9, true) }) })
}
/** `PlaylistDataService/Get` for a range of rows with the playlist's own metadata (name, length, picture). */
internal fun nativePageRequest(uri: String, start: Int, length: Int): ByteArray = proto {
    writeString(1, playlistUri(uri))
    writeByteArray(2, proto {
        writeByteArray(4, proto { writeInt32(1, start); writeInt32(2, length) })
        writeBool(8, true)
    })
    writeByteArray(3, proto {
        writeByteArray(1, proto { for (field in 1..30) writeBool(field, true) })
        writeByteArray(4, proto { writeBool(1, true); writeBool(9, true) })
    })
}

/**
 * Response of [nativePageRequest]: 2 → 1 items (18 = URI, or 4 → 1), 2 → 2 → 1 playlist metadata
 * (2 name, 6 length, 7 description, 8 → 1 picture URI).
 */
internal fun decodeNativePlaylistPage(response: Proto, fallback: Music, from: Int): PlaylistPage {
    val result = response.message(2)
    val raw = result?.messages(1).orEmpty()
    val entries = raw.mapNotNull { item -> (item.text(18) ?: item.message(4)?.text(1))?.let(::playlistEntryUri) }
    val meta = result?.message(2)?.message(1)
    val total = meta?.number(6) ?: (from + raw.size)
    val image = meta?.message(8)?.text(1)?.let(::spotifyArtwork) ?: fallback.imageUrl
    return PlaylistPage(
        item = fallback.copy(
            title = meta?.text(2)?.takeIf(String::isNotBlank) ?: fallback.title,
            subtitle = fr.spotifeur.utils.formatSongCount(total),
            imageUrl = image, note = meta?.text(7) ?: fallback.note,
        ),
        uris = entries.filter { it.startsWith("spotify:track:") }, total = total, rawCount = raw.size,
        hasMore = raw.isNotEmpty() && from + raw.size < total, entries = entries,
    )
}
internal fun playlistModification(uri: String, modification: ByteArray): ByteArray = proto {
    writeString(1, playlistUri(uri)); writeByteArray(2, modification)
}
