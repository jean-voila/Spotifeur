package fr.spotifeur.playback

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.youtube.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Decoded signal of YouTube Music playback through the app's own chain: a YouTube Music track, then
 * a Spotify track as it plays without a Spotify account (found by the best match), with seeks and
 * the transition between them. Run with `-e youtubeAudio true`; network required.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class YouTubePlaybackSignalTest {
    @Test fun signalAfterLeadSeeksAndTransition(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("youtubeAudio") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val youtube = Music("ytm:track:fa5IWHDbftI", "One More Time", "Daft Punk", MusicKind.Track, durationMs = 321_000)
        val spotify = Music("spotify:track:3n3Ppam7vgaVa1iaRUc9Lp", "Mr. Brightside", "The Killers", MusicKind.Track,
            durationMs = 222_075, album = "Hot Fuss")
        val tracks = listOf(youtube, spotify)
        val chain = SpotifeurPlaybackService.youTubeCacheChain(app)
        var player: ExoPlayer? = null
        suspend fun signal(label: String) {
            val before = PlaybackDiagnostics.nonSilentBuffers.get()
            withTimeout(40_000) {
                while (PlaybackDiagnostics.nonSilentBuffers.get() < before + 20) {
                    val error = withContext(Dispatchers.Main) { player!!.playerError }
                    check(error == null) { "Playback error=${error?.errorCodeName} cause=${generateSequence<Throwable>(error) { it.cause }.joinToString(">") { it.toString() }}" }
                    delay(100)
                }
            }
            assertTrue("$label: fresh PCM signal", android.os.SystemClock.elapsedRealtime() - PlaybackDiagnostics.lastSignalAt.get() < 2000)
            Log.i("SpotifeurYouTubeTest", "$label PCM OK")
        }
        try {
            withContext(Dispatchers.Main) {
                val renderers = object : DefaultRenderersFactory(app) {
                    override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                        DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(SignalProbe())).build()
                }
                player = ExoPlayer.Builder(app, renderers).build().apply {
                    addAnalyticsListener(PlaybackDiagnostics)
                    val factory = ProgressiveMediaSource.Factory(chain.kept)
                    setMediaSources(tracks.map { track ->
                        val uri = checkNotNull(YouTubeUris.of(track, spotifyThroughYouTube = true))
                        factory.createMediaSource(MediaItem.Builder().setMediaId(track.id).setUri(uri)
                            .setCustomCacheKey(YouTubeUris.cacheKey(track.id)).build())
                    })
                    prepare(); play()
                }
            }
            signal("start youtube")
            delay(20_000)
            signal("continuous youtube")
            for (fraction in listOf(0.2, 0.7, 0.5)) {
                withContext(Dispatchers.Main) { player!!.seekTo((youtube.durationMs * fraction).toLong()) }
                signal("seek youtube/$fraction")
            }
            // Across the transition to the matched Spotify track.
            withContext(Dispatchers.Main) { player!!.seekTo(youtube.durationMs - 6_000L) }
            withTimeout(30_000) { while (withContext(Dispatchers.Main) { player!!.currentMediaItemIndex } != 1) delay(200) }
            signal("after transition")
            delay(20_000)
            signal("continuous match")
            withContext(Dispatchers.Main) { player!!.seekTo(1, (spotify.durationMs * 0.6).toLong()) }
            signal("seek match/0.6")
            assertEquals(Player.STATE_READY, withContext(Dispatchers.Main) { player!!.playbackState })
            // What was heard is kept in the recent cache, under each track's key.
            tracks.forEach { track ->
                val cached = AudioCache.cache(app).getCachedBytes(YouTubeUris.cacheKey(track.id), 0, Long.MAX_VALUE)
                Log.i("SpotifeurYouTubeTest", "${track.id} cached=$cached")
                assertTrue("${track.id} cached", cached > 0)
            }
        } finally {
            withContext(Dispatchers.Main) { player?.release() }
            // Leave the device as it was.
            AudioCache.remove(app, tracks.map(Music::id))
            TrackMatcher.forget(spotify.id)
        }
    }
}
