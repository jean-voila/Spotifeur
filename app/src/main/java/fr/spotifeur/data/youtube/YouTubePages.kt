package fr.spotifeur.data.youtube

import fr.spotifeur.data.ArtistProfile
import fr.spotifeur.data.CatalogDetail
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

const val YTM_ALBUM_PREFIX = "ytm:album:"
const val YTM_ARTIST_PREFIX = "ytm:artist:"

/** The album and artists YouTube Music links to a track. */
data class YouTubeRelations(val album: Music?, val artists: List<Music>)

/**
 * Album and artist pages of YouTube Music (InnerTube `browse` with `MPREb_…` and `UC…` IDs), read
 * anonymously like the rest of the catalogue. Every call blocks: run it on an IO thread.
 */
object YouTubePages {
    private val relations = ConcurrentHashMap<String, YouTubeRelations>()

    /** Kept from each search or browse row, so a track menu can open its album or artist at once. */
    internal fun remember(videoId: String, value: YouTubeRelations) {
        if (value.album != null || value.artists.isNotEmpty()) relations[videoId] = value
    }

    /** From what search rows gave, then the `next` endpoint, then a search for the track itself. */
    fun relations(videoId: String, title: String, artist: String): YouTubeRelations? {
        relations[videoId]?.takeIf { it.album != null }?.let { return it }
        val next = runCatching { YouTubeMusic.browseRequest("next", JSONObject().put("videoId", videoId).put("isAudioOnly", true)) }.getOrNull()
        if (next != null) {
            var byline: JSONArray? = null
            walkJson(next) { node ->
                val panel = node.optJSONObject("playlistPanelVideoRenderer") ?: return@walkJson
                if (byline == null && panel.optString("videoId") == videoId) byline = panel.optJSONObject("longBylineText")?.optJSONArray("runs")
            }
            byline?.let(::relationsOf)?.let { remember(videoId, it) }
        }
        relations[videoId]?.takeIf { it.album != null }?.let { return it }
        if (title.isNotBlank()) runCatching { YouTubeMusic.searchSongs("$title $artist".trim()) }
        relations[videoId]?.let { return it }
        return relations[videoId]
    }

    /**
     * The unfiltered search: the top result card, then songs, videos, albums, artists and playlists
     * mixed in YouTube Music's own order. Episodes, podcasts and user profiles are left out.
     */
    fun searchAll(query: String): List<Music> {
        val response = YouTubeMusic.browseRequest("search", JSONObject().put("query", query))
        val found = ArrayList<Music>()
        var cardArtist = ""
        walkJson(response) { node ->
            node.optJSONObject("musicCardShelfRenderer")?.let { card ->
                val run = card.optJSONObject("title")?.runs()?.optJSONObject(0) ?: return@let
                val entity = entityOf(run, card.optJSONObject("subtitle")?.optJSONArray("runs")?.texts().orEmpty().filter { it != " • " },
                    thumbnailOf(card.optJSONObject("thumbnail")))
                if (entity != null) { found += entity; if (entity.kind == MusicKind.Artist) cardArtist = entity.title }
            }
            val row = node.optJSONObject("musicResponsiveListItemRenderer") ?: return@walkJson
            searchRow(row, cardArtist)?.let(found::add)
        }
        return found.distinctBy(Music::id)
    }

