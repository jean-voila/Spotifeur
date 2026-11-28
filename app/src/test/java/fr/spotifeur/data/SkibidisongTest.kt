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
        // "-M-" would read as "without M" in a search.
        assertEquals("Matchistador Extended M", TrackMatcher.searchText(MatchQuery("k", "Matchistador - Extended", "-M-", 0)))
        assertEquals("Matchistador M", TrackMatcher.coreSearchText(MatchQuery("k", "Matchistador - Extended", "-M-", 0)))
        assertEquals("Empire State of Mind Jay-Z", TrackMatcher.coreSearchText(MatchQuery("k", "Empire State of Mind", "Jay-Z, Alicia Keys", 0)))
        assertEquals("Fool's Overture Supertramp Even in the Quietest Moments",
            TrackMatcher.albumSearchText(MatchQuery("k", "Fool's Overture", "Supertramp", 0, "Even in the Quietest Moments...")))
        assertEquals("No. 4, Largo Frédéric Chopin Alexandre Tharaud",
            TrackMatcher.alternateSearchText(MatchQuery("k", "No. 4, Largo", "Frédéric Chopin, Alexandre Tharaud", 0)))
    }

    @Test fun decoratedTitlesAreSearchedAsPlainLetters() {
        val decorated = "\u0329L\u20d8\u035a\u0325E\u035a\u0329 \u035aG\u0366R\u20d8\u20ddA\u035aN\u20daD\u0325\u2800\u02d9 \u035c\u029f\u02d9\u2800 G\u20dd\u035aA\u035aR\u035aI\u20d8O\u0325"
        assertEquals("LE GRAND GARIO", TrackMatcher.plainTitle(decorated))
        assertEquals("Мой мармеладный (Я не права)", TrackMatcher.plainTitle("Мой мармеладный (Я не права)"))
    }

    @Test fun punctuationTitlesCompareAsWritten() {
        val query = MatchQuery("k", "?", "Jake St Ange", 106_000)
        assertTrue(TrackMatcher.score(query, Music("ytm:track:a", "?", "Jake St Ange", MusicKind.Track, durationMs = 106_000)) > 0.9)
        assertEquals(0.0, TrackMatcher.score(query, Music("ytm:track:b", "robot people", "Jake St Ange", MusicKind.Track)), 0.0)
    }

    @Test fun anotherSongOfTheSameArtistIsNotPlausible() {
        val query = MatchQuery("k", "Sources", "Arlen Grace", 138_000)
        assertFalse(TrackMatcher.plausible(query, Music("ytm:track:a", "office", "Arlen Grace, Gemroz & kolya44", MusicKind.Track)))
        assertFalse(TrackMatcher.plausible(MatchQuery("k", "What falling in love feels like", "D.A", 146_000),
            Music("ytm:track:b", "This is what falling in love feels like, to me.", "ATLeft", MusicKind.Track)))
        assertTrue(TrackMatcher.plausible(MatchQuery("k", "Hung Up", "Madonna", 336_000), Music("ytm:track:c", "Hung Up", "Madonna", MusicKind.Track)))
        // A guitar lesson naming the composer is another version, not the track.
        assertFalse(TrackMatcher.sameSong(MatchQuery("k", "Raindrops Keep Fallin' on My Head - Rerecorded", "B.J. Thomas", 178_000),
            Music("ytm:track:d", "B.J. Thomas - Raindrops Keep Fallin’ on My Head (Vocals Only)", "MetalMassacre", MusicKind.Track)))
        assertTrue(TrackMatcher.sameSong(MatchQuery("k", "Raindrops Keep Fallin' on My Head - Rerecorded", "B.J. Thomas", 178_000),
            Music("ytm:track:e", "Raindrops Keep Fallin' on My Head (Rerecorded)", "B.J. Thomas", MusicKind.Track)))
    }

    @Test fun classicalMovementsAreSearchedByWorkAndPerformers() {
        assertEquals("Peer Gynt Suite No. 1, Op. 46 Berliner Philharmoniker Herbert von Karajan", TrackMatcher.workSearchText(MatchQuery("k",
            "Peer Gynt Suite No. 1, Op. 46: Edvard GRIEG : Peer Gynt. Dans le hall du roi de la montagne - Recorded 1982",
            "Edvard Grieg, Berliner Philharmoniker, Herbert von Karajan", 129_000)))
        assertEquals(null, TrackMatcher.workSearchText(MatchQuery("k", "Hung Up", "Madonna", 0)))
    }

    @Test fun cyrillicAndLatinNamesAreTheSameArtist() {
        val query = MatchQuery("k", "Мой мармеладный (Я не права)", "Katya Lel", 223_000)
        assertTrue(TrackMatcher.sameSong(query, Music("ytm:track:a", "Мой мармеладный", "Катя Лель", MusicKind.Track, durationMs = 224_000)))
    }

    @Test fun classicalPerformanceIsKnownByItsCreditsAndLength() {
        val query = MatchQuery("k", "Peer Gynt Suite No. 1, Op. 46: Edvard GRIEG : Peer Gynt. Dans le hall du roi de la montagne - Recorded 1982",
            "Edvard Grieg, Berliner Philharmoniker, Herbert von Karajan", 129_000)
        assertTrue(TrackMatcher.sameCredits(query, Music("ytm:track:a", "Grieg: Peer Gynt Suite No. 1, Op. 46: IV. In the Hall of the Mountain King",
            "Berliner Philharmoniker, Herbert von Karajan & Edvard Grieg", MusicKind.Track, durationMs = 130_000)))
        assertFalse(TrackMatcher.sameCredits(query, Music("ytm:track:b", "Peer Gynt, Suite No. 1, Op. 46: Dans le château du roi de la montagne",
            "Wiener Philharmoniker, Herbert von Karajan & Edvard Grieg", MusicKind.Track, durationMs = 150_000)))
    }
}
