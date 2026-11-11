package fr.spotifeur.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One play: a track and when it started. [fromSpotify] marks plays reported by Spotify for its other apps and devices. */
data class HistoryEntry(val track: Music, val playedAt: Long, val fromSpotify: Boolean = false)

/**
 * What was played in Spotifeur, whatever the source, most recent first. Stored on the device only,
 * one JSON line per play: recording a play appends one line instead of rewriting the history, and
 * the file is read on the first use, off the main thread.
 */
class ListeningHistoryStore(context: Context) {
    private val directory = context.applicationContext.filesDir
    private val file = File(directory, "listening-history.jsonl")
    private val legacy = File(directory, "listening-history.json")
    private val mutable = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = mutable.asStateFlow()
    private var loaded = false
    private var lines = 0

    /** Reads the file once. Call it off the main thread; every other method calls it first. */
    @Synchronized fun load() {
        if (loaded) return
        loaded = true
        migrateLegacy()
        val read = runCatching { file.readLines() }.getOrDefault(emptyList()).filter(String::isNotBlank)
        lines = read.size
        val stored = read.asReversed().asSequence().take(LIMIT).mapNotNull(::decodeEntry).toList()
        mutable.value = (mutable.value + stored).take(LIMIT)
        if (lines > LIMIT + COMPACT_SLACK) compact()
    }

    @Synchronized fun record(track: Music, at: Long = System.currentTimeMillis()) {
        load()
        // The same listening seen again (queue rebuilt, shuffle, app restarted while playing) is not a new play.
        mutable.value.firstOrNull()?.let { newest -> if (isSameListening(newest, track.id, at)) return }
        val entry = HistoryEntry(track.copy(mediaUri = if (track.source == MusicSource.Local) track.mediaUri else null), at)
        mutable.value = (listOf(entry) + mutable.value).take(LIMIT)
        runCatching { java.io.FileOutputStream(file, true).use { it.write((encodeEntry(entry) + "\n").toByteArray()) } }
        if (++lines > LIMIT + COMPACT_SLACK) compact()
    }

    @Synchronized fun clear() {
        loaded = true; lines = 0
        mutable.value = emptyList()
        file.delete(); legacy.delete()
    }

    /** Keeps the newest [LIMIT] lines; runs once every [COMPACT_SLACK] plays at most. */
    private fun compact() {
        val kept = mutable.value.asReversed()
        val temporary = File(directory, "listening-history.jsonl.tmp")
        runCatching {
            temporary.writeText(kept.joinToString("") { encodeEntry(it) + "\n" })
            if (temporary.renameTo(file)) lines = kept.size
        }
    }

    /** v1.2.0 and v1.2.1 stored one JSON array. */
    private fun migrateLegacy() {
        if (!legacy.exists()) return
        runCatching {
            val array = JSONArray(legacy.readText())
            val old = (0 until array.length()).mapNotNull { decodeEntry(array.getJSONObject(it).toString()) }
            if (!file.exists()) file.writeText(old.asReversed().joinToString("") { encodeEntry(it) + "\n" })
        }
        legacy.delete()
    }

    private fun encodeEntry(entry: HistoryEntry): String =
        JSONArray(encodePins(listOf(entry.track))).getJSONObject(0).put("playedAt", entry.playedAt).toString()

    private fun decodeEntry(line: String): HistoryEntry? = runCatching {
        val json = JSONObject(line)
        decodePins("[$line]").firstOrNull()?.let { HistoryEntry(it, json.optLong("playedAt")) }
    }.getOrNull()

    private companion object {
        const val LIMIT = 2000
        const val COMPACT_SLACK = 500
    }
}

/** A context Spotify remembers as played, with the last track played in it. */
internal data class SpotifyRecentPlay(val context: String, val track: String, val playedAt: Long)

/** `recently-played/v3`: the albums, playlists and artists played lately on the account, from every device. */
internal suspend fun spotifyRecentPlays(session: SpotifySession, limit: Int = 50): List<SpotifyRecentPlay> {
    val url = spotifyUrl("recently-played/v3/user/${session.username()}/recently-played").newBuilder()
        .addQueryParameter("format", "json").addQueryParameter("offset", "0").addQueryParameter("limit", limit.toString())
        .addQueryParameter("filter", "default,collection-new-episodes").build()
    return decodeRecentPlays(authenticatedSpotifyRequest(SpotifyAccount.transport, session, url, "Listening history", contentType = "application/json").decodeToString())
}

internal fun decodeRecentPlays(json: String): List<SpotifyRecentPlay> {
    val contexts = JSONObject(json).optJSONArray("playContexts") ?: return emptyList()
    return (0 until contexts.length()).mapNotNull { index ->
        val entry = contexts.optJSONObject(index) ?: return@mapNotNull null
        val track = entry.optString("lastPlayedTrackUri").takeIf { it.startsWith("spotify:track:") } ?: return@mapNotNull null
        SpotifyRecentPlay(entry.optString("uri"), track, entry.optLong("lastPlayedTime"))
    }
}

/**
 * Spotifeur's own plays and Spotify's, newest first. Spotify's entry for a track also played in
 * Spotifeur within [window] ms is dropped, so one listening never appears twice.
 */
internal fun mergeHistory(local: List<HistoryEntry>, spotify: List<HistoryEntry>, window: Long = 15 * 60_000L): List<HistoryEntry> {
    val kept = spotify.filter { remote -> local.none { it.track.id == remote.track.id && kotlin.math.abs(it.playedAt - remote.playedAt) < window } }
    return collapseRepeats((local + kept).sortedByDescending(HistoryEntry::playedAt))
}

/**
 * Whether a play of [trackId] starting at [at] is the [newest] entry seen again: same track, started
 * before the newest one could have finished. A track played again after it ended is a new play.
 */
internal fun isSameListening(newest: HistoryEntry, trackId: String, at: Long): Boolean {
    if (newest.track.id != trackId) return false
    val length = newest.track.durationMs.takeIf { it > 0 }?.toLong() ?: DEFAULT_TRACK_MS
    return at - newest.playedAt in 0 until maxOf(length * 9 / 10, MIN_REPLAY_MS)
}

/** Entries, newest first, without the repeats of one listening that earlier versions stored twice. */
internal fun collapseRepeats(entries: List<HistoryEntry>): List<HistoryEntry> {
    val kept = ArrayList<HistoryEntry>(entries.size)
    // Walk oldest first, so the first time a listening was seen is the one kept.
    for (entry in entries.asReversed()) {
        val previous = kept.lastOrNull()
        if (previous != null && isSameListening(previous, entry.track.id, entry.playedAt)) continue
        kept += entry
    }
    return kept.asReversed()
}

private const val DEFAULT_TRACK_MS = 3 * 60_000L
private const val MIN_REPLAY_MS = 30_000L
