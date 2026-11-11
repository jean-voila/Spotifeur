package fr.spotifeur.data

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.playback.SpotifyNativeRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Read-only: track, album and artist metadata through the native engine's ClassicMetadataService. Run with `-e spotifyNetwork true`. */
@RunWith(AndroidJUnit4::class)
class NativeMetadataProbeTest {
    @Test fun multigetEntity(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyNetwork") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val session = SpotifyAccount.require(app)
        val found = SpotifySearch(SpotifyAccount.transport, session).search("Daft Punk", Locale.FRANCE, null).items
        val uris = found.filter { it.kind == MusicKind.Track }.take(5).map(Music::id) +
            found.filter { it.kind != MusicKind.Track && it.kind != MusicKind.Playlist }.take(2).map(Music::id)
        SpotifyNativeRuntime.open(app).use { runtime ->
            val started = SystemClock.elapsedRealtime()
            val decoded = nativeMetadata(runtime, uris)
            Log.i(TAG, "multiget ${uris.size} uris -> ${decoded.size} in ${SystemClock.elapsedRealtime() - started} ms: " +
                decoded.values.joinToString { "${it.kind}:${it.title.take(12)}:${it.imageUrl != null}:${it.durationMs}" })
            assertTrue(decoded.size >= uris.size - 1)
        }
    }

    private companion object { const val TAG = "SpotifeurNativeMetadata" }
}
