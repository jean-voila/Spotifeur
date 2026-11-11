package fr.spotifeur.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyCatalogTest {
    private val first = "spotify:track:0000000000000000000001"
    private val second = "spotify:track:0000000000000000000002"

    @Test
    fun albumMetadataUsesZigzagNumbersAndInheritsArtworkForDiscTracks() {
        val albumBytes = proto {
            writeByteArray(1, gid(3))
            writeString(2, "Discovery")
            writeByteArray(3, proto { writeByteArray(1, gid(4)); writeString(2, "Daft Punk") })
            writeByteArray(6, proto { writeSInt32(1, 2001) })
            writeByteArray(17, proto { writeByteArray(1, proto {
                writeByteArray(1, ByteArray(20) { 0x1a }); writeSInt32(3, 640)
            }) })
            writeByteArray(11, proto { writeByteArray(3, proto {
                writeByteArray(1, gid(1)); writeString(2, "One More Time"); writeSInt32(7, 320_357)
            }) })
        }
        val source = Proto(albumBytes)
        val album = decodeMetadataAlbum(source)!!
        val track = decodeMetadataTrack(source.messages(11).single().messages(3).single(), album)!!
        assertEquals("2001", album.note)
        assertEquals(listOf(first), album.tracks)
        assertEquals(320_357, track.durationMs)
        assertEquals("Daft Punk", track.subtitle)
        assertEquals("https://i.scdn.co/image/" + "1a".repeat(20), track.imageUrl)
    }

    @Test
    fun extensionBatchPreservesEntityIdentityAndIgnoresProviderFailures() {
        val track = proto { writeByteArray(1, gid(1)); writeString(2, "Real title") }
        val response = proto {
            writeByteArray(2, proto {
                writeEnum(2, 10)
                writeByteArray(3, proto {
                    writeByteArray(1, proto { writeInt32(1, 200) })
                    writeString(2, first)
                    writeByteArray(3, proto { writeString(1, "type.spotify.com/spotify.metadata.Track"); writeByteArray(2, track) })
                })
                writeByteArray(3, proto {
                    writeByteArray(1, proto { writeInt32(1, 404) })
                    writeString(2, second)
                    writeByteArray(3, proto { writeByteArray(2, track) })
                })
            })
        }
        val entries = decodeMetadataExtensions(response)
        assertEquals(setOf(first), entries.keys)
        assertEquals("Real title", Proto(entries.getValue(first)).text(2))
    }

    @Test
    fun playlistFiltersPodcastsWithoutLosingRawPaginationPositionOrTrackDuplicates() {
        val fallback = Music("spotify:playlist:0000000000000000000003", "Playlist", "", MusicKind.Playlist)
        val response = proto {
            writeInt32(2, 151)
            writeByteArray(3, proto { writeString(1, "Mes morceaux"); writeString(2, "Description") })
            writeByteArray(5, proto {
                writeInt32(1, 100)
                writeBool(2, true)
                listOf(first, "spotify:episode:0000000000000000000004", first, second).forEach {
                    writeByteArray(3, proto { writeString(1, it) })
                }
            })
        }
        val page = decodePlaylistPage(response, fallback)
        assertEquals("Mes morceaux", page.item.title)
        assertEquals(listOf(first, first, second), page.uris)
        assertEquals(4, page.rawCount)
        assertEquals(151, page.total)
        assertTrue(page.hasMore)
    }

    @Test
    fun artworkAndIdentifiersRejectExternalAndUnsupportedInputs() {
        assertNull(canonicalMusicUri("spotify:episode:0000000000000000000001"))
        assertNull(canonicalMusicUri("spotify:track:../../outside"))
        assertNull(spotifyArtwork("https://example.com/image"))
        assertNull(spotifyArtwork("https://secret@i.scdn.co/image/a"))
        assertEquals("spotify:playlist:0000000000000000000001", canonicalMusicUri("spotify:user:owner:playlist:0000000000000000000001"))
        assertFalse(spotifyArtwork("http://i.scdn.co/image/a") != null)
    }

    @Test
    fun nativeMetadataKeepsRequestedUrisAndSkipsErrors() {
        val track = proto { writeByteArray(1, gid(1)); writeString(2, "One More Time") }
        val album = proto { writeByteArray(1, gid(2)); writeString(2, "Discovery") }
        val requested = listOf(gidUri("track", gid(9))!!, gidUri("album", gid(2))!!, gidUri("track", gid(3))!!)
        val response = Proto(proto {
            // A relinked track comes back with another gid: it is kept under the URI that was asked for.
            writeByteArray(1, proto { writeByteArray(4, track) })
            writeByteArray(1, proto { writeByteArray(3, album) })
            writeByteArray(1, proto { writeSInt32(1, 404) })
        })
        val decoded = decodeNativeMetadata(response, requested)
        assertEquals(setOf(requested[0], requested[1]), decoded.keys)
        assertEquals("One More Time", decodeMetadataTrack(Proto(decoded.getValue(requested[0])))?.title)
        assertEquals(listOf("spotify:track:x", "spotify:album:y").joinToString(), nativeMetadataRequest(listOf("spotify:track:x", "spotify:album:y"))
            .let { bytes -> Proto(bytes).texts(1).joinToString() })
    }

    private fun gid(number: Int) = ByteArray(16).also { it[15] = number.toByte() }
}
