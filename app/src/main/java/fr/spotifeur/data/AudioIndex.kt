package fr.spotifeur.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** One encrypted audio file of the CDN, as the media playlist describes it. */
internal data class AudioFileRecord(val hex: String, val expectedBytes: Long, val playlistUrl: String) {
    /** YouTube Music files are plain audio: nothing to prepare with the native engine. */
    val isYouTube: Boolean get() = hex.startsWith(YOUTUBE_FILE_PREFIX)
}

internal const val YOUTUBE_FILE_PREFIX = "yt-"

/** JSON has no NaN: absent values are written as null and read back as NaN. */
private fun Float.toSafeJson(): Any = if (isNaN()) JSONObject.NULL else toDouble()

internal data class AudioRecord(
    val track: Music,
    val file: AudioFileRecord? = null,
    val lastPlayedMs: Long = 0,
)

/**
 * What the app must know to read a track again without the service: its audio identifier, its
 * playlists exactly as received and the file they point to. Signed
 * addresses inside stored playlists only serve while they are valid; the data source renews them.
 */
internal class AudioIndex private constructor(context: Context) {
    private val root = File(context.applicationContext.noBackupFilesDir, "audio-index")
    private val playlists = File(root, "playlists")
    private val file = File(root, "index.json")
    private val audioIds = HashMap<String, String>()
    private val playableTracks = HashMap<String, String>()
    private val records = HashMap<String, AudioRecord>()
    private val playlistOwners = HashMap<String, String>()
    /** Spotify's file normalization per track: loudness and true peak in dB; NaN when the service gives none. */
    private val loudness = HashMap<String, FloatArray>()
    private var dirty = false
    private var lastWrite = 0L

    init { load() }

    @Synchronized fun audioId(track: String): String? = audioIds[track]
    @Synchronized fun playableTrack(track: String): String? = playableTracks[track]
    @Synchronized fun rememberAudioId(track: String, id: String, playable: String? = null) {
        val different = audioIds.put(track, id) != id
        val relinked = playable != null && playableTracks.put(track, playable) != playable
        if (different || relinked) changed()
    }
    @Synchronized fun loudness(track: String): FloatArray? = loudness[track]
    @Synchronized fun rememberLoudness(track: String, loudnessDb: Float, truePeakDb: Float) {
        val value = floatArrayOf(loudnessDb, truePeakDb)
        if (!value.contentEquals(loudness.put(track, value))) changed()
    }
    @Synchronized fun rememberPlaylistOwner(url: String, track: String) {
        if (playlistOwners.put(url, track) != track) changed()
    }
    @Synchronized fun attachFile(playlistUrl: String, file: AudioFileRecord) {
        val track = playlistOwners[playlistUrl] ?: return
        val current = records[track] ?: AudioRecord(Music(track, "", "", MusicKind.Track))
        if (current.file != file) { records[track] = current.copy(file = file); changed() }
    }
    /** A file read straight from its address, with no media playlist: a YouTube Music stream. */
    @Synchronized fun attachDirectFile(track: String, file: AudioFileRecord) {
        val current = records[track] ?: AudioRecord(Music(track, "", "", MusicKind.Track))
        if (current.file != file) { records[track] = current.copy(file = file); changed() }
    }
    /** The file of a variant, once its media playlist has been read. */
    @Synchronized fun fileOfPlaylist(url: String): AudioFileRecord? =
        playlistOwners[url]?.let(records::get)?.file?.takeIf { it.playlistUrl == url }
    @Synchronized fun playlistOfFile(hex: String): String? = records.values.firstOrNull { it.file?.hex == hex }?.file?.playlistUrl

    /** Keeps the metadata shown in the cache list; [played] also dates it as listened to. */
    @Synchronized fun describe(track: Music, played: Boolean) {
        val current = records[track.id] ?: AudioRecord(track)
        records[track.id] = current.copy(track = track, lastPlayedMs = if (played) System.currentTimeMillis() else current.lastPlayedMs)
        changed()
    }
    @Synchronized fun record(track: String): AudioRecord? = records[track]
    @Synchronized fun all(): List<AudioRecord> = records.values.toList()
    @Synchronized fun forget(track: String) {
        // The audio identifier and playlists stay: they cost nothing and avoid a request later on.
        records[track]?.let { records[track] = it.copy(lastPlayedMs = 0) }
        changed()
    }

    fun readPlaylist(url: String): String? = try {
        playlistFile(url).takeIf(File::exists)?.readText()
    } catch (_: IOException) { null }

    fun writePlaylist(url: String, text: String) {
        try {
            val target = playlistFile(url)
            target.parentFile?.mkdirs()
            val temporary = File(target.path + ".tmp")
            temporary.writeText(text)
            if (!temporary.renameTo(target)) temporary.delete()
        } catch (_: IOException) { /* The next listening asks for it again. */ }
    }

    fun sizeBytes(): Long = root.walkTopDown().filter(File::isFile).sumOf(File::length)

    @Synchronized fun flush() { if (dirty) write() }

    private fun changed() {
        dirty = true
        if (System.currentTimeMillis() - lastWrite > WRITE_INTERVAL_MS) write()
    }

    private fun playlistFile(url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(playlists, hash)
    }