    private fun searchRow(row: JSONObject, cardArtist: String): Music? {
        val flex = row.optJSONArray("flexColumns") ?: return null
        val second = flex.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")?.texts().orEmpty().filter { it != " • " }
        val label = second.firstOrNull().orEmpty()
        if (label == "Episode" || label == "Podcast" || label == "Profile") return null
        val browse = row.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        if (browse == null) return parseTrackRow(row)?.let { if (it.subtitle.isBlank()) it.copy(subtitle = cardArtist) else it }
        val titleRun = flex.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0) ?: return null
        val image = row.optJSONObject("thumbnail")?.let(::thumbnailOf)
        return entityOf(JSONObject(titleRun.toString()).put("navigationEndpoint", JSONObject().put("browseEndpoint", browse)), second, image)
    }

    /** An album, artist or playlist link with its subtitle parts ("Album", "Daft Punk", "2013"). */
    private fun entityOf(run: JSONObject, subtitle: List<String>, image: String?): Music? {
        val title = run.optString("text").takeIf(String::isNotBlank) ?: return null
        val picture = image?.let { largestYouTubeImage(it, "") }
        run.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf(String::isNotBlank)?.let { videoId ->
            return Music(YTM_TRACK_PREFIX + videoId, title, subtitle.getOrNull(1).orEmpty(), MusicKind.Track,
                imageUrl = largestYouTubeImage(image, videoId))
        }
        val browse = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: return null
        val id = browse.optString("browseId").takeIf(String::isNotBlank) ?: return null
        val type = browse.optJSONObject("browseEndpointContextSupportedConfigs")?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
        val details = subtitle.drop(1)
        return when (type) {
            "MUSIC_PAGE_TYPE_ALBUM" -> Music(YTM_ALBUM_PREFIX + id, title, details.firstOrNull().orEmpty(), MusicKind.Album, imageUrl = picture,
                note = details.lastOrNull { it.matches(Regex("\\d{4}")) }.orEmpty())
            "MUSIC_PAGE_TYPE_ARTIST" -> Music(YTM_ARTIST_PREFIX + id, title, "Artist", MusicKind.Artist, imageUrl = picture)
            "MUSIC_PAGE_TYPE_PLAYLIST" -> Music(YTM_PLAYLIST_PREFIX + id.removePrefix("VL"), title, details.firstOrNull().orEmpty(),
                MusicKind.Playlist, imageUrl = picture, ownerUsername = details.firstOrNull())
            else -> null
        }
    }

    fun album(browseId: String): CatalogDetail {
        val response = YouTubeMusic.browseRequest("browse", JSONObject().put("browseId", browseId))
        val header = responsiveHeader(response) ?: throw YouTubeFailure("This album is unavailable.")
        val title = header.optJSONObject("title")?.runs()?.firstText() ?: throw YouTubeFailure("This album is unavailable.")
        val artists = header.optJSONObject("straplineTextOne")?.optJSONArray("runs")?.let(::artistsOf).orEmpty()
        val artistNames = artists.joinToString(", ") { it.title }
        val year = header.optJSONObject("subtitle")?.optJSONArray("runs")?.texts()?.lastOrNull { it.matches(Regex("\\d{4}")) }.orEmpty()
        val image = thumbnail(header)?.let { largestYouTubeImage(it, "") }
        val album = Music(YTM_ALBUM_PREFIX + browseId, title, artistNames, MusicKind.Album, imageUrl = image, note = year)
        val tracks = parseTrackRows(response).map { track ->
            track.copy(subtitle = track.subtitle.ifBlank { artistNames }, album = title, imageUrl = image ?: track.imageUrl)
        }
        val albumMusic = album
        tracks.forEach { track -> track.youTubeVideoId?.let { remember(it, YouTubeRelations(albumMusic, artists)) } }
        return CatalogDetail(album.copy(tracks = tracks.map(Music::id)), tracks, artists)
    }

    fun artist(browseId: String): CatalogDetail {
        val response = YouTubeMusic.browseRequest("browse", JSONObject().put("browseId", browseId))
        var header: JSONObject? = null
        walkJson(response) { node ->
            if (header == null) header = node.optJSONObject("musicImmersiveHeaderRenderer") ?: node.optJSONObject("musicVisualHeaderRenderer")
        }
        val head = header ?: responsiveHeader(response) ?: throw YouTubeFailure("This artist is unavailable.")
        val name = head.optJSONObject("title")?.runs()?.firstText() ?: throw YouTubeFailure("This artist is unavailable.")
        val picture = thumbnail(head)
        val description = head.optJSONObject("description")?.optJSONArray("runs")?.texts()?.joinToString("").orEmpty()
        val audience = head.optJSONObject("monthlyListenerCount")?.optJSONArray("runs")?.firstText()
        val artist = Music(YTM_ARTIST_PREFIX + browseId, name, "Artist", MusicKind.Artist, imageUrl = picture, note = description)
        var songs = emptyList<Music>()
        val plays = HashMap<String, Long>()
        val albums = ArrayList<Music>()
        val singles = ArrayList<Music>()
        val similar = ArrayList<Music>()
        walkJson(response) { node ->
            node.optJSONObject("musicShelfRenderer")?.let { shelf ->
                if (songs.isNotEmpty()) return@let
                val rows = shelf.optJSONArray("contents") ?: return@let
                songs = (0 until rows.length()).mapNotNull { index ->
                    val row = rows.optJSONObject(index)?.optJSONObject("musicResponsiveListItemRenderer") ?: return@mapNotNull null
                    parseTrackRow(row)?.also { track -> playsOf(row)?.let { plays[track.id] = it } }
                }
            }
            node.optJSONObject("musicCarouselShelfRenderer")?.let { carousel ->
                val title = carousel.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                    ?.optJSONObject("title")?.runs()?.firstText().orEmpty()
                val items = carousel.optJSONArray("contents") ?: return@let
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index)?.optJSONObject("musicTwoRowItemRenderer")?.let { twoRowItem(it, name) } ?: continue
                    when {
                        item.kind == MusicKind.Artist -> similar += item
                        item.kind == MusicKind.Album && title.contains("single", ignoreCase = true) -> singles += item
                        item.kind == MusicKind.Album -> albums += item
                    }
                }
            }
        }
        val releases = (albums + singles).distinctBy(Music::id)
        val profile = ArtistProfile(
            monthlyListeners = audience?.let(::abbreviatedCount), approximate = true,
            playCounts = plays, albums = albums.map(Music::id), singles = singles.map(Music::id),
            biography = description,
        )
        return CatalogDetail(artist, songs, releases + similar.distinctBy(Music::id), artist = profile)
    }

    private fun twoRowItem(item: JSONObject, artistName: String): Music? {
        val title = item.optJSONObject("title")?.runs()?.firstText() ?: return null
        val browse = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: return null
        val id = browse.optString("browseId").takeIf(String::isNotBlank) ?: return null
        val type = browse.optJSONObject("browseEndpointContextSupportedConfigs")?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
        val subtitle = item.optJSONObject("subtitle")?.optJSONArray("runs")?.texts().orEmpty().filter { it != " • " }
        val image = item.optJSONObject("thumbnailRenderer")?.let(::thumbnailOf)?.let { largestYouTubeImage(it, "") }
        return when (type) {
            "MUSIC_PAGE_TYPE_ALBUM" -> Music(YTM_ALBUM_PREFIX + id, title, artistName, MusicKind.Album, imageUrl = image,
                note = subtitle.lastOrNull { it.matches(Regex("\\d{4}")) }.orEmpty())
            "MUSIC_PAGE_TYPE_ARTIST" -> Music(YTM_ARTIST_PREFIX + id, title, "Artist", MusicKind.Artist, imageUrl = image)
            else -> null
        }
    }

    private fun responsiveHeader(response: JSONObject): JSONObject? {
        var header: JSONObject? = null
        walkJson(response) { node -> if (header == null) header = node.optJSONObject("musicResponsiveHeaderRenderer") }
        return header
    }

    private fun thumbnail(header: JSONObject): String? = thumbnailOf(header.optJSONObject("thumbnail"))

    private fun thumbnailOf(holder: JSONObject?): String? {
        val list = holder?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: holder?.optJSONObject("croppedSquareThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: return null
        return list.optJSONObject(list.length() - 1)?.optString("url")?.takeIf(String::isNotBlank)
    }

    private fun playsOf(row: JSONObject): Long? {
        val flex = row.optJSONArray("flexColumns") ?: return null
        for (column in 0 until flex.length()) {
            val runs = flex.optJSONObject(column)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")?.optJSONArray("runs") ?: continue
            runs.texts().firstNotNullOfOrNull { PLAYS.matchEntire(it)?.groupValues?.get(1) }?.let { return abbreviatedCount(it) }
        }
        return null
    }

    /** Runs that link to artist pages, as albums and track bylines list them. */
    internal fun artistsOf(runs: JSONArray): List<Music> = (0 until runs.length()).mapNotNull { index ->
        val run = runs.optJSONObject(index) ?: return@mapNotNull null
        val browse = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: return@mapNotNull null
        val type = browse.optJSONObject("browseEndpointContextSupportedConfigs")?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
        if (type != "MUSIC_PAGE_TYPE_ARTIST" && !browse.optString("browseId").startsWith("UC")) return@mapNotNull null
        Music(YTM_ARTIST_PREFIX + browse.optString("browseId"), run.optString("text"), "Artist", MusicKind.Artist)
    }

    internal fun relationsOf(runs: JSONArray): YouTubeRelations {
        var album: Music? = null
        for (index in 0 until runs.length()) {
            val run = runs.optJSONObject(index) ?: continue
            val browse = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: continue
            val type = browse.optJSONObject("browseEndpointContextSupportedConfigs")?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
            if (type == "MUSIC_PAGE_TYPE_ALBUM") album = Music(YTM_ALBUM_PREFIX + browse.optString("browseId"), run.optString("text"), "", MusicKind.Album)
        }
        return YouTubeRelations(album, artistsOf(runs))
    }

    private fun JSONObject.runs(): JSONArray? = optJSONArray("runs")
    private fun JSONArray.firstText(): String? = optJSONObject(0)?.optString("text")?.takeIf(String::isNotBlank)
    private fun JSONArray.texts(): List<String> = (0 until length()).mapNotNull { optJSONObject(it)?.optString("text") }

    private val PLAYS = Regex("^([\\d.,]+[KMB]?) (?:plays|views)$")
}

/** "28.3M", "150K", "1.2B": the approximate value YouTube abbreviates. */
internal fun abbreviatedCount(text: String): Long? {
    val match = Regex("([\\d.,]+)\\s*([KMB])?").find(text.replace(",", "")) ?: return null
    val number = match.groupValues[1].toDoubleOrNull() ?: return null
    val scale = when (match.groupValues[2]) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }
    return (number * scale).toLong().takeIf { it > 0 }
}
