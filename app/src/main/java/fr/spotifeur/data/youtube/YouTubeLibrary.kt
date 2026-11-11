package fr.spotifeur.data.youtube

import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** A playlist of the signed-in account, with what editing it needs. */
data class YouTubeAccountPlaylist(
    val id: String,
    val title: String,
    val imageUrl: String?,
    val tracks: List<Music>,
    /** Row identifiers (`playlistSetVideoId`), parallel to [tracks]; a row is removed by this id. */
    val setVideoIds: List<String?>,
    val editable: Boolean,
    /** PUBLIC, UNLISTED or PRIVATE, when the page says it. */
    val privacy: String?,
)

/**
 * The signed-in account's YouTube Music playlists, through the InnerTube requests of the
 * music.youtube.com web client (WEB_REMIX) with the account's cookies. Every call blocks.
 */
object YouTubeLibrary {
    const val PRIVATE = "PRIVATE"
    const val PUBLIC = "PUBLIC"

    /** Who is signed in; throws [YouTubeAuthRequired] when the cookies no longer open a session. */
    internal fun accountInfo(credential: YouTubeCredential): YouTubeAccountInfo =
        parseAccountMenu(request("account/account_menu", JSONObject(), credential))
            ?: throw YouTubeAuthRequired("Google did not open a YouTube Music session.")

    /** Playlists of the library: the account's own, the ones it saved, and Liked Music. */
    fun libraryPlaylists(): List<Music> {
        val first = request("browse", JSONObject().put("browseId", "FEmusic_liked_playlists"))
        val playlists = ArrayList(parseLibraryPlaylists(first))
        val seen = HashSet<String>()
        var token = findContinuation(first, preferShelf = false)
        var pages = 0
        while (token != null && seen.add(token) && pages++ < MAX_LIBRARY_PAGES) {
            val page = request("browse", JSONObject().put("continuation", token))
            val rows = parseLibraryPlaylists(page)
            if (rows.isEmpty()) break
            playlists += rows
            token = findContinuation(page, preferShelf = false)
        }
        return playlists.distinctBy(Music::id)
    }

    /** A playlist as the account sees it, private ones included, with its row identifiers. */
    fun playlist(playlistId: String, limit: Int = MAX_TRACKS): YouTubeAccountPlaylist {
        val id = playlistId.removePrefix(YTM_PLAYLIST_PREFIX).removePrefix("VL")
        val first = request("browse", JSONObject().put("browseId", "VL$id"))
        // The page can also hold a shelf of suggestions: only the playlist's own shelf is read.
        var shelf: JSONObject? = null
        walkJson(first) { node -> if (shelf == null) node.optJSONObject("musicPlaylistShelfRenderer")?.let { shelf = it } }
        val rows = ArrayList(parsePlaylistRows(shelf ?: first))
        val seen = HashSet<String>()
        var token = findContinuation(first, preferShelf = true)
        while (token != null && seen.add(token) && rows.size < limit) {
            val page = request("browse", JSONObject().put("continuation", token))
            val next = parsePlaylistRows(page)
            if (next.isEmpty()) break
            rows += next
            token = findContinuation(page, preferShelf = false)
        }
        val kept = rows.take(limit)
        return YouTubeAccountPlaylist(
            id = id, title = playlistTitle(first) ?: "YouTube Music", imageUrl = playlistImage(first),
            tracks = kept.map { it.first }, setVideoIds = kept.map { it.second },
            editable = isEditablePlaylist(first), privacy = playlistPrivacy(first),
        )
    }

    /** A playlist created on the account, and the tracks YouTube Music would not take. */
    data class Created(val id: String, val refused: List<String>)

    /**
     * Creates a playlist on the account. The tracks go in afterwards, a hundred at a time, so a long
     * playlist is never one oversized request.
     */
    fun create(title: String, videoIds: List<String>, privacy: String = PRIVATE): Created {
        val clean = title.trim()
        require(clean.isNotEmpty() && clean.length <= 150) { "The name must be between 1 and 150 characters." }
        val body = JSONObject().put("title", clean).put("description", "").put("privacyStatus", privacy)
        val response = request("playlist/create", body)
        val id = response.optString("playlistId").takeIf(String::isNotBlank)
            ?: throw YouTubeFailure("YouTube Music did not return the new playlist.")
        // A playlist left half filled would be created again by the next attempt: it goes if filling fails.
        val refused = try { add(id, videoIds) }
        catch (failure: java.io.IOException) { runCatching { delete(id) }; throw failure }
        return Created(id, refused)
    }

    /**
     * Appends tracks in order; a track already in the playlist is added again, as Spotify does.
     * Without `DEDUPE_OPTION_SKIP`, YouTube Music refuses a whole batch (`STATUS_FAILED`) as soon as one
     * video is already in the playlist. A batch refused anyway is split until the refused videos are
     * isolated; those are returned and the others are added.
     */
    fun add(playlistId: String, videoIds: List<String>): List<String> {
        val refused = ArrayList<String>()
        fun send(chunk: List<String>) {
            try {
                edit(playlistId, chunk.map {
                    JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", it).put("dedupeOption", "DEDUPE_OPTION_SKIP")
                })
            } catch (failure: YouTubeFailure) {
                if (failure.status != "STATUS_FAILED") throw failure
                if (chunk.size == 1) refused += chunk else chunk.chunked((chunk.size + 1) / 2).forEach(::send)
            }
        }
        videoIds.chunked(EDIT_BATCH).forEach(::send)
        if (refused.isNotEmpty()) fr.spotifeur.data.diagnostics.DiagnosticLog.record("youtube-library",
            "add refused=${refused.size} of ${videoIds.size}")
        return refused
    }

