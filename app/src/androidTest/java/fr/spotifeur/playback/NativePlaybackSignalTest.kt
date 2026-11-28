package fr.spotifeur.playback

import android.content.Intent
import android.util.Log
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.audio.*
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.MainActivity
import fr.spotifeur.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class NativePlaybackSignalTest {
    @Test fun standardQualityRefusalStopsAutomaticRetry(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("spotifyRefusalDiagnostic") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val session = SpotifyAccount.require(app)
        val transport = SpotifyAccount.transport
        val attributes = spotifyAttributes(transport, session)
        assertEquals("free", attributes["type"])
        assertEquals("false", attributes["offline"])
        val uri = checkNotNull(args.getString("spotifyTrackUris")).substringBefore(',')
        val track = SpotifyCatalog(transport, session).hydrate(listOf(uri)).single()
        // Skibidisong mode would move the track to YouTube Music: this test covers the engine's own refusal.
        val settings = fr.spotifeur.data.preferences.SettingsStore.get(app)
        val skibidisong = settings.settings.value.skibidisong
        settings.update(commit = true) { it.copy(skibidisong = false) }
        withTimeout(5_000) { while (fr.spotifeur.data.skibidisong.Skibidisong.isActive) delay(20) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val player = withContext(Dispatchers.Main) { SpotifyPlayer(app, scope) }
        try {
            val before = PlaybackDiagnostics.nonSilentBuffers.get()
            withContext(Dispatchers.Main) { player.play(listOf(track)) }
            val refusal = withTimeout(35_000) { player.state.first { it.error != null } }
            assertTrue("The clear lead-in produced PCM", PlaybackDiagnostics.nonSilentBuffers.get() > before)
            assertTrue("The player exposes the native refusal: ${refusal.error}", refusal.spotifyPlaybackRefused)
            assertEquals(app.getString(fr.spotifeur.R.string.spotify_playback_refused), refusal.error)
            val stopped = PlaybackDiagnostics.nonSilentBuffers.get()
            delay(6_000)
            assertTrue("The refusal remains visible", player.state.value.spotifyPlaybackRefused)
            assertEquals("The clear lead-in did not restart", stopped, PlaybackDiagnostics.nonSilentBuffers.get())
        } finally {
            withContext(Dispatchers.Main) { player.stop(); player.close() }
            scope.cancel()
            settings.update(commit = true) { it.copy(skibidisong = skibidisong) }
        }
    }

    @Test fun signalBeyondClearLeadAndAfterSeeks(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyAudioStress") == "true")
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext
        val session = SpotifyAccount.require(app)
        val catalog = SpotifyCatalog(SpotifyAccount.transport, session)
        val requested = InstrumentationRegistry.getArguments().getString("spotifyTrackUris")
            ?.split(',')?.filter(String::isNotBlank)
        // A new Free account need not have any saved tracks. Explicit fixtures avoid writes to its library.
        val tracks = (if (requested != null) catalog.hydrate(requested)
            else SpotifyLibrary(SpotifyAccount.transport, session, catalog).load().savedTracks)
            .filter { it.durationMs > 90_000 }.take(2)
        val args = InstrumentationRegistry.getArguments()
        val expectedType = args.getString("spotifyAccountType")
        val expectedOffline = args.getString("spotifyOfflineEntitlement")
        if (expectedType != null || expectedOffline != null) {
            val attributes = spotifyAttributes(SpotifyAccount.transport, session)
            expectedType?.let { assertEquals("The intended account must be connected", it, attributes["type"]) }
            expectedOffline?.let {
                assertEquals("The offline entitlement must come from the real account", it, attributes["offline"])
                assertNull("Do not override the account attributes for this test", args.getString("spotifyAttributeOverrides"))
            }
            Log.i("SpotifeurNativeTest", "Account ${attributeSummary(attributes)}")
        }
        assertEquals(2, tracks.size)
        args.getString("spotifyAttributeOverrides")?.let { overrides ->
            SpotifyNativeRuntime.attributeOverridesForTest = overrides.split(';').filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        }
        val activity = ins.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val native = NativeAudioProvider(app)
        val http = SpotifyHlsDataSource.Factory(app, nativeAudio = true)
        var player: ExoPlayer? = null
        suspend fun signal(label: String) {
            delay(500) // Flush output from the previous seek or track before measuring.
            val before = PlaybackDiagnostics.nonSilentBuffers.get()
            withTimeout(35_000) {
                while (PlaybackDiagnostics.nonSilentBuffers.get() < before + 20) {
                    val code = withContext(Dispatchers.Main) { player!!.playerError?.errorCodeName }
                    check(code == null) { "Native playback error=$code diagnostic=${PlaybackDiagnostics.lastError}" }
                    delay(100)
                }
            }
            assertTrue("$label: fresh PCM signal", android.os.SystemClock.elapsedRealtime() - PlaybackDiagnostics.lastSignalAt.get() < 2000)
            Log.i("SpotifeurNativeTest", "$label PCM OK encrypted=${PlaybackDiagnostics.encryptedSamples.get()}")
        }
        try {
            withContext(Dispatchers.Main) {
                val renderers = object : DefaultRenderersFactory(app) {
                    override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory = native.codecFactory
                    override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                        DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(SignalProbe())).build()
                }
                player = ExoPlayer.Builder(app, renderers).build().apply {
                    volume = 0f
                    addAnalyticsListener(PlaybackDiagnostics)
                }
            }
            for ((index, track) in tracks.withIndex()) {
                withContext(Dispatchers.Main) {
                    player!!.setMediaSource(HlsMediaSource.Factory(http).setExtractorFactory(NativeHlsExtractor(native))
                        .createMediaSource(MediaItem.fromUri(track.id)))
                    player!!.prepare(); player!!.play()
                }
                signal("start $index")
                delay(20_000)
                signal("continuous $index")
                for (fraction in listOf(0.2, 0.7, 0.5)) {
                    withContext(Dispatchers.Main) { player!!.seekTo((track.durationMs * fraction).toLong()) }
                    signal("seek $index/$fraction")
                }
            }
            // Exercise an automatic transition too, including the encrypted part of the next file.
            withContext(Dispatchers.Main) {
                player!!.setMediaSources(tracks.map { track ->
                    HlsMediaSource.Factory(http).setExtractorFactory(NativeHlsExtractor(native))
                        .createMediaSource(MediaItem.fromUri(track.id))
                })
                player!!.prepare()
                player!!.seekTo(0, tracks.first().durationMs - 5_000L)
                player!!.play()
            }
            signal("before transition")
            withTimeout(35_000) {
                while (withContext(Dispatchers.Main) { player!!.currentMediaItemIndex } != 1) delay(100)
            }
            delay(20_000)
            signal("automatic transition past lead-in")
        } catch (error: Throwable) {
            Log.e("SpotifeurNativeTest", "Playback failure=" + generateSequence(error) { it.cause }.take(12).joinToString(">") {
                it.javaClass.simpleName + if (it is NativeAudioException) ":${it.nativeCode ?: 0}" else ""
            })
            throw error
        } finally {
            withContext(Dispatchers.Main) { player?.release(); activity.finish() }
            native.close(); http.close()
        }
    }
}
