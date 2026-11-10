package fr.spotifeur.data

import fr.spotifeur.data.youtube.MatchQuery
import fr.spotifeur.data.youtube.TrackMatcher
import fr.spotifeur.data.youtube.YTM_PLAYLIST_PREFIX
import fr.spotifeur.data.youtube.YTM_TRACK_PREFIX
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** The three kinds of playlists: on the Spotify account, on the Google account, or multi-service on the device. */
enum class PlaylistKind(val key: String) {
    Spotify("spotify"), YouTubeMusic("youtube"), MultiService("multi");

    /** Whether [track] goes as it is into a playlist of this kind. */
    fun accepts(track: Music): Boolean = when (this) {
        MultiService -> true
        Spotify -> track.id.startsWith("spotify:track:") || track.id.startsWith(SPOTIFY_LOCAL_PREFIX)
        YouTubeMusic -> track.id.startsWith(YTM_TRACK_PREFIX)
    }

    companion object {
        fun fromKey(key: String): PlaylistKind? = entries.firstOrNull { it.key == key }
    }
}

fun playlistKindOf(item: Music): PlaylistKind? = when {
    item.kind != MusicKind.Playlist -> null
    item.id.startsWith(LOCAL_PLAYLIST_PREFIX) -> PlaylistKind.MultiService
    item.id.startsWith(YTM_PLAYLIST_PREFIX) -> PlaylistKind.YouTubeMusic
    item.id.startsWith("spotify:playlist:") || item.id == "spotify:collection:tracks" -> PlaylistKind.Spotify
    else -> null
}

enum class MatchState {
    /** Being looked up on the destination. */
    Searching,
    /** Already from the destination: kept as it is. */
    Same,
    /** A close match. */
    Found,
    /** A match that may be another recording: shown for review. */
    Uncertain,
    /** Nothing close enough: left out unless a track is chosen. */
    Missing,
}

/** One track of the source playlist, with the track it becomes in the new playlist. */
data class ConversionRow(
    val position: Int,
    val original: Music,
    val state: MatchState = MatchState.Searching,
    val choice: Music? = null,
    val score: Double = 0.0,
    val candidates: List<Music> = emptyList(),
    val removed: Boolean = false,
    /** Chosen or confirmed by the user. */
    val reviewed: Boolean = false,
    /** The search failed (network): the user can search by hand. */
    val failed: Boolean = false,
) {
    val included: Boolean get() = !removed && choice != null && state != MatchState.Searching
    val needsReview: Boolean get() = !removed && !reviewed && (state == MatchState.Uncertain || state == MatchState.Missing)
}

data class ConversionUi(
    val source: Music,
    val target: PlaylistKind,
    val name: String,
    val reading: Boolean = true,
    val rows: List<ConversionRow> = emptyList(),
    /** The source playlist could not be read. */
    val loadError: String? = null,
    /** The new playlist could not be created. */
    val error: String? = null,
    val creating: Boolean = false,
    val created: Music? = null,
) {
    val searched: Int get() = rows.count { it.state != MatchState.Searching }
    val finished: Boolean get() = !reading && loadError == null && rows.none { it.state == MatchState.Searching }
    val tracks: List<Music> get() = rows.filter { it.included }.mapNotNull { it.choice }
    val toReview: Int get() = rows.count { it.needsReview }
}

/** Candidates for a track on the destination service. */
fun interface MatchSearch {
    suspend fun candidates(track: Music): List<Music>
}

/** Scores candidates as the YouTube Music matching of Spotify tracks does: title, artists, duration, version words. */
object ConversionMatching {
    const val CONFIDENT = 0.8
    const val ACCEPTED = 0.55

    fun query(track: Music) = MatchQuery(track.id, songTitle(track), track.subtitle, track.durationMs, track.album)

