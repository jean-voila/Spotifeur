package fr.spotifeur.data

import fr.spotifeur.data.preferences.AppSettings
import fr.spotifeur.data.youtube.abbreviatedCount
import fr.spotifeur.playback.TrackLoudness
import fr.spotifeur.utils.matchesWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doubles for the fixes and features of the November 2026 reports. */
class ReportedIssuesTest {
    private val playlist = Music("spotify:playlist:0000000000000000000001", "Old name", "", MusicKind.Playlist)

    @Test
    fun nativePlaylistPageKeepsOrderLocalFilesAndLength() {
        val response = Proto(proto {
            writeByteArray(1, proto { writeInt32(1, 200) })
            writeByteArray(2, proto {
                writeByteArray(1, proto { writeString(7, "row1"); writeString(18, "spotify:track:0000000000000000000002") })
                writeByteArray(1, proto { writeString(7, "row2"); writeString(18, "spotify:local:Artist:Album:Title:200") })
                writeByteArray(1, proto { writeString(7, "row3"); writeString(18, "spotify:episode:0000000000000000000003") })
                writeByteArray(2, proto { writeByteArray(1, proto {
                    writeString(1, playlist.id); writeString(2, "Mix"); writeInt32(6, 354); writeString(7, "Description")
                    writeByteArray(8, proto { writeString(1, "spotify:image:" + "ab".repeat(20)) })
                }) })
            })
        })
        val page = decodeNativePlaylistPage(response, playlist, from = 100)
        assertEquals("Mix", page.item.title)
        assertEquals(354, page.total)
        assertEquals(3, page.rawCount)
        assertEquals(listOf("spotify:track:0000000000000000000002", "spotify:local:Artist:Album:Title:200"), page.entries)
        assertEquals(listOf("spotify:track:0000000000000000000002"), page.uris)
        assertTrue(page.hasMore)
        assertEquals("https://i.scdn.co/image/" + "ab".repeat(20), page.item.imageUrl)
    }

    @Test
    fun onlyRequestRefusalsTryAnotherPlaylistReader() {
        assertTrue(playlistFallbackApplies(SpotifyFailure("Spotify playlist: HTTP response 400.", 400)))
        assertFalse(playlistFallbackApplies(SpotifyFailure("missing", 404)))
        assertFalse(playlistFallbackApplies(SpotifyFailure("private", 403)))
        assertFalse(playlistFallbackApplies(SpotifyAuthRequired("signed out")))
        assertFalse(playlistFallbackApplies(SpotifyRateLimited("later", 1000)))
        assertFalse(playlistFallbackApplies(SpotifyFailure("offline")))
    }

    @Test
    fun metadataStatusesAreSummarisedWithoutIdentifiers() {
        val response = proto {
            writeByteArray(2, proto {
                writeByteArray(1, proto { writeInt32(1, 200) })
                writeInt32(2, 10)
                writeByteArray(3, proto { writeByteArray(1, proto { writeInt32(1, 403) }); writeString(2, "spotify:track:0000000000000000000001") })
                writeByteArray(3, proto { writeByteArray(1, proto { writeInt32(1, 403) }); writeString(2, "spotify:track:0000000000000000000002") })
            })
        }
        val summary = metadataStatusSummary(response)
        assertEquals("kind=10 provider=200 entities={403=2}", summary)
        assertFalse(summary.contains("spotify:"))
    }

    @Test
    fun floatsAndLongVarintsAreRead() {
        val message = Proto(proto { writeFloat(1, -7.25f); writeFloat(2, -0.5f); writeInt64(3, 1_196_939_438L * 3) })
        assertEquals(-7.25f, message.float(1)!!, 0f)
        assertEquals(-0.5f, message.float(2)!!, 0f)
        assertEquals(3_590_818_314L, message.long(3))
    }

    @Test
    fun normalizationLowersLoudTracksAndKeepsQuietOnesUnchanged() {
        // -8 LUFS with a -0.3 dBTP peak: 6 dB down.
        assertEquals(-6f, TrackLoudness.spotifyGainDb(-8f, -0.3f)!!, 0.001f)
        assertEquals(0.501f, TrackLoudness.linear(-6f), 0.001f)
        // -20 LUFS would need +6 dB, limited by its -2 dBTP peak to +1 dB; volume cannot raise it.
        assertEquals(1f, TrackLoudness.spotifyGainDb(-20f, -2f)!!, 0.001f)
        assertEquals(1f, TrackLoudness.linear(1f), 0f)
        assertNull(TrackLoudness.spotifyGainDb(Float.NaN, -1f))
        assertEquals(1f, TrackLoudness.linear(null), 0f)
    }

