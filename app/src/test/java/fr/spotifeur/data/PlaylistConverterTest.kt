package fr.spotifeur.data

import fr.spotifeur.data.youtube.YTM_PLAYLIST_PREFIX
import fr.spotifeur.data.youtube.YTM_TRACK_PREFIX
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The converter with a fake destination search: states, choices, removals and the created track list. */
class PlaylistConverterTest {
    private fun spotify(id: String, title: String, artist: String, seconds: Int) =
        Music("spotify:track:$id", title, artist, MusicKind.Track, durationMs = seconds * 1000)
    private fun ytm(id: String, title: String, artist: String, seconds: Int) =
        Music("$YTM_TRACK_PREFIX$id", title, artist, MusicKind.Track, durationMs = seconds * 1000)
    private fun file(id: Int, title: String, artist: String, seconds: Int) =
        Music("$LOCAL_TRACK_PREFIX$id", title, artist, MusicKind.Track, durationMs = seconds * 1000)

    @Test fun kindsAndWhatTheyAccept() {
        assertEquals(PlaylistKind.Spotify, playlistKindOf(Music("spotify:playlist:x", "", "", MusicKind.Playlist)))
        assertEquals(PlaylistKind.Spotify, playlistKindOf(Music("spotify:collection:tracks", "", "", MusicKind.Playlist)))
        assertEquals(PlaylistKind.YouTubeMusic, playlistKindOf(Music("${YTM_PLAYLIST_PREFIX}PLx", "", "", MusicKind.Playlist)))
        assertEquals(PlaylistKind.MultiService, playlistKindOf(Music("${LOCAL_PLAYLIST_PREFIX}x", "", "", MusicKind.Playlist)))
        assertNull(playlistKindOf(Music("spotify:album:x", "", "", MusicKind.Album)))
        val track = spotify("a", "A", "B", 100)
        assertTrue(PlaylistKind.Spotify.accepts(track))
        assertTrue(PlaylistKind.Spotify.accepts(Music("spotify:local:Artist:Album:Title:200", "Title", "Artist", MusicKind.Track)))
        assertFalse(PlaylistKind.YouTubeMusic.accepts(track))
        assertTrue(PlaylistKind.MultiService.accepts(file(1, "x", "y", 1)))
        assertEquals(PlaylistKind.YouTubeMusic, PlaylistKind.fromKey("youtube"))
    }

    @Test fun searchTextDropsVersionsAndFeaturing() {
        assertEquals("Get Lucky Daft Punk", ConversionMatching.searchText(spotify("a", "Get Lucky (feat. Pharrell Williams) - Radio Edit", "Daft Punk, Pharrell Williams", 248)))
    }

    @Test fun videoTitlesGiveTheSongTitle() {
        fun title(text: String, channel: String) = ConversionMatching.songTitle(ytm("v", text, channel, 200))
        assertEquals("Peshta", title("Xamdam Sobirov - Peshta / Хамдам Собиров - Пешта", "Xamdam Sobirov"))
        assertEquals("체포하겠어 (You're Under Arrest)", title("ILLIT (아일릿) '체포하겠어 (You're Under Arrest)' Official MV", "ILLIT"))
        assertEquals("Ala Bolelo - Lyric Video", title("Ala Bolelo - Lyric Video | Jailer 2 | Superstar Rajinikanth", "Sun TV"))
        assertEquals("Sweet Child O' Mine (Official Music Video)", title("Guns N' Roses - Sweet Child O' Mine (Official Music Video)", "Guns N' Roses"))
        assertEquals("Don't Stop Me Now", title("Don't Stop Me Now", "Queen"))
        // A Spotify title is never cut.
        assertEquals("Song - Live", ConversionMatching.songTitle(spotify("s", "Song - Live", "Artist", 100)))
        assertEquals("Peshta Xamdam Sobirov", ConversionMatching.searchText(ytm("v", "Xamdam Sobirov - Peshta (Official Video)", "Xamdam Sobirov", 200)))
    }

    private fun convert(
        tracks: List<Music>, target: PlaylistKind, results: Map<String, List<Music>>,
        fallback: (Music) -> Music? = { null }, block: suspend (PlaylistConversion) -> Unit,
    ) = runBlocking {
        val searched = mutableListOf<String>()
        val conversion = PlaylistConversion(this, Music("${LOCAL_PLAYLIST_PREFIX}src", "Source", "", MusicKind.Playlist), target,
            loadTracks = { tracks },
            search = { track -> synchronized(searched) { searched += track.id }; results[track.id] ?: throw IOException("offline") },
            fallback = fallback, searchAttempts = 1)
        conversion.start()
        withTimeout(5_000) { conversion.state.first { it.finished } }
        block(conversion)
        // A track that appears twice is looked up once.
        assertEquals(searched.distinct().size, searched.size)
    }

    @Test fun aFailedSearchIsTriedAgain() = runBlocking {
        val track = spotify("1", "One More Time", "Daft Punk", 320)
        var calls = 0
        val conversion = PlaylistConversion(this, Music("${LOCAL_PLAYLIST_PREFIX}src", "Source", "", MusicKind.Playlist), PlaylistKind.YouTubeMusic,
            loadTracks = { listOf(track) },
            search = { if (++calls < 3) throw IOException("YouTube Music answered 429.") else listOf(ytm("orig", "One More Time", "Daft Punk", 321)) },
            retryBaseMs = 1)
        conversion.start()
        withTimeout(5_000) { conversion.state.first { it.finished } }
        assertEquals(3, calls)
        assertEquals(MatchState.Found, conversion.state.value.rows.single().state)
        assertFalse(conversion.state.value.rows.single().failed)
    }