    /**
     * The song title of a YouTube video title: "Artist - Title (Official Video)", "Title | Film | Cast",
     * "Artist 'Title' Official MV", "Title / Титул". Other titles are returned as they are.
     */
    fun songTitle(track: Music): String {
        if (!track.id.startsWith(YTM_TRACK_PREFIX)) return track.title
        var title = track.title.substringBefore(" | ").trim()
        QUOTED.find(title)?.let { quoted ->
            val inside = quoted.groupValues.drop(1).first(String::isNotEmpty)
            if (mentionsArtist(title.replace(quoted.value, " "), track.subtitle)) return inside.trim()
        }
        val dash = title.indexOf(" - ")
        if (dash > 0 && mentionsArtist(title.substring(0, dash), track.subtitle)) title = title.substring(dash + 3)
        return title.substringBefore(" / ").trim().ifEmpty { track.title }
    }

    private val QUOTED = Regex("""[‘“]([^’”]{2,})[’”]|(?:^|\s)'(.{2,}?)'(?=\s|$)|(?:^|\s)"(.{2,}?)"(?=\s|$)""")

    private fun mentionsArtist(text: String, artists: String): Boolean {
        val haystack = " ${TrackMatcher.normalize(text)} "
        return artists.split(",", "&", " x ", " feat.", " ft.").map(TrackMatcher::normalize).filter(String::isNotEmpty)
            .any { haystack.contains(" $it ") }
    }

    fun rank(track: Music, candidates: List<Music>): List<Pair<Music, Double>> {
        val query = query(track)
        return candidates.filter { it.kind == MusicKind.Track }.distinctBy(Music::id)
            .map { it to TrackMatcher.score(query, it) }
            .sortedByDescending { it.second }
    }

    /** "Title Artist", without featuring and version suffixes. */
    fun searchText(track: Music): String {
        val title = with(TrackMatcher) { songTitle(track).coreTitle() }
        val artist = track.subtitle.split(",", "&", " feat.", " ft.").firstOrNull()?.trim().orEmpty()
        return "$title $artist".trim()
    }

    fun stateOf(score: Double): MatchState = when {
        score >= CONFIDENT -> MatchState.Found
        score >= ACCEPTED -> MatchState.Uncertain
        else -> MatchState.Missing
    }
}

/**
 * Converts a playlist to another kind: tracks already from the destination stay, the others are
 * looked up a few at a time, and each row can be changed or left out before the playlist is created.
 */
