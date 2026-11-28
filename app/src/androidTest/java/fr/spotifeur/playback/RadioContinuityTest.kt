package fr.spotifeur.playback

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class RadioContinuityTest {
    @Test fun switchingToRadioKeepsTheCurrentTrackAndItsSignal(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("radioContinuity") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File.createTempFile("radio-continuity-", ".wav", app.cacheDir)
        writeTone(audio)
        val uri = Uri.fromFile(audio).toString()
        val seed = Music("local:track:radio-seed", "Seed", "", MusicKind.Track, durationMs = 36_000, mediaUri = uri)
        val oldNext = seed.copy(id = "local:track:old-next", title = "Previous queue")
        val radioNext = seed.copy(id = "local:track:radio-next", title = "Radio recommendation")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val player = withContext(Dispatchers.Main) { SpotifyPlayer(app, scope) }
        var previousVolume: Float? = null

        suspend fun signal(track: Music, position: Int): Playback {
            val before = PlaybackDiagnostics.nonSilentBuffers.get()
            return withTimeout(20_000) {
                var playback = player.state.value
                while (playback.track?.id != track.id || playback.positionMs < position ||
                    PlaybackDiagnostics.nonSilentBuffers.get() < before + 10) {
                    check(playback.error == null) { playback.error.orEmpty() }
                    delay(50)
                    playback = player.state.value
                }
                playback
            }
        }

        try {
            withContext(Dispatchers.Main) { player.play(listOf(seed, oldNext)) }
            signal(seed, 200)
            withContext(Dispatchers.Main) {
                previousVolume = player.volume()
                player.setVolume(0f)
                player.seek(22_000)
            }
            val beforeRadio = signal(seed, 22_000)
            withContext(Dispatchers.Main) { player.continueWithRadio(seed.id, listOf(seed, radioNext)) }
            val afterRadio = signal(seed, beforeRadio.positionMs)
            assertTrue("The seed keeps playing at its current position", afterRadio.playing)
            assertEquals(listOf(seed.id, radioNext.id), afterRadio.queue.map(Music::id))
            withContext(Dispatchers.Main) { player.seek(34_000) }
            signal(radioNext, 200)
            withContext(Dispatchers.Main) { player.seek(22_000) }
            signal(radioNext, 22_000)
        } finally {
            withContext(Dispatchers.Main) {
                player.stop()
                previousVolume?.let(player::setVolume)
                player.close()
            }
            scope.cancel()
            audio.delete()
        }
    }

    private fun writeTone(file: File) {
        val sampleRate = 16_000
        val count = 36 * sampleRate
        val bytes = count * Short.SIZE_BYTES
        val wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".encodeToByteArray()).putInt(36 + bytes).put("WAVEfmt ".encodeToByteArray())
        wav.putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
        wav.putShort(2).putShort(16).put("data".encodeToByteArray()).putInt(bytes)
        repeat(count) { sample ->
            wav.putShort((1_800 * sin(2 * PI * 330 * sample / sampleRate)).toInt().toShort())
        }
        file.writeBytes(wav.array())
    }
}
