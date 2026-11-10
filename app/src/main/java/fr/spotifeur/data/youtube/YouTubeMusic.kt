// The catalogue requests, the anonymous visitor identity and the stream resolution follow Resona
// (https://github.com/code-saksham-hash/Resona, MIT, Copyright (c) 2026 Code.Saksham), adapted to OkHttp and org.json.
package fr.spotifeur.data.youtube

import android.content.Context
import androidx.core.content.edit
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

const val YTM_TRACK_PREFIX = "ytm:track:"
const val YTM_PLAYLIST_PREFIX = "ytm:playlist:"

val Music.isYouTubeTrack: Boolean get() = id.startsWith(YTM_TRACK_PREFIX)
val Music.youTubeVideoId: String? get() = id.takeIf { it.startsWith(YTM_TRACK_PREFIX) }?.removePrefix(YTM_TRACK_PREFIX)

class YouTubeFailure(message: String, val status: String? = null) : IOException(message)

data class YouTubePage(val tracks: List<Music>, val next: String? = null)
data class YouTubePlaylist(val id: String, val title: String, val imageUrl: String?, val tracks: List<Music>)

/** One audio format of a video, as the player endpoint describes it. */
internal data class YouTubeFormat(
    val itag: Int, val url: String?, val signatureCipher: String?, val mimeType: String,
    val bitrate: Long, val contentLength: Long, val sampleRate: Int,
)

/** A resolved address: valid for a few hours, and only with the user agent of the client that obtained it. */
internal data class YouTubeStream(
    val videoId: String, val url: String, val userAgent: String, val client: String,
    val contentLength: Long, val mimeType: String, val bitrate: Long, val sampleRate: Int, val expiresAtMs: Long,
)

/**
 * YouTube Music's catalogue through the anonymous InnerTube endpoints of music.youtube.com.
 * Every call blocks: run it on an IO thread.
 */