    private fun write() {
        dirty = false
        lastWrite = System.currentTimeMillis()
        val json = JSONObject()
            .put("audioIds", JSONObject(audioIds as Map<*, *>))
            .put("playableTracks", JSONObject(playableTracks as Map<*, *>))
            .put("owners", JSONObject(playlistOwners as Map<*, *>))
            .put("loudness", JSONObject().apply {
                loudness.forEach { (track, value) -> put(track, JSONArray().put(value[0].toSafeJson()).put(value[1].toSafeJson())) }
            })
            .put("records", JSONArray().apply {
                records.values.forEach { record ->
                    put(JSONObject()
                        .put("track", JSONArray(encodePins(listOf(record.track))))
                        .put("lastPlayed", record.lastPlayedMs)
                        .apply {
                            record.file?.let { put("hex", it.hex); put("expected", it.expectedBytes); put("playlist", it.playlistUrl) }
                        })
                }
            })
        try {
            root.mkdirs()
            val temporary = File(file.path + ".tmp")
            temporary.writeText(json.toString())
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (_: IOException) { dirty = true }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            json.getJSONObject("audioIds").let { ids -> ids.keys().forEach { audioIds[it] = ids.getString(it) } }
            json.optJSONObject("playableTracks")?.let { ids -> ids.keys().forEach { playableTracks[it] = ids.getString(it) } }
            json.getJSONObject("owners").let { owners -> owners.keys().forEach { playlistOwners[it] = owners.getString(it) } }
            json.optJSONObject("loudness")?.let { values -> values.keys().forEach { track ->
                val pair = values.getJSONArray(track)
                loudness[track] = floatArrayOf(pair.optDouble(0, Double.NaN).toFloat(), pair.optDouble(1, Double.NaN).toFloat())
            } }
            val list = json.getJSONArray("records")
            for (index in 0 until list.length()) {
                val entry = list.getJSONObject(index)
                val track = decodePins(entry.getJSONArray("track").toString()).firstOrNull() ?: continue
                records[track.id] = AudioRecord(
                    track,
                    if (entry.has("hex")) AudioFileRecord(entry.getString("hex"), entry.getLong("expected"), entry.getString("playlist")) else null,
                    entry.getLong("lastPlayed"),
                )
            }
        } catch (_: org.json.JSONException) { /* Rebuilt while listening. */ }
        catch (_: IOException) { /* Rebuilt while listening. */ }
    }

    companion object {
        private const val WRITE_INTERVAL_MS = 5_000L
        @Volatile private var instance: AudioIndex? = null
        fun get(context: Context): AudioIndex = instance ?: synchronized(this) {
            instance ?: AudioIndex(context).also { instance = it }
        }
    }
}

private val FILE_PATH = Regex("/audio/([0-9a-fA-F]+)")

/** Identifies a CDN file whatever its signature. */
internal fun audioFileHex(path: String?): String? = path?.let { FILE_PATH.find(it)?.groupValues?.get(1)?.lowercase() }

/** The single file of a media playlist and how many bytes it spans, from its byte ranges. */
internal fun parseMediaPlaylist(text: String, playlistUrl: String): Pair<AudioFileRecord, String>? {
    var end = 0L
    var position = 0L
    var address: String? = null
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.startsWith("#EXT-X-BYTERANGE:") -> {
                val parts = line.substringAfter(':').split('@')
                val length = parts[0].toLongOrNull() ?: return@forEach
                val start = parts.getOrNull(1)?.toLongOrNull() ?: position
                position = start + length
                end = maxOf(end, position)
            }
            line.isNotEmpty() && !line.startsWith("#") -> address = address ?: line
        }
    }
    val found = address ?: return null
    val hex = audioFileHex(found.substringBefore('?')) ?: return null
    return AudioFileRecord(hex, end, playlistUrl) to found
}

/**
 * Keeps one variant: the service delivers each in full, so hopping between them would only cache some of each.
 * A variant with stored bytes wins (offline playback depends on it); otherwise the highest bandwidth not above
 * [targetBandwidth], or the lowest one when every variant is above it.
 */
internal fun selectVariant(master: String, targetBandwidth: Long = Long.MAX_VALUE, maximumBandwidth: Long = Long.MAX_VALUE, preference: (variantUri: String, bandwidth: Long) -> Long): String {
    val lines = master.lines()
    val variants = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") && it + 1 < lines.size && !lines[it + 1].startsWith("#") }
    if (variants.isEmpty()) return master
    fun bandwidth(index: Int) = Regex("(?:^|[:,])BANDWIDTH=(\\d+)").find(lines[index])?.groupValues?.get(1)?.toLongOrNull() ?: 0
    fun fit(index: Int) = bandwidth(index).let { if (it <= targetBandwidth) it else -it }
    // A cached high-quality file from another account must not override the current grant.
    val allowed = variants.filter { bandwidth(it) <= maximumBandwidth }
    if (allowed.isEmpty()) throw SpotifyFailure("No audio variant is available for this account.")
    val best = allowed.maxWith(compareBy({ preference(lines[it + 1].trim(), bandwidth(it)) }, { fit(it) }))
    val dropped = variants.filter { it != best }.flatMap { listOf(it, it + 1) }.toSet()
    return lines.filterIndexed { index, _ -> index !in dropped }.joinToString("\n")
}

internal fun variantUris(master: String): List<String> {
    val lines = master.lines()
    return lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") && it + 1 < lines.size }.map { lines[it + 1].trim() }
}

/** Each variant of a master playlist with its declared bandwidth (0 when absent). */
internal fun variantBandwidths(master: String): List<Pair<String, Long>> {
    val lines = master.lines()
    return lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") && it + 1 < lines.size }.map { index ->
        lines[index + 1].trim() to (Regex("(?:^|[:,])BANDWIDTH=(\\d+)").find(lines[index])?.groupValues?.get(1)?.toLongOrNull() ?: 0)
    }
}
