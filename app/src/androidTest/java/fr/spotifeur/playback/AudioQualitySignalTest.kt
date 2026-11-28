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
import fr.spotifeur.data.preferences.SettingsStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Each streaming quality selects its HLS variant, and that variant decodes to sound past the lead-in and after a seek. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AudioQualitySignalTest {
    @Test fun eachQualityPlaysItsVariant(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyAudioStress") == "true")
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext
        val session = SpotifyAccount.require(app)
        val catalog = SpotifyCatalog(SpotifyAccount.transport, session)
        val requested = InstrumentationRegistry.getArguments().getString("spotifyTrackUris")
            ?.split(',')?.filter(String::isNotBlank)
        val saved = (if (requested != null) catalog.hydrate(requested)
            else SpotifyLibrary(SpotifyAccount.transport, session, catalog).load().savedTracks)
            .filter { it.durationMs > 90_000 }
        InstrumentationRegistry.getArguments().getString("spotifyAccountType")?.let { expected ->
            assertEquals("The intended account must be connected", expected,
                spotifyAttribute(SpotifyAccount.transport, session, "type"))
        }
        val store = SettingsStore.get(app)
        val maximum = session.maximumAudioBandwidth()
        val original = store.settings.value
        val activity = ins.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val native = NativeAudioProvider(app)
        val http = SpotifyHlsDataSource.Factory(app, nativeAudio = true)
        var player: ExoPlayer? = null
        suspend fun signal(label: String) {
            val before = PlaybackDiagnostics.nonSilentBuffers.get()
            withTimeout(35_000) {
                while (PlaybackDiagnostics.nonSilentBuffers.get() < before + 20) {
                    val code = withContext(Dispatchers.Main) { player!!.playerError?.errorCodeName }
                    check(code == null) { "Native playback error=$code diagnostic=${PlaybackDiagnostics.lastError}" }
                    delay(100)
                }
            }
            assertTrue("$label: fresh PCM signal", android.os.SystemClock.elapsedRealtime() - PlaybackDiagnostics.lastSignalAt.get() < 2000)
            Log.i("SpotifeurQualityTest", "$label PCM OK")
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
            val used = mutableSetOf<String>()
            for (quality in listOf(AudioQuality.NORMAL, AudioQuality.VERY_HIGH)) {
                store.update { it.copy(streamingQualityWifi = quality.key, streamingQualityMobile = quality.key) }
                val target = minOf(quality.bandwidth(metered = false), maximum)
                // A track with a stored copy plays that copy whatever the setting: take one that has none.
                val track = withContext(Dispatchers.IO) {
                    (if (requested != null) saved else saved.drop(20)).filter { it.id !in used }.take(30).firstOrNull { candidate ->
                        val master = http.manifest(candidate.id)
                        val kept = http.master(candidate.id, master)
                        Regex("BANDWIDTH=(\\d+)").find(kept)?.groupValues?.get(1)?.toLongOrNull() == target
                    }
                } ?: error("No saved track without a stored copy for ${quality.key}")
                used += track.id
                withContext(Dispatchers.Main) {
                    player!!.setMediaSource(HlsMediaSource.Factory(http).setExtractorFactory(NativeHlsExtractor(native))
                        .createMediaSource(MediaItem.fromUri(track.id)))
                    player!!.prepare(); player!!.play()
                }
                signal("${quality.key} start")
                delay(15_000)
                signal("${quality.key} past lead-in")
                val bitrate = withContext(Dispatchers.Main) {
                    player!!.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
                        (0 until group.length).filter(group::isTrackSelected).map { group.getTrackFormat(it) }
                    }.firstOrNull()?.let { maxOf(it.peakBitrate, it.bitrate) }
                }
                Log.i("SpotifeurQualityTest", "${quality.key} variant bitrate=$bitrate")
                assertEquals(target.toInt(), bitrate)
                withContext(Dispatchers.Main) { player!!.seekTo((track.durationMs * 0.6).toLong()) }
                signal("${quality.key} seek")
            }
        } finally {
            store.update { it.copy(streamingQualityWifi = original.streamingQualityWifi, streamingQualityMobile = original.streamingQualityMobile) }
            withContext(Dispatchers.Main) { player?.release(); activity.finish() }
            native.close(); http.close()
        }
    }
}