object YouTubeMusic {
    private var app: Context? = null
    internal val http: OkHttpClient = OkHttpClient.Builder()
        // Offline mode and lost connections stop YouTube Music requests as they stop Spotify's.
        .addInterceptor { chain ->
            if (!fr.spotifeur.data.NetworkMonitor.online.value) throw IOException("No connection")
            chain.proceed(chain.request())
        }
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true).build()
    private val streams = HashMap<String, YouTubeStream>()
    private var playerJs: Pair<String, String>? = null
    private val decipher by lazy { YouTubeDecipher(checkNotNull(app)) }

    fun init(context: Context) { if (app == null) app = context.applicationContext }

    private val preferences get() = app?.getSharedPreferences("youtube_music", Context.MODE_PRIVATE)
    @Volatile private var visitor: String? = null
    private val visitorData: String?
        get() = visitor ?: preferences?.getString(KEY_VISITOR, null)?.also { visitor = it }

    /** Every response carries a current anonymous identity; reusing it keeps the player endpoint open. */
    private fun learnVisitor(response: JSONObject) {
        val token = response.optJSONObject("responseContext")?.optString("visitorData").orEmpty()
        if (token.isBlank()) return
        val decoded = URLDecoder.decode(token, "UTF-8")
        if (decoded != visitor) { visitor = decoded; preferences?.edit { putString(KEY_VISITOR, decoded) } }
    }

    // Catalogue ----------------------------------------------------------------------------------

    /** Songs first, then official uploads of artists the songs already show, as YouTube Music does. */
    fun search(query: String): YouTubePage {
        val songs = musicRequest("search", JSONObject().put("query", query).put("params", SONGS_FILTER))
        val videos = runCatching { musicRequest("search", JSONObject().put("query", query).put("params", VIDEOS_FILTER)) }.getOrNull()
        val songTracks = parseTrackRows(songs).distinctBy(Music::id)
        val videoTracks = videos?.let(::parseTrackRows).orEmpty()
        return YouTubePage(mergeVideoUploads(songTracks, videoTracks), findContinuation(songs, preferShelf = false))
    }

    /** The songs shelf alone, with its continuation: what the mixed search adds to for paging. */
    fun searchSongsPage(query: String): YouTubePage {
        val songs = musicRequest("search", JSONObject().put("query", query).put("params", SONGS_FILTER))
        return YouTubePage(parseTrackRows(songs).distinctBy(Music::id), findContinuation(songs, preferShelf = false))
    }

    fun searchMore(token: String): YouTubePage {
        val response = musicRequest("search", JSONObject().put("continuation", token))
        return YouTubePage(parseTrackRows(response).distinctBy(Music::id), findContinuation(response, preferShelf = false))
    }

    internal fun searchVideos(query: String): List<Music> =
        parseTrackRows(musicRequest("search", JSONObject().put("query", query).put("params", VIDEOS_FILTER)))

    internal fun searchSongs(query: String): List<Music> =
        parseTrackRows(musicRequest("search", JSONObject().put("query", query).put("params", SONGS_FILTER)))

    /** Every track of a public playlist, following its continuations. */
    fun playlist(playlistId: String, limit: Int = MAX_PLAYLIST_TRACKS): YouTubePlaylist {
        val id = playlistId.removePrefix("VL")
        val first = musicRequest("browse", JSONObject().put("browseId", "VL$id"))
        val tracks = ArrayList(parseTrackRows(first))
        if (tracks.isEmpty()) throw YouTubeFailure("This playlist is empty, private or unavailable.")
        val title = playlistTitle(first) ?: "YouTube"
        val image = playlistImage(first)
        val seen = HashSet<String>()
        var token = findContinuation(first, preferShelf = true)
        while (token != null && seen.add(token) && tracks.size < limit) {
            val page = musicRequest("browse", JSONObject().put("continuation", token))
            val rows = parseTrackRows(page)
            if (rows.isEmpty()) break
            tracks += rows
            token = findContinuation(page, preferShelf = false)
        }
        return YouTubePlaylist(id, title, image, tracks.take(limit))
    }

    /** A catalogue request for the album, artist and track pages. */
    internal fun browseRequest(endpoint: String, body: JSONObject): JSONObject = musicRequest(endpoint, body)

    private fun musicRequest(endpoint: String, body: JSONObject): JSONObject {
        val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", MUSIC_CLIENT_VERSION).put("hl", "en").put("gl", "US")
        visitorData?.let { client.put("visitorData", it) }
        body.put("context", JSONObject().put("client", client))
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false&key=$MUSIC_API_KEY")
            .header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/")
            .header("User-Agent", BROWSER_AGENT).header("X-Goog-Api-Format-Version", "1")
            .apply { visitorData?.let { header("X-Goog-Visitor-Id", it) } }
            .post(body.toString().toRequestBody(JSON))
            .build()
        val started = android.os.SystemClock.elapsedRealtime()
        val call = try { http.newCall(request).execute() } catch (failure: IOException) {
            android.util.Log.d("SpotifeurSearch", "ytm $endpoint failed after ${android.os.SystemClock.elapsedRealtime() - started}ms ${failure.javaClass.simpleName}")
            throw failure
        }
        call.use { response ->
            val headersMs = android.os.SystemClock.elapsedRealtime() - started
            if (!response.isSuccessful) {
                android.util.Log.d("SpotifeurSearch", "ytm $endpoint ${response.code} after ${headersMs}ms")
                throw YouTubeFailure("YouTube Music answered ${response.code}.", response.code.toString())
            }
            val json = JSONObject(response.body?.string().orEmpty())
            learnVisitor(json)
            return json
        }
    }

    // Streams ------------------------------------------------------------------------------------

    @Synchronized internal fun cachedStream(videoId: String): YouTubeStream? =
        streams[videoId]?.takeIf { it.expiresAtMs > System.currentTimeMillis() + STREAM_MARGIN_MS }

    @Synchronized internal fun forgetStream(videoId: String) { streams.remove(videoId) }

    /** Resolves the best audio-only format, trying each client until one hands back a usable address. */
    internal fun stream(videoId: String, excluded: Set<String> = emptySet()): YouTubeStream {
        if (excluded.isEmpty()) cachedStream(videoId)?.let { return it }
        val clients = YouTubeClient.entries.filterNot { it.clientName in excluded }
        if (clients.isEmpty()) throw YouTubeFailure("No client could open this track.", "ALL_CLIENTS_EXCLUDED")
        val initial = visitorData
        val (response, client) = try {
            playerWithFallback(videoId, clients)
        } catch (failure: YouTubeFailure) {
            // A gated first pass usually hands back a fresh identity: retry the chain once with it.
            val fresh = visitorData?.takeIf { it != initial } ?: mintVisitor()?.takeIf { it != initial } ?: throw failure
            visitor = fresh
            playerWithFallback(videoId, clients)
        }
        val formats = audioFormats(response)
        if (formats.isEmpty()) throw YouTubeFailure("No audio format is available.", "NO_AUDIO_FORMAT")
        response.optJSONObject("playerConfig")?.optJSONObject("audioConfig")?.let { audio ->
            if (audio.has("loudnessDb")) fr.spotifeur.playback.TrackLoudness.rememberYouTube(videoId, audio.optDouble("loudnessDb").toFloat())
        }
        val js = if (formats.any { it.url == null }) runCatching { playerScript(videoId) }.getOrNull() else null
        var last: Exception? = null
        for (format in preferredOrder(formats)) {
            try {
                val url = format.url ?: decipher.playableUrl(format, js)
                val expires = url.toHttpUrlOrNull()?.queryParameter("expire")?.toLongOrNull()?.times(1000)
                    ?: (System.currentTimeMillis() + DEFAULT_LIFETIME_MS)
                val resolved = YouTubeStream(videoId, url, client.userAgent, client.clientName, format.contentLength,
                    format.mimeType, format.bitrate, format.sampleRate, expires)
                synchronized(this) { streams[videoId] = resolved }
                return resolved
            } catch (failure: Exception) { last = failure }
        }
        throw (last as? YouTubeFailure) ?: YouTubeFailure("No audio format could be opened.", "NO_AUDIO_FORMAT")
    }

    /** After the CDN refused an address, the anonymous identity that obtained it is the likely cause. */
    internal fun renewIdentity() {
        visitor = null
        preferences?.edit { remove(KEY_VISITOR) }
        mintVisitor()?.let { token -> visitor = token; preferences?.edit { putString(KEY_VISITOR, token) } }
    }

    private fun playerWithFallback(videoId: String, clients: List<YouTubeClient>): Pair<JSONObject, YouTubeClient> {
        var status = "UNKNOWN"
        var reason = "No client answered."
        var timestamp: Int? = null
        var timestampLoaded = false
        for (client in clients) {
            if (client.sendsSignatureTimestamp && !timestampLoaded) {
                timestampLoaded = true
                timestamp = runCatching { signatureTimestamp(playerScript(videoId)) }.getOrNull()
            }
            val response = try { player(videoId, client, timestamp) } catch (failure: IOException) {
                reason = failure.message ?: reason; continue
            }
            learnVisitor(response)
            val playability = response.optJSONObject("playabilityStatus")
            val current = playability?.optString("status").orEmpty()
            val playable = audioFormats(response).any { it.url != null || it.signatureCipher != null }
            if (current == "OK" && playable) return response to client
            status = current.ifBlank { "UNKNOWN" }
            reason = if (current == "OK") "This client needs a proof-of-origin token." else playability?.optString("reason").orEmpty().ifBlank { reason }
        }
        throw YouTubeFailure(reason, status)
    }

    private fun player(videoId: String, client: YouTubeClient, timestamp: Int?): JSONObject {
        val visitor = visitorData
        val request = Request.Builder().url(client.playerUrl)
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.clientNumber)
            .header("X-YouTube-Client-Version", client.clientVersion)
            .header("Origin", "https://www.youtube.com").header("Referer", "https://www.youtube.com/")
            .apply { visitor?.let { header("X-Goog-Visitor-Id", it) } }
            .post(client.playerBody(videoId, timestamp, visitor).toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw YouTubeFailure("The player endpoint answered ${response.code}.", response.code.toString())
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun playerScript(videoId: String): String {
        val html = http.newCall(Request.Builder().url("https://www.youtube.com/watch?v=$videoId")
            .header("User-Agent", BROWSER_AGENT).build()).execute().use { it.body?.string().orEmpty() }
        Regex(""""VISITOR_DATA":"([^"]+)"""").find(html)?.groupValues?.get(1)?.let { token ->
            visitor = token; preferences?.edit { putString(KEY_VISITOR, token) }
        }
        val path = Regex(""""jsUrl"\s*:\s*"(/s/player/[a-f0-9]+/[^"]+base\.js)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""(/s/player/[a-f0-9]+/player_ias\.vflset/[^"]+base\.js)""").find(html)?.groupValues?.get(1)
            ?: throw YouTubeFailure("The player script address was not found.")
        synchronized(this) { playerJs?.takeIf { it.first == path }?.let { return it.second } }
        val script = http.newCall(Request.Builder().url("https://www.youtube.com$path").header("User-Agent", BROWSER_AGENT).build())
            .execute().use { it.body?.string().orEmpty() }
        synchronized(this) { playerJs = path to script }
        return script
    }

    private fun signatureTimestamp(script: String): Int? =
        Regex("""(?:signatureTimestamp|sts)\s*[=:]\s*(\d{5,6})""").find(script)?.groupValues?.get(1)?.toIntOrNull()

    private fun mintVisitor(): String? = runCatching {
        val body = JSONObject().put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB").put("clientVersion", YouTubeClient.WEB.clientVersion).put("hl", "en").put("gl", "US")))
        http.newCall(Request.Builder().url("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
            .header("User-Agent", YouTubeClient.WEB.userAgent).post(body.toString().toRequestBody(JSON)).build())
            .execute().use { response ->
                JSONObject(response.body?.string().orEmpty()).optJSONObject("responseContext")?.optString("visitorData")
                    ?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf(String::isNotBlank)
            }
    }.getOrNull()

    private const val KEY_VISITOR = "visitor"
    private const val MUSIC_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    internal const val MUSIC_CLIENT_VERSION = "1.20250101.01.00"
    private const val SONGS_FILTER = "EgWKAQIIAWoKEAoQAxAEEAkQBQ%3D%3D"
    private const val VIDEOS_FILTER = "EgWKAQIQAWoKEAoQAxAEEAkQBQ%3D%3D"
    internal const val BROWSER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val STREAM_MARGIN_MS = 10 * 60 * 1000L
    private const val DEFAULT_LIFETIME_MS = 5 * 60 * 60 * 1000L
    private const val MAX_PLAYLIST_TRACKS = 5000
    private val JSON = "application/json".toMediaType()
}

// Parsing ----------------------------------------------------------------------------------------

private val DURATION = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
private val TYPE_LABELS = setOf("Song", "Video", "Episode")
private const val ALBUM_PAGE = "MUSIC_PAGE_TYPE_ALBUM"

internal fun walkJson(node: Any?, visit: (JSONObject) -> Unit) {
    when (node) {
        is JSONObject -> { visit(node); node.keys().forEach { walkJson(node.opt(it), visit) } }
        is JSONArray -> for (index in 0 until node.length()) walkJson(node.opt(index), visit)
    }
}

/** Every track row of a search, browse or continuation response, in order. */
internal fun parseTrackRows(response: JSONObject): List<Music> {
    val rows = ArrayList<Music>()
    walkJson(response) { node -> node.optJSONObject("musicResponsiveListItemRenderer")?.let { parseTrackRow(it) }?.let(rows::add) }
    return rows
}

private fun runsOf(column: JSONObject?): JSONArray? =
    column?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")

internal fun parseTrackRow(renderer: JSONObject): Music? {
    val flex = renderer.optJSONArray("flexColumns") ?: return null
    val titleRun = runsOf(flex.optJSONObject(0))?.optJSONObject(0) ?: return null
    val title = titleRun.optString("text").takeIf(String::isNotBlank) ?: return null
    val videoId = titleRun.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf(String::isNotBlank)
        ?: renderer.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf(String::isNotBlank)
        ?: return null
    // Groups of runs separated by bullets: [type label], artists, album or views, duration.
    val groups = ArrayList<List<JSONObject>>()
    for (column in 1 until flex.length()) {
        val runs = runsOf(flex.optJSONObject(column)) ?: continue
        var current = ArrayList<JSONObject>()
        for (index in 0 until runs.length()) {
            val run = runs.optJSONObject(index) ?: continue
            if (run.optString("text") == " • ") { if (current.isNotEmpty()) groups += current; current = ArrayList() }
            else current += run
        }
        if (current.isNotEmpty()) groups += current
    }
    val texts = groups.map { group -> group.joinToString("") { it.optString("text") }.trim() }
    val fixed = renderer.optJSONArray("fixedColumns")?.optJSONObject(0)
        ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")
        ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
    val duration = texts.lastOrNull { DURATION.matches(it) } ?: fixed?.takeIf { DURATION.matches(it) }
    val albumIndex = groups.indexOfFirst { group ->
        group.any { run -> pageType(run) == ALBUM_PAGE }
    }
    val rest = texts.withIndex().filter { (index, text) ->
        text.isNotBlank() && !DURATION.matches(text) && index != albumIndex && !(index == 0 && text in TYPE_LABELS) && !COUNT_TEXT.matches(text)
    }
    // Album and artist links of the row, so the track menu can open them.
    val links = groups.flatten()
    YouTubePages.remember(videoId, YouTubeRelations(
        links.firstOrNull { pageType(it) == ALBUM_PAGE }?.let { run ->
            run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")?.takeIf(String::isNotBlank)
                ?.let { Music(YTM_ALBUM_PREFIX + it, run.optString("text"), "", MusicKind.Album) }
        },
        YouTubePages.artistsOf(JSONArray(links)),
    ))
    val artist = rest.firstOrNull()?.value.orEmpty()
    val thumbnails = renderer.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
        ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
    val image = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url")?.takeIf(String::isNotBlank)
    return Music(
        id = YTM_TRACK_PREFIX + videoId, title = title, subtitle = artist, kind = MusicKind.Track,
        imageUrl = largestYouTubeImage(image, videoId), durationMs = duration?.let(::durationMs) ?: 0,
        album = if (albumIndex >= 0) texts[albumIndex] else "",
    )
}

private fun pageType(run: JSONObject): String? = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
    ?.optJSONObject("browseEndpointContextSupportedConfigs")?.optJSONObject("browseEndpointContextMusicConfig")
    ?.optString("pageType")

/** "233M plays", "15M views": a row's audience, never its artist. */
private val COUNT_TEXT = Regex("^[\\d.,]+[KMB]? (?:plays|views)$")

internal fun durationMs(text: String): Int = text.split(':').fold(0) { total, part -> total * 60 + (part.toIntOrNull() ?: 0) } * 1000

/** Album art is served at any size: ask for a large square. Video stills fall back to their HD frame. */
internal fun largestYouTubeImage(url: String?, videoId: String): String? = when {
    url == null -> "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    url.contains("googleusercontent.com") || url.contains("ggpht.com") -> url.replace(Regex("=w\\d+-h\\d+.*$"), "=w544-h544-l90-rj")
    else -> url
}

/** The next page token; [preferShelf] reads it from the track shelf rather than from suggestions. */
internal fun findContinuation(response: JSONObject, preferShelf: Boolean): String? {
    var command: String? = null
    var next: String? = null
    val root: Any = if (preferShelf) {
        var shelf: JSONObject? = null
        walkJson(response) { node -> if (shelf == null) node.optJSONObject("musicPlaylistShelfRenderer")?.let { shelf = it } }
        shelf ?: response
    } else response
    walkJson(root) { node ->
        if (command == null) node.optJSONObject("continuationCommand")?.optString("token")?.takeIf(String::isNotBlank)?.let { command = it }
        if (next == null) node.optJSONObject("nextContinuationData")?.optString("continuation")?.takeIf(String::isNotBlank)?.let { next = it }
    }
    return command ?: next
}

internal fun playlistTitle(response: JSONObject): String? {
    var title: String? = null
    walkJson(response) { node ->
        if (title != null) return@walkJson
        val header = node.optJSONObject("musicResponsiveHeaderRenderer") ?: node.optJSONObject("musicDetailHeaderRenderer")
            ?: node.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")?.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")
        title = header?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.takeIf(String::isNotBlank)
    }
    return title
}

