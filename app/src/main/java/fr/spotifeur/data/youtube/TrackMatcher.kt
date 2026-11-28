package fr.spotifeur.data.youtube

import android.content.Context
import androidx.core.content.edit
import fr.spotifeur.data.Music
import java.text.Normalizer

/** What is known of a track that has to be found on YouTube Music. */
data class MatchQuery(val key: String, val title: String, val artists: String, val durationMs: Int, val album: String = "")

/**
 * Finds the YouTube Music recording of a track known by title, artists and duration: a Spotify track
 * played without a Spotify account. Matches are kept on the device, so a track is looked up once.
 */
object TrackMatcher {
    private var app: Context? = null
    fun init(context: Context) { if (app == null) app = context.applicationContext }
    private val preferences get() = app?.getSharedPreferences("youtube_matches", Context.MODE_PRIVATE)

    fun known(key: String): String? = preferences?.getString(key, null)

    /** Blocking: searches the songs and the videos shelves, then keeps the best scored candidate. */
    fun resolve(query: MatchQuery): String? {
        known(query.key)?.let { return it }
        val text = "${query.title.searchTitle()} ${primaryArtist(query.artists)}".trim()
        val songs = runCatching { YouTubeMusic.searchSongs(text) }.getOrDefault(emptyList())
        val best = bestMatch(query, songs, emptyList())
        val chosen = if (best != null && best.second >= CONFIDENT) best else {
            val videos = runCatching { YouTubeMusic.searchVideos(text) }.getOrDefault(emptyList())
            bestMatch(query, songs, videos)
        }
        val videoId = chosen?.first?.youTubeVideoId ?: return null
        preferences?.edit { putString(query.key, videoId) }
        return videoId
    }

    fun forget(key: String) = preferences?.edit { remove(key) }

    internal fun searchText(query: MatchQuery): String = "${plainTitle(query.title).searchTitle()} ${primaryArtist(query.artists)}".trim()

    /**
     * The title as letters a search engine reads: decorated titles ("L⃘E͚G̊R…", enclosed or stacked
     * marks, braille blanks, phonetic small capitals) become their plain letters.
     */
    internal fun plainTitle(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFKC)
        .replace(ALL_MARKS, "").replace(SYMBOLS, " ")
        .split(' ').filter { token -> !(token.length == 1 && token[0].code in 0x250..0x2FF) }
        .joinToString(" ").replace(SPACES, " ").trim().ifEmpty { title.trim() }

    /** Classical movements are titled "Work: movement": the work and its performers find the recording when the movement's name differs. */
    internal fun workSearchText(query: MatchQuery): String? {
        val title = plainTitle(query.title)
        if (':' !in title) return null
        val names = query.artists.split(",").map(String::trim).filter(String::isNotEmpty)
        val performers = (if (names.size > 1) names.drop(1) else names).joinToString(" ", transform = ::searchable)
        return "${title.substringBefore(':').replace(PUNCTUATION, " ")} $performers".replace(SPACES, " ").trim()
    }

    /** Same title and artist, and not a cover, karaoke or another version: another master of the same song at most. */
    internal fun sameSong(query: MatchQuery, candidate: Music): Boolean =
        // The channel itself must be the artist: re-uploads name the artist in their title.
        titleSimilarity(query.title, candidate.title) >= SAME_TITLE && artistSimilarity(query.artists, candidate.subtitle, "") >= SAME_ARTIST &&
            !isOtherVersion(query, candidate)

    /** A title close enough and the artist named: a candidate worth playing when the audio could not be compared. */
    internal fun plausible(query: MatchQuery, candidate: Music): Boolean =
        titleSimilarity(query.title, candidate.title) >= PLAUSIBLE_TITLE && artistSimilarity(query.artists, candidate.subtitle, candidate.title) >= SAME_ARTIST &&
            !isOtherVersion(query, candidate)

    private fun isOtherVersion(query: MatchQuery, candidate: Music): Boolean {
        val wanted = normalize(query.title + " " + query.album)
        val described = normalize(candidate.title + " " + candidate.subtitle)
        return VERSION_WORDS.any { normalize(candidate.title).containsWord(it) && !wanted.containsWord(it) } ||
            COVER_MARKERS.any { described.containsWord(it) && !wanted.containsWord(it) }
    }