class PlaylistConversion(
    private val scope: CoroutineScope,
    val source: Music,
    val target: PlaylistKind,
    private val loadTracks: suspend () -> List<Music>,
    private val search: MatchSearch,
    /** What a track becomes when nothing matches, if it can still go in (a device file in a Spotify playlist). */
    private val fallback: (Music) -> Music? = { null },
    private val parallelism: Int = 3,
    private val searchAttempts: Int = 3,
    private val retryBaseMs: Long = 1_500,
) {
    private val mutable = MutableStateFlow(ConversionUi(source, target, source.title))
    val state: StateFlow<ConversionUi> = mutable.asStateFlow()
    private var job: Job? = null
    private var createJob: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            val tracks = try { loadTracks() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutable.update { it.copy(reading = false, loadError = failure.message.orEmpty()) }
                return@launch
            }
            val rows = tracks.mapIndexed { index, track ->
                if (target.accepts(track)) ConversionRow(index, track, MatchState.Same, choice = track, score = 1.0)
                else ConversionRow(index, track)
            }
            mutable.update { it.copy(reading = false, rows = rows) }
            // A track that appears several times is looked up once.
            val lookups = ConcurrentHashMap<String, Deferred<List<Pair<Music, Double>>?>>()
            val gate = Semaphore(parallelism)
            coroutineScope {
                rows.filter { it.state == MatchState.Searching }.forEach { row ->
                    launch {
                        val lookup = lookups.computeIfAbsent(row.original.id) {
                            async(start = CoroutineStart.LAZY) { gate.withPermit { lookup(row.original) } }
                        }
                        settle(row.position, lookup.await())
                    }
                }
            }
            if (failures.get() > 0) fr.spotifeur.data.diagnostics.DiagnosticLog.record("convert",
                "target=${target.key} searches failed=${failures.get()} of ${lookups.size}")
        }
    }

    private val failures = java.util.concurrent.atomic.AtomicInteger()

    /**
     * A long playlist makes thousands of searches, and the service answers some with errors or rate
     * limits. Each search is tried three times, waiting longer each time, before the row asks for a choice.
     */
    private suspend fun lookup(track: Music): List<Pair<Music, Double>>? {
        repeat(searchAttempts) { attempt ->
            try { return ConversionMatching.rank(track, search.candidates(track)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (attempt == searchAttempts - 1) {
                    if (failures.incrementAndGet() <= 3) fr.spotifeur.data.diagnostics.DiagnosticLog.record("convert",
                        "search failed ${failure.javaClass.simpleName} \"${failure.message?.take(80)}\"")
                    return null
                }
                kotlinx.coroutines.delay(retryDelayMs(failure, attempt))
            }
        }
        return null
    }

    private fun retryDelayMs(failure: Exception, attempt: Int): Long {
        val limited = (failure as? java.io.IOException)?.message?.let { "429" in it || "limiting" in it } == true
        return (if (limited) retryBaseMs * 7 else retryBaseMs) * (attempt + 1)
    }

    private fun settle(position: Int, ranked: List<Pair<Music, Double>>?) = change(position) { row ->
        val best = ranked?.firstOrNull()
        val kept = fallback(row.original)
        val candidates = ranked.orEmpty().map { it.first }.take(MAX_CANDIDATES) + listOfNotNull(kept)
        when {
            best != null && best.second >= ConversionMatching.ACCEPTED ->
                row.copy(state = ConversionMatching.stateOf(best.second), choice = best.first, score = best.second, candidates = candidates)
            kept != null -> row.copy(state = MatchState.Uncertain, choice = kept, score = 0.0, candidates = candidates, failed = ranked == null)
            else -> row.copy(state = MatchState.Missing, choice = null, score = best?.second ?: 0.0, candidates = candidates, failed = ranked == null)
        }
    }

    fun rename(name: String) = mutable.update { it.copy(name = name.take(150)) }

    /** The user's pick for a row; it then counts as reviewed. */
    fun choose(position: Int, track: Music) = change(position) { row ->
        val score = ConversionMatching.rank(row.original, listOf(track)).firstOrNull()?.second ?: 0.0
        row.copy(choice = track, state = if (track.id == row.original.id) MatchState.Same else MatchState.Found,
            score = score, reviewed = true, removed = false,
            candidates = (listOf(track) + row.candidates).distinctBy(Music::id))
    }

    /** Keeps an uncertain match as it is. */
    fun confirm(position: Int) = change(position) { it.copy(reviewed = it.choice != null, removed = false) }

    fun remove(position: Int) = change(position) { it.copy(removed = true) }
    fun restore(position: Int) = change(position) { it.copy(removed = false) }

    /** Creates the playlist with the rows kept, in their order. */
    fun create(creator: suspend (name: String, tracks: List<Music>) -> Music, failed: (Exception) -> String) {
        val current = mutable.value
        if (!current.finished || current.creating || current.created != null || createJob?.isActive == true) return
        createJob = scope.launch {
            mutable.update { it.copy(creating = true) }
            try {
                val created = creator(current.name.trim().ifEmpty { current.source.title }, mutable.value.tracks)
                mutable.update { it.copy(creating = false, created = created) }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(creating = false) }
                throw cancelled
            } catch (failure: Exception) {
                mutable.update { it.copy(creating = false, error = failed(failure)) }
            }
        }
    }

    fun dismissError() = mutable.update { it.copy(error = null) }

    /** Stops the lookups. A playlist being created is still created. */
    fun cancel() { job?.cancel() }

    private fun change(position: Int, update: (ConversionRow) -> ConversionRow) = mutable.update { ui ->
        ui.copy(rows = ui.rows.map { if (it.position == position) update(it) else it })
    }

    private companion object {
        const val MAX_CANDIDATES = 8
    }
}