internal fun playlistImage(response: JSONObject): String? {
    var image: String? = null
    walkJson(response) { node ->
        if (image != null) return@walkJson
        val thumbnails = node.optJSONObject("musicResponsiveHeaderRenderer")?.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        image = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url")?.takeIf(String::isNotBlank)
    }
    return image
}

internal fun audioFormats(response: JSONObject): List<YouTubeFormat> {
    val data = response.optJSONObject("streamingData") ?: return emptyList()
    return listOfNotNull(data.optJSONArray("adaptiveFormats"), data.optJSONArray("formats")).flatMap { array ->
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }.filter { it.optString("mimeType").startsWith("audio/") }.map { format ->
        YouTubeFormat(
            itag = format.optInt("itag"), url = format.optString("url").takeIf(String::isNotBlank),
            signatureCipher = format.optString("signatureCipher").takeIf(String::isNotBlank) ?: format.optString("cipher").takeIf(String::isNotBlank),
            mimeType = format.optString("mimeType"), bitrate = format.optLong("bitrate"),
            contentLength = format.optString("contentLength").toLongOrNull() ?: -1,
            sampleRate = format.optString("audioSampleRate").toIntOrNull() ?: 0,
        )
    }
}

/** Highest bitrate first; formats with a known length come first, since the player can then seek and cache them. */
internal fun preferredOrder(formats: List<YouTubeFormat>): List<YouTubeFormat> =
    formats.sortedWith(compareByDescending<YouTubeFormat> { it.contentLength > 0 }.thenByDescending { it.bitrate })

