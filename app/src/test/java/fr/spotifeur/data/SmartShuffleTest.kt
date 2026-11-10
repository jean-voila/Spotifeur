package fr.spotifeur.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SmartShuffleTest {
    private fun artist(name: String) = Music("spotify:artist:$name", name, "Artist", MusicKind.Artist)
    private fun track(id: String, by: String) = Music("spotify:track:$id", id, by, MusicKind.Track)
    private fun titles(items: List<Music>) = items.map(Music::title)

    private fun gid(n: Int) = ByteArray(16).also { it[15] = n.toByte() }
    private fun uri(kind: String, n: Int) = requireNotNull(gidUri(kind, gid(n)))
    private fun ref(n: Int) = proto { writeByteArray(1, gid(n)) }
    private fun trackMeta(n: Int, artist: Int? = null) = proto {
        writeByteArray(1, gid(n)); writeString(2, "t$n")
        if (artist != null) writeByteArray(4, proto { writeByteArray(1, gid(artist)); writeString(2, "a$artist") })
    }
    private fun artistMeta(n: Int, related: List<Int> = emptyList(), albums: List<Int> = emptyList(), compilations: List<Int> = emptyList(), top: List<Int> = emptyList()) = proto {
        writeByteArray(1, gid(n)); writeString(2, "a$n")
        if (top.isNotEmpty()) writeByteArray(4, proto { writeString(1, "FR"); top.forEach { writeByteArray(2, ref(it)) } })
        if (albums.isNotEmpty()) writeByteArray(5, proto { albums.forEach { writeByteArray(1, ref(it)) } })
        if (compilations.isNotEmpty()) writeByteArray(7, proto { compilations.forEach { writeByteArray(1, ref(it)) } })
        related.forEach { writeByteArray(15, proto { writeByteArray(1, gid(it)); writeString(2, "a$it") }) }
    }
    private fun albumMeta(n: Int, tracks: List<Int>) = proto {
        writeByteArray(1, gid(n)); writeString(2, "l$n")
        writeByteArray(11, proto { tracks.forEach { writeByteArray(3, ref(it)) } })
    }

    @Test fun artistsCitedByTwoSeedsComeFirstAndAlone() {
        val weights = mapOf("spotify:artist:A" to 3, "spotify:artist:B" to 2, "spotify:artist:C" to 1)
        val related = mapOf(
            "spotify:artist:A" to listOf(artist("Y"), artist("X"), artist("B")),
            "spotify:artist:B" to listOf(artist("X"), artist("Z")),
            "spotify:artist:C" to listOf(artist("X"), artist("Y"), artist("W")),
        )
        // X: 3 + 2 + 1, Y: 3 + 1. Z and W have one citation, and B is a seed.
        assertEquals(listOf("X", "Y"), titles(rankRelatedArtists(weights, related)))
    }

    @Test fun singleCitationsAreKeptWhenNoArtistIsShared() {
        val weights = mapOf("spotify:artist:A" to 1, "spotify:artist:B" to 5)
        val related = mapOf("spotify:artist:A" to listOf(artist("X")), "spotify:artist:B" to listOf(artist("Y")))
        assertEquals(listOf("Y", "X"), titles(rankRelatedArtists(weights, related)))
    }

    @Test fun recommendationsAreSpreadAndNeverCloserThanThreeTracks() {
        val picks = listOf(track("r1", "R"), track("r2", "R"))
        val short = List(7) { track("q$it", "Q") }
        assertEquals(listOf("q0", "q1", "q2", "r1", "q3", "q4", "q5", "r2", "q6"), titles(spreadRecommendations(short, picks)))
        val long = titles(spreadRecommendations(List(20) { track("q$it", "Q") }, picks))
        assertEquals("q9", long[long.indexOf("r1") - 1])
        assertEquals("q19", long[long.indexOf("r2") - 1])
        assertEquals(short, spreadRecommendations(short, emptyList()))
    }

    @Test fun searchUsesFiveBatchesAndOwnReleasesOnly() = runBlocking {
        // Artists A=10, B=11, C=12 in the context; X=20, Y=21, Z=22 related to them.
        val store = mapOf(
            uri("track", 1) to trackMeta(1, artist = 10), uri("track", 3) to trackMeta(3, artist = 11),
            uri("track", 4) to trackMeta(4, artist = 12),
            // B's page is missing, as when the service leaves an entity out: the other seeds still count.
            uri("artist", 10) to artistMeta(10, related = listOf(20, 21, 11)),
            uri("artist", 12) to artistMeta(12, related = listOf(20, 21, 22)),
            uri("artist", 20) to artistMeta(20, albums = listOf(30), compilations = listOf(31), top = listOf(40)),
            uri("artist", 21) to artistMeta(21, top = listOf(41)),
            uri("album", 30) to albumMeta(30, tracks = listOf(32, 33)),
            uri("track", 32) to trackMeta(32), uri("track", 41) to trackMeta(41),
        )
        val requested = mutableListOf<List<String>>()
        val context = listOf(
            Music(uri("track", 1), "t1", "A", MusicKind.Track), Music(uri("track", 2), "t2", "A", MusicKind.Track),
            Music(uri("track", 3), "t3", "B", MusicKind.Track), Music(uri("track", 4), "t4", "C", MusicKind.Track),
        )
        val picks = smartShuffleTracks(context, setOf(uri("track", 33))) { uris ->
            requested.add(uris)
            uris.mapNotNull { id -> store[id]?.let { id to it } }.toMap()
        }
        // X: its album's track that is not excluded. Y: no release, so a top track. Z: one citation only.
        assertEquals(setOf("t32", "t41"), titles(picks).toSet())
        assertEquals(5, requested.size)
        assertFalse(requested.flatten().contains(uri("album", 31)))
    }
}
