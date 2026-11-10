package fr.spotifeur.data

import kotlin.random.Random

/*
 * Smart shuffle without paid placement.
 *
 * Recommendations come from catalog metadata only: an artist's related artists (`Artist.related`,
 * field 15) and its releases. They never come from Radio, Autoplay, Mixes or the playlist
 * recommendation service: on those surfaces, Discovery Mode boosts the tracks whose artist or label
 * accepts a lower royalty rate, which is promoted content.
 *
 * - Taste: an artist qualifies when at least two of the context's main artists list it as related.
 * - Fairness: every qualifying artist gets one slot. Being cited more often lets an artist in; it
 *   does not make it play more, nor play first.
 * - Depth: the track comes from a random album or single of the artist, not from its top tracks.
 */

private const val SEED_ARTISTS = 8
private const val RECOMMENDATIONS = 10
private const val MIN_GAP = 3

/**
 * Tracks to mix into a shuffled [context], at most one per artist. [excluded] holds the IDs never to
 * recommend, such as the context itself and the saved tracks. [metadata] is [SpotifyCatalog.metadata]:
 * each step asks for all its entities at once, so a search costs five batched requests at most, fewer
 * when the context's tracks are already cached.
 */
internal suspend fun smartShuffleTracks(
    context: List<Music>,
    excluded: Set<String>,
    random: Random = Random.Default,
    metadata: suspend (List<String>) -> Map<String, ByteArray>,
): List<Music> {
    // The artist lines that come back most often stand for the context's taste. Grouping costs no
    // request; one track per line then names its main artist.
    val lines = context.filter { it.kind == MusicKind.Track && it.source == MusicSource.Spotify }
        .groupBy(Music::subtitle).values.sortedByDescending(List<Music>::size).take(SEED_ARTISTS)
    if (lines.isEmpty()) return emptyList()
    val seedTracks = metadata(lines.map { it.first().id })
    val weights = HashMap<String, Int>()
    for (line in lines) {
        val track = seedTracks[line.first().id]?.let(::Proto) ?: continue
        val artist = gidUri("artist", track.messages(4).firstOrNull()?.bytes(1)) ?: continue
        weights.merge(artist, line.size, Int::plus)
    }
    val seeds = metadata(weights.keys.toList())
    val related = weights.keys.associateWith { id ->
        seeds[id]?.let { Proto(it).messages(15).mapNotNull(::decodeMetadataArtist) }.orEmpty()
    }

    val artists = rankRelatedArtists(weights, related).take(RECOMMENDATIONS).map(Music::id)
    val pages = metadata(artists)
    // Albums and singles (fields 5 and 6) are the artist's own; compilations and guest appearances
    // (7 and 8) are left out. Top tracks (field 4) only serve when no release has a new track.
    val choices = artists.mapNotNull { id ->
        val page = pages[id]?.let(::Proto) ?: return@mapNotNull null
        val releases = (5..6).flatMap(page::messages).flatMap { it.messages(1) }.mapNotNull { gidUri("album", it.bytes(1)) }
        val top = page.messages(4).flatMap { it.messages(2) }.mapNotNull { gidUri("track", it.bytes(1)) }
        releases.randomOrNull(random) to top
    }
    val albums = metadata(choices.mapNotNull { it.first })
    val picks = choices.mapNotNull { (release, top) ->
        val onRelease = release?.let(albums::get)?.let { album ->
            Proto(album).messages(11).flatMap { it.messages(3) }.mapNotNull { gidUri("track", it.bytes(1)) }
        }.orEmpty()
        onRelease.filter { it !in excluded }.ifEmpty { top.filter { it !in excluded } }.randomOrNull(random)
    }.distinct()
    val tracks = metadata(picks)
    return picks.mapNotNull { uri -> tracks[uri]?.let { decodeMetadataTrack(Proto(it)) } }.shuffled(random)
}

/**
 * Artists related to the seed artists, the best cited first, seed artists left out. Only artists
 * cited by at least two seeds are kept, unless no artist is.
 */
internal fun rankRelatedArtists(weights: Map<String, Int>, related: Map<String, List<Music>>): List<Music> {
    val ranked = related.flatMap { (seed, artists) ->
        artists.distinctBy(Music::id).filter { it.id !in weights }.map { it to (weights[seed] ?: 0) }
    }.groupBy { it.first.id }.values.sortedByDescending { citations -> citations.sumOf { it.second } }
    return ranked.filter { it.size >= 2 }.ifEmpty { ranked }.map { it.first().first }
}

/** Puts one recommendation after every few tracks, at least [MIN_GAP] apart, across the whole [queue]. */
internal fun spreadRecommendations(queue: List<Music>, picks: List<Music>): List<Music> {
    if (picks.isEmpty()) return queue
    val gap = maxOf(MIN_GAP, queue.size / picks.size)
    return queue.flatMapIndexed { index, track ->
        listOfNotNull(track, picks.getOrNull(index / gap)?.takeIf { (index + 1) % gap == 0 })
    }
}
