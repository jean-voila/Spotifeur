package fr.spotifeur.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Last known library and detail pages, on private storage: what the app shows first, with or without network. */
internal class CatalogStore(context: Context) {
    private val root = File(context.applicationContext.noBackupFilesDir, "catalog")
    private val detailsDir = File(root, "details")
    private val libraryFile = File(root, "library.json")

    fun loadLibrary(): HomeLibrary? = read(libraryFile)?.let(::decodeLibrary)
    fun saveLibrary(library: HomeLibrary) = write(libraryFile, encodeLibrary(library))
    fun libraryAgeMs(): Long = age(libraryFile)

    fun loadDetail(id: String): CatalogDetail? = read(detailFile(id))?.let(::decodeDetail)
    fun saveDetail(id: String, detail: CatalogDetail) = write(detailFile(id), encodeDetail(detail))
    fun detailAgeMs(id: String): Long = age(detailFile(id))
    fun removeDetail(id: String) { detailFile(id).delete() }

    fun sizeBytes(): Long = root.walkTopDown().filter(File::isFile).sumOf(File::length)
    fun clear() { root.deleteRecursively() }

    private fun detailFile(id: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(detailsDir, "$hash.json")
    }
    private fun age(file: File): Long = if (file.exists()) System.currentTimeMillis() - file.lastModified() else Long.MAX_VALUE
    private fun read(file: File): String? = try { if (file.exists()) file.readText() else null } catch (_: java.io.IOException) { null }
    /** A page cut short by the system must not replace the previous one. */
    private fun write(file: File, text: String) {
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.path + ".tmp")
            temporary.writeText(text)
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (_: java.io.IOException) { /* The next refresh writes it again. */ }
    }
}

internal fun encodeLibrary(library: HomeLibrary): String = JSONObject()
    .put("playlists", JSONArray(encodePins(library.playlists)))
    .put("albums", JSONArray(encodePins(library.albums)))
    .put("artists", JSONArray(encodePins(library.artists)))
    .put("savedTracks", JSONArray(encodePins(library.savedTracks)))
    .put("savedTrackCount", library.savedTrackCount)
    .put("savedTrackUris", JSONArray(library.savedTrackUris))
    .put("savedTracksLoaded", library.savedTracksLoaded)
    .toString()

internal fun decodeLibrary(text: String): HomeLibrary? = try {
    val json = JSONObject(text)
    val uris = json.getJSONArray("savedTrackUris")
    HomeLibrary(
        playlists = decodePins(json.getJSONArray("playlists").toString()),
        albums = decodePins(json.getJSONArray("albums").toString()),
        artists = decodePins(json.getJSONArray("artists").toString()),
        savedTracks = decodePins(json.getJSONArray("savedTracks").toString()),
        savedTrackCount = json.getInt("savedTrackCount"),
        savedTrackUris = (0 until uris.length()).map(uris::getString),
        savedTracksLoaded = json.getInt("savedTracksLoaded"),
    )
} catch (_: org.json.JSONException) { null }

internal fun encodeDetail(detail: CatalogDetail): String = JSONObject()
    .put("item", JSONArray(encodePins(listOf(detail.item))))
    .put("tracks", JSONArray(encodePins(detail.tracks)))
    .put("related", JSONArray(encodePins(detail.related)))
    .put("nextPage", detail.nextPage ?: JSONObject.NULL)
    .put("total", detail.total)
    .apply { detail.artist?.let { put("artist", encodeArtistProfile(it)) } }
    .toString()

private fun encodeArtistProfile(profile: ArtistProfile): JSONObject = JSONObject()
    .put("monthlyListeners", profile.monthlyListeners ?: JSONObject.NULL)
    .put("worldRank", profile.worldRank ?: JSONObject.NULL)
    .put("playCounts", JSONObject(profile.playCounts as Map<*, *>))
    .put("popularReleases", JSONArray(profile.popularReleases))
    .put("albums", JSONArray(profile.albums))
    .put("singles", JSONArray(profile.singles))
    .put("compilations", JSONArray(profile.compilations))
    .put("appearsOn", JSONArray(profile.appearsOn))
    .put("biography", profile.biography)
    .put("headerImageUrl", profile.headerImageUrl ?: JSONObject.NULL)
    .put("approximate", profile.approximate)

private fun decodeArtistProfile(json: JSONObject): ArtistProfile {
    fun ids(name: String) = json.optJSONArray(name)?.let { list -> (0 until list.length()).map(list::getString) }.orEmpty()
    val counts = json.optJSONObject("playCounts")
    return ArtistProfile(
        monthlyListeners = if (json.isNull("monthlyListeners")) null else json.optLong("monthlyListeners"),
        worldRank = if (json.isNull("worldRank")) null else json.optInt("worldRank"),
        playCounts = counts?.keys()?.asSequence()?.associateWith { counts.getLong(it) }.orEmpty(),
        popularReleases = ids("popularReleases"), albums = ids("albums"), singles = ids("singles"),
        compilations = ids("compilations"), appearsOn = ids("appearsOn"),
        biography = json.optString("biography"),
        headerImageUrl = if (json.isNull("headerImageUrl")) null else json.optString("headerImageUrl"),
        approximate = json.optBoolean("approximate"),
    )
}

internal fun decodeDetail(text: String): CatalogDetail? = try {
    val json = JSONObject(text)
    decodePins(json.getJSONArray("item").toString()).firstOrNull()?.let { item ->
        CatalogDetail(
            item = item,
            tracks = decodePins(json.getJSONArray("tracks").toString()),
            related = decodePins(json.getJSONArray("related").toString()),
            nextPage = if (json.isNull("nextPage")) null else json.getString("nextPage"),
            total = json.getInt("total"),
            artist = json.optJSONObject("artist")?.let(::decodeArtistProfile),
        )
    }
} catch (_: org.json.JSONException) { null }