    /** A second search for the exact recording: the title with its version and every artist (performers in classical music). */
    internal fun alternateSearchText(query: MatchQuery): String =
        "${plainTitle(query.title).replace(FEATURING, " ").replace(PUNCTUATION, " ")} ${searchable(query.artists.replace(",", " "))}".replace(SPACES, " ").trim()

    /** The title without its version words and the main artist: finds the song when its version is not listed. */
    internal fun coreSearchText(query: MatchQuery): String = "${plainTitle(query.title).coreTitle()} ${primaryArtist(query.artists)}".replace(SPACES, " ").trim()

    /** The title, the main artist and the album: finds the studio recording among live and compilation uploads. */
    internal fun albumSearchText(query: MatchQuery): String? = query.album.takeIf(String::isNotBlank)
        ?.let { "${plainTitle(query.title).coreTitle()} ${primaryArtist(query.artists)} ${it.replace(PARENTHETICALS, " ").replace(ELLIPSIS, " ")}".replace(SPACES, " ").trim() }

    /**
     * Candidates of the songs search, plus the videos search when no song is confident, best score
     * first (songs keep their small advantage). [videosOnly] reads the videos shelf alone. Throws when
     * no search answered.
     */
    internal fun rankedCandidates(query: MatchQuery, text: String = searchText(query), videosOnly: Boolean = false): List<Pair<Music, Double>> {
        val songs = if (videosOnly) Result.failure(java.io.IOException("Videos only.")) else runCatching { YouTubeMusic.searchSongs(text) }
        val scoredSongs = songs.getOrDefault(emptyList()).map { it to score(query, it) + SONG_BONUS }
        val videos = if (!videosOnly && (scoredSongs.maxOfOrNull { it.second } ?: 0.0) >= CONFIDENT) Result.success(emptyList())
            else runCatching { YouTubeMusic.searchVideos(text) }
        if (songs.isFailure && videos.isFailure) throw (if (videosOnly) videos else songs).exceptionOrNull() as? java.io.IOException
            ?: java.io.IOException("YouTube Music search failed.", songs.exceptionOrNull())
        val scored = scoredSongs + videos.getOrDefault(emptyList()).map { it to score(query, it) }
        return scored.filter { it.first.youTubeVideoId != null }.distinctBy { it.first.id }.sortedByDescending { it.second }
    }

    /** The candidate with the highest score, if it reaches [ACCEPTED]. Songs get a small advantage over videos. */
    fun bestMatch(query: MatchQuery, songs: List<Music>, videos: List<Music>): Pair<Music, Double>? =
        (songs.map { it to score(query, it) + SONG_BONUS } + videos.map { it to score(query, it) })
            .filter { it.second >= ACCEPTED }
            .maxByOrNull { it.second }

    /** 0..1: title (45 %), artist (35 %) and duration (20 %), minus versions the original is not. */
    fun score(query: MatchQuery, candidate: Music): Double {
        val title = titleSimilarity(query.title, candidate.title)
        if (title < MIN_TITLE) return 0.0
        val artist = artistSimilarity(query.artists, candidate.subtitle, candidate.title)
        val duration = durationSimilarity(query.durationMs, candidate.durationMs)
        var total = 0.45 * title + 0.35 * artist + 0.20 * duration
        val wanted = normalize(query.title + " " + query.album)
        val found = normalize(candidate.title)
        VERSION_WORDS.forEach { word -> if (found.containsWord(word) && !wanted.containsWord(word)) total -= VERSION_PENALTY }
        // Karaoke, "in the style of" and re-tuned uploads name the original in their title or channel.
        val described = normalize(candidate.title + " " + candidate.subtitle)
        if (COVER_MARKERS.any { described.containsWord(it) && !wanted.containsWord(it) }) total -= COVER_PENALTY
        if (query.durationMs > 0 && candidate.durationMs > 0 && kotlin.math.abs(query.durationMs - candidate.durationMs) > 60_000) total -= 0.2
        // Same album name: most likely the same release of the recording.
        if (query.album.isNotBlank() && normalize(query.album) == normalize(candidate.album)) total += ALBUM_BONUS
        return total.coerceIn(0.0, 1.0)
    }