    @Test
    fun playlistSearchMatchesTitleArtistOrAlbumWithoutAccents() {
        assertTrue(matchesWords("beyonce halo", "Halo", "Beyoncé", "I Am... Sasha Fierce"))
        assertTrue(matchesWords("SASHA", "Halo", "Beyoncé", "I Am... Sasha Fierce"))
        assertFalse(matchesWords("halo adele", "Halo", "Beyoncé", ""))
        assertTrue(matchesWords("  ", "anything"))
    }

    @Test
    fun youTubeCountsAreExpanded() {
        assertEquals(28_300_000L, abbreviatedCount("28.3M monthly audience"))
        assertEquals(150_000L, abbreviatedCount("150K"))
        assertEquals(1_200_000_000L, abbreviatedCount("1.2B plays"))
        assertNull(abbreviatedCount("none"))
    }

    @Test
    fun newSettingsSurviveABackupAndUnknownSwipeValuesFallBack() {
        val settings = AppSettings(audioNormalization = true, showPlayerMenuButton = false, miniPlayerSwipe = AppSettings.MINI_SWIPE_OFF, showSearchSources = true)
        assertEquals(settings, AppSettings.fromJson(settings.toJson()))
        val defaults = AppSettings()
        assertFalse(defaults.audioNormalization)
        assertTrue(defaults.showPlayerMenuButton)
        assertEquals(AppSettings.MINI_SWIPE_TRACKS, defaults.miniPlayerSwipe)
        assertFalse(defaults.showSearchSources)
        val odd = settings.toJson().put("miniPlayerSwipe", "sideways")
        assertEquals(AppSettings.MINI_SWIPE_TRACKS, AppSettings.fromJson(odd).miniPlayerSwipe)
    }

    @Test
    fun artistProfileIsStoredWithThePage() {
        val artist = Music("spotify:artist:0000000000000000000009", "Band", "Artist", MusicKind.Artist)
        val album = Music("spotify:album:0000000000000000000008", "Record", "Band", MusicKind.Album)
        val profile = ArtistProfile(
            monthlyListeners = 24_738_114, worldRank = 7, playCounts = mapOf("spotify:track:0000000000000000000001" to 1_196_939_438L),
            popularReleases = listOf(album.id), albums = listOf(album.id), biography = "Text", headerImageUrl = null,
        )
        val detail = CatalogDetail(artist, emptyList(), listOf(album), artist = profile)
        val decoded = decodeDetail(encodeDetail(detail))!!
        assertEquals(profile, decoded.artist)
        assertNull(decodeDetail(encodeDetail(detail.copy(artist = null)))!!.artist)
    }

    @Test
    fun mixedSearchPutsTheClosestNameFirstAndShowsATrackOnce() {
        val spotifyArtist = Music("spotify:artist:0000000000000000000001", "Daft Punk", "Artist", MusicKind.Artist)
        val spotifyTrack = Music("spotify:track:0000000000000000000002", "One More Time", "Daft Punk", MusicKind.Track)
        val youTubeSame = Music("ytm:track:abc", "One More Time", "Daft Punk", MusicKind.Track)
        val youTubeAlbum = Music("ytm:album:MPREb_x", "Daft Punk Live", "Daft Punk", MusicKind.Album)
        val device = Music("local:track:1", "Da Funk", "Daft Punk", MusicKind.Track)
        val merged = SearchRanking.merge("daft punk", listOf(
            SearchRanking.Source(listOf(spotifyTrack, spotifyArtist)),
            SearchRanking.Source(listOf(youTubeSame, youTubeAlbum), 0.97),
            SearchRanking.Source(listOf(device), 1.02, deduplicated = false),
        ))
        assertEquals(spotifyArtist, merged.first())
        assertTrue(spotifyTrack in merged)
        assertFalse(youTubeSame in merged)
        assertTrue(youTubeAlbum in merged && device in merged)
        assertEquals(merged.size, merged.distinctBy(Music::id).size)
    }
}
