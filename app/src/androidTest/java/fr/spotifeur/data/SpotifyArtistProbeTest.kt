package fr.spotifeur.data

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Read-only: what the artist page services answer for one artist (monthly listeners from each
 * source, timings). Run with `-e spotifyArtist true` (optionally `-e artistQuery Name`).
 */
@RunWith(AndroidJUnit4::class)
class SpotifyArtistProbeTest {
    @Test fun artistSources(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("spotifyArtist") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val session = SpotifyAccount.require(app)
        val transport = SpotifyAccount.transport
        val query = args.getString("artistQuery") ?: "Wailtoad"
        val artist = SpotifySearch(transport, session).search(query, Locale.FRANCE, null).items.first { it.kind == MusicKind.Artist }
        val id = artist.id.substringAfterLast(':')
        Log.i(TAG, "artist ${artist.title}")
        val body = proto {
            writeByteArray(2, proto { writeString(1, artist.id); listOf(185).forEach { kind -> writeByteArray(2, proto { writeEnum(1, kind) }) } })
        }
        val response = Proto(authenticatedSpotifyRequest(transport, session, spotifyUrl("extended-metadata/v0/extended-metadata"), "probe", body))
        response.messages(2).forEach { array ->
            array.messages(3).forEach { data ->
                val payload = data.message(3)?.bytes(2)
                val p = payload?.let(::Proto)
                Log.i(TAG, "kind=${array.number(2)} status=${data.message(1)?.number(1)} f1=${p?.long(1)} f2=${p?.long(2)} f3=${p?.long(3)} len=${payload?.size}")
            }
        }
        for (fields in listOf("artist,listenerCount,monthlyListenerRank,isVerified", null)) {
            val url = spotifyUrl("creatorabout/v0/artist/$id/about").newBuilder().apply { if (fields != null) addQueryParameter("fields", fields) }.build()
            val json = runCatching { org.json.JSONObject(authenticatedSpotifyRequest(transport, session, url, "probe", contentType = "application/json").decodeToString()) }
            json.onSuccess { j ->
                Log.i(TAG, "about fields=$fields keys=${j.keys().asSequence().toList()} monthlyListeners=${j.opt("monthlyListeners")} listenerCount=${j.opt("listenerCount")} rank=${j.opt("monthlyListenerRank")}")
            }.onFailure { Log.i(TAG, "about fields=$fields failed $it") }
        }
        val catalog = SpotifyCatalog(transport, session)
        val started = SystemClock.elapsedRealtime()
        val detail = catalog.detail(artist)
        Log.i(TAG, "detail ${SystemClock.elapsedRealtime() - started} ms tracks=${detail.tracks.size} covers=${detail.tracks.count { it.imageUrl != null }} listeners=${detail.artist?.monthlyListeners} related=${detail.related.size}")
    }

    private companion object { const val TAG = "SpotifeurArtistProbe" }
}
