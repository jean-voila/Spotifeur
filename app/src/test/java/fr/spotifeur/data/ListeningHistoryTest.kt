package fr.spotifeur.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningHistoryTest {
    private fun track(id: String) = Music(id, id, "", MusicKind.Track)

    @Test fun spotifyRecentPlaysKeepTheLastTrackOfEachContext() {
        val json = """{"playContexts":[
            {"uri":"spotify:album:a","lastPlayedTime":2000,"lastPlayedTrackUri":"spotify:track:t1"},
            {"uri":"spotify:show:s","lastPlayedTime":1500,"lastPlayedTrackUri":"spotify:episode:e"},
            {"uri":"spotify:playlist:p","lastPlayedTime":1000}]}"""
        assertEquals(listOf(SpotifyRecentPlay("spotify:album:a", "spotify:track:t1", 2000)), decodeRecentPlays(json))
        assertEquals(emptyList<SpotifyRecentPlay>(), decodeRecentPlays("{}"))
    }

    @Test fun mergedHistoryIsNewestFirstWithoutTheSamePlayTwice() {
        val local = listOf(HistoryEntry(track("ytm:x"), 9_000_000), HistoryEntry(track("spotify:track:a"), 5_000_000))
        val spotify = listOf(
            HistoryEntry(track("spotify:track:a"), 5_100_000, fromSpotify = true),
            HistoryEntry(track("spotify:track:b"), 7_000_000, fromSpotify = true),
            HistoryEntry(track("spotify:track:a"), 1_000_000, fromSpotify = true),
        )
        val merged = mergeHistory(local, spotify)
        assertEquals(listOf("ytm:x", "spotify:track:b", "spotify:track:a", "spotify:track:a"), merged.map { it.track.id })
        assertEquals(listOf(false, true, false, true), merged.map { it.fromSpotify })
    }

    @Test fun oneListeningSeenTwiceIsShownOnce() {
        val song = Music("spotify:track:a", "a", "", MusicKind.Track, durationMs = 200_000)
        val entries = listOf(
            HistoryEntry(song, 1_000_000 + 400_000), // Played again after it ended: a new play.
            HistoryEntry(song, 1_000_000 + 5_000), // The queue rebuilt a few seconds later: the same play.
            HistoryEntry(song, 1_000_000),
        )
        assertEquals(listOf(1_400_000L, 1_000_000L), collapseRepeats(entries).map { it.playedAt })
        assertEquals(true, isSameListening(entries.last(), song.id, 1_000_000 + 60_000))
        assertEquals(false, isSameListening(entries.last(), "spotify:track:b", 1_000_000 + 60_000))
    }
}