    /** Removes one row: a duplicated track keeps its other rows. */
    fun remove(playlistId: String, videoId: String, setVideoId: String) = edit(playlistId, listOf(
        JSONObject().put("action", "ACTION_REMOVE_VIDEO").put("setVideoId", setVideoId).put("removedVideoId", videoId),
    ))

    fun rename(playlistId: String, title: String) {
        val clean = title.trim()
        require(clean.isNotEmpty() && clean.length <= 150) { "The name must be between 1 and 150 characters." }
        edit(playlistId, listOf(JSONObject().put("action", "ACTION_SET_PLAYLIST_NAME").put("playlistName", clean)))
    }

    fun setPrivacy(playlistId: String, privacy: String) =
        edit(playlistId, listOf(JSONObject().put("action", "ACTION_SET_PLAYLIST_PRIVACY").put("playlistPrivacy", privacy)))

    /** Deletes a playlist the account owns. */
    fun delete(playlistId: String) {
        request("playlist/delete", JSONObject().put("playlistId", bareId(playlistId)))
    }

    /** Removes a playlist saved from someone else from the library; the playlist itself stays. */
    fun removeFromLibrary(playlistId: String) {
        request("like/removelike", JSONObject().put("target", JSONObject().put("playlistId", bareId(playlistId))))
    }

    private fun edit(playlistId: String, actions: List<JSONObject>) {
        if (actions.isEmpty()) return
        val response = request("browse/edit_playlist", JSONObject().put("playlistId", bareId(playlistId)).put("actions", JSONArray(actions)))
        val status = response.optString("status")
        if (status.isNotEmpty() && status != "STATUS_SUCCEEDED") throw YouTubeFailure("YouTube Music refused the change ($status).", status)
    }

    private fun bareId(playlistId: String) = playlistId.removePrefix(YTM_PLAYLIST_PREFIX).removePrefix("VL")

    /** Likes a track on the account (it then appears in Liked Music), or removes the like. */
    fun like(videoId: String, liked: Boolean) {
        request(if (liked) "like/like" else "like/removelike", JSONObject().put("target", JSONObject().put("videoId", videoId)))
    }

    /**
     * Google answers 403 or 429 to every request from a connection it is limiting for a while (after a
     * long conversion, for instance). A change is tried again a little later instead of failing at once.
     */
    private fun request(endpoint: String, body: JSONObject, credential: YouTubeCredential = YouTubeAccount.require()): JSONObject {
        var attempt = 0
        while (true) {
            YouTubeMusic.cooldownMs().takeIf { it > 0 }?.let { Thread.sleep(minOf(it, MAX_COOLDOWN_WAIT_MS)) }
            try { return requestOnce(endpoint, JSONObject(body.toString()), credential) }
            catch (failure: YouTubeFailure) {
                val refused = failure.status == "403" || failure.status == "429"
                if (!refused || endpoint == "account/account_menu" || attempt >= RETRY_DELAYS_MS.size) throw failure
                Thread.sleep(RETRY_DELAYS_MS[attempt++])
            }
        }
    }

    private fun requestOnce(endpoint: String, body: JSONObject, credential: YouTubeCredential): JSONObject {
        val sapisid = credential.sapisid ?: throw YouTubeAuthRequired("The Google session is incomplete. Connect the account again.")
        val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", YouTubeMusic.MUSIC_CLIENT_VERSION)
            .put("hl", "en").put("gl", "US")
        credential.visitorData?.let { client.put("visitorData", it) }
        val context = JSONObject().put("client", client)
        delegatedPageId(credential.dataSyncId)?.let { context.put("user", JSONObject().put("onBehalfOfUser", it)) }
        body.put("context", context)
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .header("Cookie", credential.cookie)
            .header("Authorization", sapisidHash(sapisid, ORIGIN, System.currentTimeMillis() / 1000))
            .header("X-Goog-AuthUser", "0")
            .header("Origin", ORIGIN).header("X-Origin", ORIGIN).header("Referer", "$ORIGIN/")
            .header("User-Agent", YouTubeMusic.BROWSER_AGENT)
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-YouTube-Client-Name", "67").header("X-YouTube-Client-Version", YouTubeMusic.MUSIC_CLIENT_VERSION)
            .apply { credential.visitorData?.let { header("X-Goog-Visitor-Id", it) } }
            .post(body.toString().toRequestBody(JSON))
            .build()
        YouTubeMusic.http.newCall(request).execute().use { response ->
            YouTubeAccount.updateCookies(response.headers("Set-Cookie"))
            if (response.code == 401) throw YouTubeAuthRequired("Google closed the session. Connect the account again.")
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Google's error status and reason say why ("PERMISSION_DENIED", "UNAUTHENTICATED", …); no account data.
                val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
                val reason = listOfNotNull(error?.optString("status")?.takeIf(String::isNotBlank),
                    error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")?.takeIf(String::isNotBlank),
                    error?.optString("message")?.take(120)?.takeIf(String::isNotBlank)).joinToString(" / ")
                fr.spotifeur.data.diagnostics.DiagnosticLog.record("youtube-library",
                    "status=${response.code} endpoint=$endpoint delegated=${context.has("user")} reason=\"$reason\"")
                // A stored brand-account delegation that is refused: once more as the main account.
                if (response.code == 403 && context.has("user") && credential === YouTubeAccount.current()) {
                    YouTubeAccount.dropDelegation()
                    return requestOnce(endpoint, body.apply { remove("context") }, YouTubeAccount.require())
                }
                throw YouTubeFailure("YouTube Music answered ${response.code}.", response.code.toString())
            }
            return JSONObject(text)
        }
    }

    private const val ORIGIN = "https://music.youtube.com"
    private const val MAX_LIBRARY_PAGES = 20
    private const val MAX_TRACKS = 5000
    private const val EDIT_BATCH = 100
    private val RETRY_DELAYS_MS = longArrayOf(4_000, 12_000)
    private const val MAX_COOLDOWN_WAIT_MS = 30_000L
    private val JSON = "application/json".toMediaType()
}