    internal fun titleSimilarity(wanted: String, found: String): Double {
        val a = normalize(wanted.coreTitle())
        // A title made of punctuation alone ("?") can only be compared as it is written.
        if (a.isEmpty()) return if (wanted.isNotBlank() && wanted.trim().equals(found.trim(), ignoreCase = true)) 1.0 else 0.0
        // A video title often wraps the song title: "Artist - Title (Official Video)".
        val bare = found.replace(PARENTHETICALS, " ")
        val forms = listOf(found.coreTitle(), bare, bare.substringAfter(" - ", "")).map(::normalize).filter(String::isNotEmpty).distinct()
        return forms.maxOfOrNull { b ->
            if (a == b) return 1.0
            val tokens = tokenSimilarity(a, b)
            val edit = 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
            val contained = if (b.containsWord(a)) 0.9 else 0.0
            maxOf(tokens, edit, contained)
        } ?: 0.0
    }

    internal fun artistSimilarity(wanted: String, found: String, foundTitle: String): Double =
        maxOf(artistSimilarityAsWritten(wanted, found, foundTitle), artistSimilarityAsWritten(latin(wanted), latin(found), latin(foundTitle)))

    /** Cyrillic names as Latin letters: "Катя Лель" on one service is "Katya Lel" on the other. */
    internal fun latin(text: String): String {
        if (text.none { it in '\u0400'..'\u04FF' }) return text
        return buildString { text.lowercase().forEach { append(CYRILLIC[it] ?: it.toString()) } }
    }

    /**
     * Several credited artists, all on the candidate, and the same length within three seconds: the same
     * performance, whatever language its title is in (classical movements are titled differently on each service).
     */
    internal fun sameCredits(query: MatchQuery, candidate: Music): Boolean {
        val names = query.artists.split(",").map { normalize(latin(it)) }.filter(String::isNotEmpty)
        if (names.size < 2 || query.durationMs <= 0 || candidate.durationMs <= 0) return false
        if (kotlin.math.abs(query.durationMs - candidate.durationMs) > SAME_LENGTH_MS) return false
        val credited = normalize(latin(candidate.subtitle + " " + candidate.title))
        return names.all { credited.containsWord(it) } && !isOtherVersion(query, candidate)
    }

    /**
     * The recording to play when no candidate could be matched by ear: the same performance by its credits
     * and length (classical music), then a plausible result of the same length (the same edit), then any
     * plausible result; the first search first, then the best score. Null when there is none: the track is
     * not on YouTube Music, and playing another song would be wrong.
     */
    internal fun fallbackChoice(
        query: MatchQuery, first: List<Pair<Music, Double>>, all: Collection<Pair<Music, Double>>,
        playable: (Music) -> Boolean = { true },
    ): Music? {
        fun pick(accept: (Music) -> Boolean): Music? = first.firstOrNull { playable(it.first) && accept(it.first) }?.first
            ?: all.filter { playable(it.first) && accept(it.first) }.maxByOrNull { it.second }?.first
        return pick { sameCredits(query, it) }
            ?: pick { plausible(query, it) && sameLength(query, it) }
            ?: pick { plausible(query, it) }
    }

    /** Same length within three seconds: most likely the same edit. */
    internal fun sameLength(query: MatchQuery, candidate: Music): Boolean =
        query.durationMs > 0 && candidate.durationMs > 0 && kotlin.math.abs(query.durationMs - candidate.durationMs) <= SAME_LENGTH_MS

    private fun artistSimilarityAsWritten(wanted: String, found: String, foundTitle: String): Double {
        val names = wanted.split(",", "&", " feat.", " ft.", " x ").map { normalize(it) }.filter(String::isNotEmpty)
        if (names.isEmpty()) return 0.5
        val channel = normalize(found.replace(CHANNEL_NOISE, " "))
        val haystack = "$channel ${normalize(foundTitle)}"
        val primary = names.first()
        return when {
            channel.isNotEmpty() && (channel == primary || haystack.containsWord(primary) || channel.replace(" ", "") == primary.replace(" ", "")) -> 1.0
            names.any { haystack.containsWord(it) } -> 0.8
            else -> tokenSimilarity(primary, channel) * 0.8
        }
    }

