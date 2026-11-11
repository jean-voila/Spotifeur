package fr.spotifeur.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicit integration check against Spotify with synthetic, deliberately invalid credentials. */
@RunWith(AndroidJUnit4::class)
class Login5NetworkTest {
    @Test
    fun nativePasswordExchangeReachesCredentialRefusal() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyNetwork") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = SpotifySession(context, SpotifyTransport(), SessionVault(context), UUID.randomUUID().toString())
        try {
            session.connect("spotifeur-protocol-check@example.invalid", "invalid-protocol-test") {
                error("Unexpected code challenge for synthetic credentials")
            }
            fail("Synthetic credentials must be refused")
        } catch (failure: SpotifyFailure) {
            assertEquals(failure.message, 401, failure.status)
        }
    }

    /** A made-up Google code: Login5 must refuse it as a credential (not as an unknown request layout). */
    @Test
    fun googleCredentialReachesCredentialRefusal() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyNetwork") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = SpotifySession(context, SpotifyTransport(), SessionVault(context), UUID.randomUUID().toString())
        try {
            session.connectGoogle("4/0Aspotifeur-protocol-check", "eyJhbGciOiJSUzI1NiJ9.e30.c3BvdGlmZXVy", "https://accounts.spotify.com/login/google/redirect") {
                error("Unexpected code challenge for a synthetic Google code")
            }
            fail("A synthetic Google code must be refused")
        } catch (failure: SpotifyFailure) {
            android.util.Log.i("SpotifeurLogin5Test", "google refusal ${failure.javaClass.simpleName} status=${failure.status} \"${failure.message}\"")
            assertEquals(failure.message, 401, failure.status)
        }
    }
}