private val PARENTHETICAL = Regex("""[(\[][^)\]]*[)\]]""")
private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
internal fun String.matchingKey(): String = lowercase().replace(PARENTHETICAL, " ").replace(NON_ALPHANUMERIC, " ").trim()

/**
 * Official uploads that exist only as videos are kept when their channel is an artist the songs
 * shelf already shows; covers, lessons and lyric videos come from channels it does not show.
 */
internal fun mergeVideoUploads(songs: List<Music>, videos: List<Music>): List<Music> {
    if (videos.isEmpty()) return songs
    val artists = songs.mapTo(HashSet()) { it.subtitle.matchingKey() }
    val ids = songs.mapTo(HashSet(), Music::id)
    val keys = songs.mapTo(HashSet()) { "${it.title.matchingKey()}|${it.subtitle.matchingKey()}" }
    val kept = HashMap<Int, Music>()
    videos.forEachIndexed { rank, video ->
        val known = songs.isEmpty() || video.subtitle.matchingKey() in artists
        if (known && video.id !in ids && keys.add("${video.title.matchingKey()}|${video.subtitle.matchingKey()}")) kept[rank] = video
    }
    if (kept.isEmpty()) return songs
    val merged = ArrayList<Music>(songs.size + kept.size)
    repeat(maxOf(songs.size, videos.size)) { rank -> songs.getOrNull(rank)?.let(merged::add); kept[rank]?.let(merged::add) }
    return merged
}

/** A playlist id from a YouTube or YouTube Music link, or a bare id. */
fun youTubePlaylistId(input: String): String? {
    val text = input.trim()
    text.toHttpUrlOrNull()?.let { url ->
        if (!url.host.endsWith("youtube.com") && url.host != "youtu.be") return null
        url.queryParameter("list")?.takeIf(String::isNotBlank)?.let { return it }
        val browse = url.pathSegments.dropWhile { it != "browse" }.getOrNull(1)
        return browse?.takeIf { it.startsWith("VL") }?.removePrefix("VL")
    }
    return text.removePrefix("VL").takeIf { Regex("^(PL|OLAK5uy_|RD|LM|UU|FL)[A-Za-z0-9_-]{8,}$").matches(it) }
}