    internal fun durationSimilarity(wanted: Int, found: Int): Double {
        if (wanted <= 0 || found <= 0) return 0.5
        val seconds = kotlin.math.abs(wanted - found) / 1000.0
        return when {
            seconds <= 2 -> 1.0
            seconds >= 30 -> 0.0
            else -> 1.0 - (seconds - 2) / 28
        }
    }

    internal fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(MARKS, "")
        .replace("&", " and ")
        .replace(SEPARATORS, " ")
        .trim()

    /** "Song - Remastered 2011", "Song (feat. X)" and "Song [Live]" all compare as "Song". */
    internal fun String.coreTitle(): String = replace(PARENTHETICALS, " ")
        .replace(DASH_SUFFIX, " ")
        .replace(FEATURING, " ")
        .trim().ifEmpty { this }

    /** The core title, or the whole title when it names a version (live, remix…), so that version is found first. */
    private fun String.searchTitle(): String =
        if (SEARCH_VERSION_WORDS.any { normalize(this).containsWord(it) }) replace(FEATURING, " ").replace(PUNCTUATION, " ").replace(SPACES, " ").trim()
        else coreTitle()
    private fun primaryArtist(artists: String): String = searchable(artists.split(",").firstOrNull().orEmpty())

    /** Search engines read "-word" as "without word" and quotes as exact phrases: the artist "-M-" must not exclude itself. */
    private fun searchable(artist: String): String = artist.replace(SEARCH_OPERATORS, " ").replace(SPACES, " ").trim()

    private fun String.containsWord(word: String): Boolean =
        word.isNotEmpty() && (" $this ").contains(" $word ")

    private fun tokenSimilarity(a: String, b: String): Double {
        val first = a.split(' ').filter(String::isNotEmpty).toSet()
        val second = b.split(' ').filter(String::isNotEmpty).toSet()
        if (first.isEmpty() || second.isEmpty()) return 0.0
        return (first intersect second).size.toDouble() / (first union second).size
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    private val PARENTHETICALS = Regex("""[(\[][^)\]]*[)\]]""")
    // Compiled once: normalize runs for every local file and every candidate.
    private val CHANNEL_NOISE = Regex("(?i)vevo|- topic|official")
    private val MARKS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private val DASH_SUFFIX = Regex("""\s+-\s+.*$""")
    private val FEATURING = Regex("""(?i)\s(feat|ft)\.?\s.*$""")
    private const val ACCEPTED = 0.55
    private const val CONFIDENT = 0.8
    private const val MIN_TITLE = 0.45
    private const val SONG_BONUS = 0.05
    private const val ALBUM_BONUS = 0.05
    private const val VERSION_PENALTY = 0.25
    private val PUNCTUATION = Regex("""[()\[\]\-–—/]""")
    private val SPACES = Regex("""\s+""")
    private val ALL_MARKS = Regex("""\p{M}+""")
    private val SYMBOLS = Regex("""[\p{So}\p{Sk}\u2800-\u28FF]+""")
    private const val SAME_TITLE = 0.8
    private const val SAME_ARTIST = 0.8
    private const val PLAUSIBLE_TITLE = 0.6
    private const val SAME_LENGTH_MS = 3_000
    private val CYRILLIC = mapOf('а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya", 'і' to "i", 'ї' to "yi", 'є' to "ye")
    private val ELLIPSIS = Regex("""\.{2,}|…""")
    private val SEARCH_OPERATORS = Regex("""(?:^|(?<=\s))[-+]+|[-+]+(?=\s|$)|["“”]""")
    private val SEARCH_VERSION_WORDS = listOf("live", "remix", "mix", "acoustic", "extended", "slowed", "sped", "instrumental", "demo", "unplugged", "rework")
    private val COVER_MARKERS = listOf("karaoke", "in the style of", "made famous", "made popular", "originally performed", "sound a like",
        "soundalike", "tribute", "as performed by", "432hz", "432 hz", "vocals only", "isolated vocals", "acapella", "a cappella",
        "backing track", "piano arrangement", "guitar tab", "tabs")
    private const val COVER_PENALTY = 0.35
    private val VERSION_WORDS = listOf(
        "live", "remix", "acoustic", "instrumental", "karaoke", "cover", "sped", "slowed", "nightcore",
        "8d", "reverb", "demo", "lyrics", "reaction", "tutorial", "lesson", "edit", "extended",
    )
}
