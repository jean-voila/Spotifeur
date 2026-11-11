package fr.spotifeur.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.data.youtube.YouTubeAccount
import fr.spotifeur.data.youtube.YouTubeLibrary
import fr.spotifeur.data.youtube.YouTubeMusic
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read-only check of the Google account signed in to the app: the account menu and the library
 * playlists answer through the app's own requests. Run with `-e youtubeAccount true`.
 */
@RunWith(AndroidJUnit4::class)
class YouTubeAccountProbeTest {
    @Test fun accountAndLibrary() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("youtubeAccount") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        YouTubeMusic.init(app)
        YouTubeAccount.init(app)
        val credential = YouTubeAccount.current()
        assumeTrue("No Google account in the app", credential != null)
        assertNotNull(YouTubeLibrary.accountInfo(credential!!))
        Log.i(TAG, "library playlists=${YouTubeLibrary.libraryPlaylists().size}")
    }

    private companion object { const val TAG = "SpotifeurYouTubeProbe" }
}