// Parsing ----------------------------------------------------------------------------------------

private fun JSONObject.text(): String? = optString("simpleText").takeIf(String::isNotBlank)
    ?: optJSONArray("runs")?.let { runs -> (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() } }
        ?.takeIf(String::isNotBlank)

private fun lastThumbnail(thumbnails: JSONArray?): String? =
    thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url")?.takeIf(String::isNotBlank)

internal fun parseAccountMenu(response: JSONObject): YouTubeAccountInfo? {
    var header: JSONObject? = null
    walkJson(response) { node -> if (header == null) node.optJSONObject("activeAccountHeaderRenderer")?.let { header = it } }
    val found = header ?: return null
    val name = found.optJSONObject("accountName")?.text() ?: return null
    val handle = found.optJSONObject("channelHandle")?.text() ?: found.optJSONObject("email")?.text().orEmpty()
    return YouTubeAccountInfo(name, handle, lastThumbnail(found.optJSONObject("accountPhoto")?.optJSONArray("thumbnails")))
}

/** Playlist cards of the library grid. The "New playlist" card and podcast lists are left out. */
internal fun parseLibraryPlaylists(response: JSONObject): List<Music> {
    val playlists = ArrayList<Music>()
    walkJson(response) { node ->
        val card = node.optJSONObject("musicTwoRowItemRenderer") ?: return@walkJson
        val titleRun = card.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0) ?: return@walkJson
        val browseId = (titleRun.optJSONObject("navigationEndpoint") ?: card.optJSONObject("navigationEndpoint"))
            ?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
        if (!browseId.startsWith("VL") || browseId.startsWith("VLSE")) return@walkJson
        val title = titleRun.optString("text").takeIf(String::isNotBlank) ?: return@walkJson
        val subtitleRuns = card.optJSONObject("subtitle")?.optJSONArray("runs")
        val subtitle = card.optJSONObject("subtitle")?.text().orEmpty()
        // "Owner • 12 songs": the first run names who made the playlist.
        val owner = subtitleRuns?.optJSONObject(0)?.optString("text")?.takeIf { subtitleRuns.length() >= 3 && it.isNotBlank() }
        val image = lastThumbnail(card.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
        playlists += Music(
            id = YTM_PLAYLIST_PREFIX + browseId.removePrefix("VL"), title = title, subtitle = subtitle,
            kind = MusicKind.Playlist, imageUrl = image?.let { largestYouTubeImage(it, "") }, ownerUsername = owner,
        )
    }
    return playlists
}

/** Track rows with their `playlistSetVideoId`. */
internal fun parsePlaylistRows(response: JSONObject): List<Pair<Music, String?>> {
    val rows = ArrayList<Pair<Music, String?>>()
    walkJson(response) { node ->
        val renderer = node.optJSONObject("musicResponsiveListItemRenderer") ?: return@walkJson
        val track = parseTrackRow(renderer) ?: return@walkJson
        rows += track to renderer.optJSONObject("playlistItemData")?.optString("playlistSetVideoId")?.takeIf(String::isNotBlank)
    }
    return rows
}

/** Only the owner's page carries the editable header. */
internal fun isEditablePlaylist(response: JSONObject): Boolean {
    var editable = false
    walkJson(response) { node -> if (!editable && node.has("musicEditablePlaylistDetailHeaderRenderer")) editable = true }
    return editable
}

internal fun playlistPrivacy(response: JSONObject): String? {
    var privacy: String? = null
    walkJson(response) { node ->
        if (privacy == null) node.optJSONObject("musicPlaylistEditHeaderRenderer")?.optString("privacy")?.takeIf(String::isNotBlank)?.let { privacy = it }
    }
    return privacy
}