    @Test fun matchesAreRankedAndClassified() {
        val exact = spotify("1", "One More Time", "Daft Punk", 320)
        val doubtful = spotify("2", "Harder Better Faster Stronger", "Daft Punk", 224)
        val nothing = spotify("3", "Unreleased Demo", "Nobody", 200)
        val offline = spotify("4", "Veridis Quo", "Daft Punk", 345)
        val already = ytm("keep", "Around the World", "Daft Punk", 429)
        val results = mapOf(
            exact.id to listOf(ytm("cover", "One More Time (Piano Cover)", "Pianist", 300), ytm("orig", "One More Time", "Daft Punk", 321)),
            doubtful.id to listOf(ytm("live", "Harder Better Faster Stronger (Alive 2007)", "Daft Punk", 330)),
            nothing.id to listOf(ytm("other", "Something Else", "Somebody", 100)),
        )
        convert(listOf(exact, already, doubtful, nothing, offline, exact), PlaylistKind.YouTubeMusic, results) { conversion ->
            val rows = conversion.state.value.rows
            assertEquals(MatchState.Found, rows[0].state)
            assertEquals("${YTM_TRACK_PREFIX}orig", rows[0].choice?.id)
            assertEquals(MatchState.Same, rows[1].state)
            assertEquals(already, rows[1].choice)
            assertEquals(MatchState.Uncertain, rows[2].state)
            assertTrue(rows[2].needsReview)
            assertEquals(MatchState.Missing, rows[3].state)
            assertNull(rows[3].choice)
            assertFalse(rows[3].failed)
            assertTrue(rows[4].failed)
            assertEquals(MatchState.Missing, rows[4].state)
            assertEquals(rows[0].choice, rows[5].choice)
            assertEquals(3, conversion.state.value.toReview)
            // Kept rows, in the source order; missing ones are left out.
            assertEquals(listOf("${YTM_TRACK_PREFIX}orig", "${YTM_TRACK_PREFIX}keep", "${YTM_TRACK_PREFIX}live", "${YTM_TRACK_PREFIX}orig"),
                conversion.state.value.tracks.map(Music::id))
        }
    }

    @Test fun userChoicesRemovalsAndCreation() {
        val first = spotify("1", "Song", "Artist", 200)
        val second = spotify("2", "Other", "Artist", 200)
        val manual = ytm("manual", "Other (Remastered)", "Artist", 201)
        val results = mapOf(first.id to listOf(ytm("one", "Song", "Artist", 200)), second.id to emptyList())
        convert(listOf(first, second), PlaylistKind.YouTubeMusic, results) { conversion ->
            assertEquals(MatchState.Missing, conversion.state.value.rows[1].state)
            conversion.choose(1, manual)
            val chosen = conversion.state.value.rows[1]
            assertEquals(manual, chosen.choice)
            assertTrue(chosen.reviewed)
            assertFalse(chosen.needsReview)
            conversion.remove(0)
            assertEquals(listOf(manual.id), conversion.state.value.tracks.map(Music::id))
            conversion.restore(0)
            conversion.rename("  Copy  ")
            var received: Pair<String, List<Music>>? = null
            conversion.create(creator = { name, tracks -> received = name to tracks; Music("${YTM_PLAYLIST_PREFIX}new", name, "", MusicKind.Playlist) },
                failed = { "failed" })
            withTimeout(5_000) { conversion.state.first { it.created != null } }
            assertEquals("Copy", received?.first)
            assertEquals(listOf("${YTM_TRACK_PREFIX}one", manual.id), received?.second?.map(Music::id))
        }
    }

    @Test fun deviceFilesStayAsLocalReferencesInSpotify() {
        val local = file(7, "Home Recording", "Me", 90)
        val known = spotify("k", "Known", "Band", 180)
        convert(listOf(local, known), PlaylistKind.Spotify, mapOf(local.id to emptyList()), fallback = { it.takeIf { t -> t.id.startsWith(LOCAL_TRACK_PREFIX) } }) { conversion ->
            val row = conversion.state.value.rows[0]
            assertEquals(MatchState.Uncertain, row.state)
            assertEquals(local, row.choice)
            assertEquals(MatchState.Same, conversion.state.value.rows[1].state)
            assertEquals(2, conversion.state.value.tracks.size)
        }
    }

    @Test fun everythingGoesAsItIsIntoAMultiServicePlaylist() {
        val tracks = listOf(spotify("1", "A", "B", 1), ytm("y", "C", "D", 2), file(3, "E", "F", 3))
        convert(tracks, PlaylistKind.MultiService, emptyMap()) { conversion ->
            assertTrue(conversion.state.value.rows.all { it.state == MatchState.Same })
            assertEquals(tracks, conversion.state.value.tracks)
            assertEquals(0, conversion.state.value.toReview)
        }
    }

    @Test fun creationFailureIsShownAndCanBeRetried() {
        val track = ytm("y", "C", "D", 2)
        convert(listOf(track), PlaylistKind.YouTubeMusic, emptyMap()) { conversion ->
            conversion.create(creator = { _, _ -> throw IOException("refused") }, failed = { "Could not create: ${it.message}" })
            withTimeout(5_000) { conversion.state.first { it.error != null } }
            assertEquals("Could not create: refused", conversion.state.value.error)
            assertTrue(conversion.state.value.finished)
            conversion.dismissError()
            assertNull(conversion.state.value.error)
        }
    }
}
