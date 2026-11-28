package fr.spotifeur.data

import fr.spotifeur.data.skibidisong.Skibidisong
import fr.spotifeur.data.youtube.MatchQuery
import fr.spotifeur.data.youtube.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkibidisongTest {
    @Test fun restrictedFreeAccountIsDetected() {
        // Attributes of the test account whose audio stops after the clear lead-in (2026-11-25).
        assertTrue(Skibidisong.isRestricted(mapOf("type" to "free", "offline" to "false", "ads" to "true", "on-demand" to "false")))
    }

    @Test fun introductoryFreeAndPremiumAccountsAreNotRestricted() {
        assertFalse(Skibidisong.isRestricted(mapOf("type" to "free", "offline" to "true", "ads" to "false", "on-demand" to "true")))
        assertFalse(Skibidisong.isRestricted(mapOf("type" to "premium", "offline" to "true", "on-demand" to "true")))
    }

    @Test fun karaokeAndStyleCopiesRankBelowTheOriginal() {
        val query = MatchQuery("spotify:track:x", "Breakfast In America - Remastered 2010", "Supertramp", 159_000)
        val original = Music("ytm:track:a", "Breakfast In America", "Supertramp", MusicKind.Track, durationMs = 160_000)
        val copy = Music("ytm:track:b", "Breakfast In America (In The Style Of 'Supertramp')", "Zoom Karaoke", MusicKind.Track, durationMs = 159_000)
        val soundAlike = Music("ytm:track:c", "Breakfast In America - Sound-A-Like As Made Famous By: Supertramp", "Studio Group", MusicKind.Track, durationMs = 159_000)
        assertTrue(TrackMatcher.score(query, original) > TrackMatcher.score(query, copy))
        assertTrue(TrackMatcher.score(query, original) > TrackMatcher.score(query, soundAlike))
    }

    @Test fun searchKeepsVersionWordsAndTheSecondSearchNamesEveryArtist() {
        assertEquals("Ditto 250 Remix NewJeans", TrackMatcher.searchText(MatchQuery("k", "Ditto (250 Remix)", "NewJeans", 0)))
        assertEquals("Take Five The Dave Brubeck Quartet", TrackMatcher.searchText(MatchQuery("k", "Take Five", "The Dave Brubeck Quartet", 0)))
        assertEquals("Bohemian Rhapsody Queen", TrackMatcher.searchText(MatchQuery("k", "Bohemian Rhapsody - Remastered 2011", "Queen", 0)))
        assertEquals("No. 4, Largo Frédéric Chopin Alexandre Tharaud",
            TrackMatcher.alternateSearchText(MatchQuery("k", "No. 4, Largo", "Frédéric Chopin, Alexandre Tharaud", 0)))
    }
}
