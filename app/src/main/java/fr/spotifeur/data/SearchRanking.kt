package fr.spotifeur.data

import fr.spotifeur.utils.foldForSearch

/**
 * Orders results of several catalogues in one list, as the official search orders its own: how
 * closely the name matches what was typed first, then the rank each service gave the result.
 * A track, album or artist found on Spotify and on YouTube Music is shown once, at the better rank of
 * the two, as the version of the source listed first (Spotify): its page has the account's actions
 * and Spotify's figures. Device files are always kept.
 */
object SearchRanking {
    class Source(val items: List<Music>, val weight: Double = 1.0, val deduplicated: Boolean = true)

    fun merge(query: String, sources: List<Source>): List<Music> {
        val scored = sources.flatMapIndexed { order, source ->
            source.items.mapIndexed { index, item -> Scored(item, score(query, item, index) * source.weight, order, source.deduplicated) }
        }
        // Per identity: the best rank any source gave it, and the version of the first source that has it.
        val best = HashMap<String, Double>()
        val chosen = HashMap<String, Scored>()
        scored.filter { it.deduplicated }.forEach { entry ->
            val key = identity(entry.item)
            best.merge(key, entry.score, ::maxOf)
            val current = chosen[key]
            if (current == null || entry.order < current.order || (entry.order == current.order && entry.score > current.score)) chosen[key] = entry
        }
        val kept = scored.filter { !it.deduplicated } + chosen.values.map { it.copy(score = best.getValue(identity(it.item))) }
        return kept.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.order })
            .map { it.item }
            .distinctBy(Music::id)
    }

    private data class Scored(val item: Music, val score: Double, val order: Int, val deduplicated: Boolean)

    internal fun score(query: String, item: Music, index: Int): Double = relevance(query, item) * 0.65 + 0.35 / (1 + index * 0.15)

    /** 1 for the exact name, then a start, every word in the name, every word in name and artists, some words. */
    internal fun relevance(query: String, item: Music): Double {
        val wanted = query.foldForSearch().trim().replace(Regex("\\s+"), " ")
        if (wanted.isEmpty()) return 0.0
        val title = item.title.foldForSearch()
        val words = wanted.split(' ')
        val everything = "$title ${item.subtitle.foldForSearch()} ${item.album.foldForSearch()}"
        val base = when {
            title == wanted -> 1.0
            title.startsWith(wanted) -> 0.9
            words.all(title::contains) -> 0.8
            words.all(everything::contains) -> 0.75
            else -> 0.4 * words.count(everything::contains) / words.size
        }
        // Typing a name usually means that artist.
        return if (item.kind == MusicKind.Artist && title == wanted) base + 0.1 else base
    }

    private fun identity(item: Music): String {
        val artist = item.subtitle.split(',', '&').firstOrNull().orEmpty().foldForSearch().trim()
        val title = item.title.foldForSearch().replace(Regex("\\s*[(\\[].*$"), "").trim()
        return "${item.kind}|$title|${if (item.kind == MusicKind.Artist) "" else artist}"
    }
}
