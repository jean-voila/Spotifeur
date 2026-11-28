package fr.spotifeur.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.SpotifyAccount
import fr.spotifeur.data.SpotifyCatalog
import fr.spotifeur.data.skibidisong.MatchMethod
import fr.spotifeur.data.skibidisong.Skibidisong
import fr.spotifeur.data.youtube.MatchQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Skibidisong mode on a restricted Spotify account (playback refused after the clear lead-in), through
 * the app's own player and playback service. Run with `-e skibidisong true` on a restricted account;
 * network required. Tracks: `-e spotifyTrackUris a,b`, otherwise two tracks of the test playlist.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SkibidisongSignalTest {
    private val args get() = InstrumentationRegistry.getArguments()

    private suspend fun restrictedAccount() {
        assumeTrue(args.getString("skibidisong") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Skibidisong.refreshAccount(SpotifyAccount.require(app))
        assertTrue("This test needs a restricted account with Skibidisong on", Skibidisong.isActive)
    }

    /** The audio check keeps the original recording over a cover that the text search ranks first. */
    @Test fun audioCheckRejectsCoverRankedFirst(): Unit = runBlocking {
        restrictedAccount()
        // YouTube Music's songs search ranks a violin cover first for this title (2026-11-25).
        val query = MatchQuery("spotify:track:3INsYP1Y8GG4qJvBsKCdXC", "Don't Stop Me Now - ...Revisited", "Queen", 210_000)
        val match = checkNotNull(Skibidisong.matchAgain(query))
        Log.i(TAG, "cover check → ${match.videoId} ${match.method} ber=${match.errorRate} lag=${match.lagMs}")
        assertEquals(MatchMethod.Audio, match.method)
        assertTrue("error rate ${match.errorRate}", (match.errorRate ?: 1.0) < 0.35)
        assertTrue("not the cover", match.videoId != "CKuswC_sLX8")
    }

    @Test fun signalPastLeadInAfterSeeksAndAcrossTransition(): Unit = runBlocking {
        restrictedAccount()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val uris = args.getString("spotifyTrackUris")?.split(',')?.filter(String::isNotBlank)
            ?: listOf("spotify:track:1YQWosTIljIvxAgHWTp7KP", "spotify:track:3dYD57lRAUcMHufyqn9GcI")
        val tracks: List<Music> = SpotifyCatalog(SpotifyAccount.transport, SpotifyAccount.require(app)).hydrate(uris)
            .filter { it.kind == MusicKind.Track && it.durationMs > 90_000 }.take(2)
        assertEquals(2, tracks.size)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val player = withContext(Dispatchers.Main) { SpotifyPlayer(app, scope) }
        suspend fun signal(label: String) {
            delay(500)
            val before = PlaybackDiagnostics.nonSilentBuffers.get()
            withTimeout(40_000) {
                while (PlaybackDiagnostics.nonSilentBuffers.get() < before + 20) {
                    val state = player.state.value
                    check(state.error == null) { "$label: player error ${state.error}" }
                    delay(100)
                }
            }
            assertTrue("$label: fresh PCM signal", SystemClock.elapsedRealtime() - PlaybackDiagnostics.lastSignalAt.get() < 2000)
            Log.i(TAG, "$label PCM OK at ${player.state.value.positionMs} ms of \"${player.state.value.track?.title}\"")
        }
        try {
            val started = SystemClock.elapsedRealtime()
            val before = PlaybackDiagnostics.nonSilentBuffers.get()
            withContext(Dispatchers.Main) { player.play(tracks, 0); player.setVolume(0f) }
            withTimeout(40_000) { while (PlaybackDiagnostics.nonSilentBuffers.get() <= before) delay(20) }
            Log.i(TAG, "first PCM ${SystemClock.elapsedRealtime() - started} ms after play")
            signal("start")
            // Past the clear lead-in, where a restricted account's own Spotify audio stops.
            delay(20_000)
            signal("continuous")
            assertTrue("position past the lead-in", player.state.value.positionMs > 15_000)
            for (fraction in listOf(0.2, 0.7, 0.5)) {
                withContext(Dispatchers.Main) { player.seek((tracks[0].durationMs * fraction).toInt()) }
                signal("seek $fraction")
            }
            withContext(Dispatchers.Main) { player.seek(tracks[0].durationMs - 6_000) }
            withTimeout(30_000) { while (player.state.value.track?.id != tracks[1].id) delay(100) }
            signal("after transition")
            delay(20_000)
            signal("past the lead-in of the next track")
            assertNull(player.state.value.error)
            tracks.forEach { track -> Log.i(TAG, "${track.title} → ${Skibidisong.matchOf(track.id)}") }
        } finally {
            withContext(Dispatchers.Main) { player.stop(); player.setVolume(1f); player.close() }
            scope.cancel()
        }
    }

    /**
     * An account whose attributes did not announce the restriction: the engine's refusal at the
     * encrypted boundary moves playback to YouTube Music at the same position, without an error.
     */
    @Test fun refusalAtTheEncryptedBoundarySwitchesToYouTubeMusic(): Unit = runBlocking {
        restrictedAccount()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val track = SpotifyCatalog(SpotifyAccount.transport, SpotifyAccount.require(app))
            .hydrate(listOf(args.getString("spotifyRefusalUri") ?: "spotify:track:3dYD57lRAUcMHufyqn9GcI")).single()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val player = withContext(Dispatchers.Main) { SpotifyPlayer(app, scope) }
        try {
            Skibidisong.setRestrictedForTest(false)
            withTimeout(5_000) { while (Skibidisong.isActive) delay(20) }
            withContext(Dispatchers.Main) { player.play(listOf(track), 0); player.setVolume(0f) }
            // The lead-in plays from Spotify, then the engine refuses and the mode turns on.
            withTimeout(40_000) { while (!Skibidisong.isActive) delay(50) }
            val switchedAt = player.state.value.positionMs
            Log.i(TAG, "refusal detected at $switchedAt ms")
            delay(15_000)
            val before = PlaybackDiagnostics.nonSilentBuffers.get()
            delay(1_500)
            assertTrue("PCM after the switch", PlaybackDiagnostics.nonSilentBuffers.get() > before)
            assertNull(player.state.value.error)
            assertTrue("resumed where it stopped: ${player.state.value.positionMs}", player.state.value.positionMs > switchedAt + 10_000)
            Log.i(TAG, "after refusal PCM OK at ${player.state.value.positionMs} ms")
        } finally {
            withContext(Dispatchers.Main) { player.stop(); player.setVolume(1f); player.close() }
            scope.cancel()
            Skibidisong.refreshAccount(SpotifyAccount.require(app))
        }
    }

    /** A track YouTube Music does not have is skipped: the next one plays, with sound past its lead-in. */
    @Test fun missingTrackIsSkipped(): Unit = runBlocking {
        restrictedAccount()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val missing = Skibidisong.matches().firstOrNull { it.method == MatchMethod.Missing }
        assumeTrue("no track marked missing yet", missing != null)
        val tracks = SpotifyCatalog(SpotifyAccount.transport, SpotifyAccount.require(app))
            .hydrate(listOf(missing!!.trackId, "spotify:track:1YQWosTIljIvxAgHWTp7KP"))
        assertEquals(2, tracks.size)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val player = withContext(Dispatchers.Main) { SpotifyPlayer(app, scope) }
        try {
            withContext(Dispatchers.Main) { player.play(tracks, 0); player.setVolume(0f) }
            withTimeout(30_000) { while (player.state.value.track?.id != tracks[1].id) delay(100) }
            Log.i(TAG, "skipped \"${tracks[0].title}\" / ${tracks[0].subtitle}")
            delay(20_000)
            val before = PlaybackDiagnostics.nonSilentBuffers.get()
            delay(1_500)
            assertTrue("PCM on the next track", PlaybackDiagnostics.nonSilentBuffers.get() > before)
            assertTrue("position ${player.state.value.positionMs}", player.state.value.positionMs > 15_000)
        } finally {
            withContext(Dispatchers.Main) { player.stop(); player.setVolume(1f); player.close() }
            scope.cancel()
        }
    }

    /** A download keeps the full YouTube Music recording, which then plays without network. */
    @Test fun downloadKeepsTheYouTubeRecordingAndPlaysOffline(): Unit = runBlocking {
        restrictedAccount()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = args.getString("spotifyDownloadUri") ?: "spotify:track:1YQWosTIljIvxAgHWTp7KP"
        val track = SpotifyCatalog(SpotifyAccount.transport, SpotifyAccount.require(app)).hydrate(listOf(uri)).single()
        withContext(Dispatchers.Main) { DownloadCenter.start(app) }
        assumeTrue("already downloaded on this device", DownloadCenter.downloads.value[track.id] == null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val player = withContext(Dispatchers.Main) { SpotifyPlayer(app, scope) }
        try {
            withContext(Dispatchers.Main) { DownloadCenter.add(app, track) {} }
            withTimeout(120_000) { while (DownloadCenter.downloads.value[track.id]?.isComplete != true) delay(200) }
            val download = checkNotNull(DownloadCenter.manager(app).downloadIndex.getDownload(track.id))
            Log.i(TAG, "download ${download.request.uri} ${download.bytesDownloaded} B, match ${Skibidisong.matchOf(track.id)}")
            assertEquals("ytm", download.request.uri.scheme)
            assertTrue("whole recording", download.bytesDownloaded > 1_000_000)
            // Only the download may answer: drop the copy the recent cache could hold.
            AudioCache.cache(app).removeResource(YouTubeUris.cacheKey(track.id))
            fr.spotifeur.data.NetworkMonitor.setOfflineForTest(true)
            val before = PlaybackDiagnostics.nonSilentBuffers.get()
            withContext(Dispatchers.Main) { player.play(listOf(track), 0); player.setVolume(0f) }
            withTimeout(30_000) { while (PlaybackDiagnostics.nonSilentBuffers.get() < before + 20) delay(100) }
            delay(20_000)
            val later = PlaybackDiagnostics.nonSilentBuffers.get()
            delay(1_500)
            assertTrue("offline PCM past the lead-in", PlaybackDiagnostics.nonSilentBuffers.get() > later)
            assertTrue("position ${player.state.value.positionMs}", player.state.value.positionMs > 15_000)
            assertNull(player.state.value.error)
            Log.i(TAG, "offline PCM OK at ${player.state.value.positionMs} ms")
        } finally {
            fr.spotifeur.data.NetworkMonitor.setOfflineForTest(false)
            withContext(Dispatchers.Main) { player.stop(); player.setVolume(1f); player.close(); DownloadCenter.remove(app, track.id) }
            scope.cancel()
        }
    }

    /** Reads again a sample of the tracks kept by fallback, with every comparison in the log. `-e skibidisongSurvey 15`. */
    @Test fun fallbackSurvey(): Unit = runBlocking {
        val count = args.getString("skibidisongSurvey")?.toIntOrNull()
        assumeTrue(count != null)
        restrictedAccount()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Log.i(TAG, "survey before: ${Skibidisong.summary()}")
        val minimum = args.getString("skibidisongSurveyAbove")?.toDoubleOrNull()
        val fallbacks = Skibidisong.matches().filter {
            if (minimum != null) it.method == MatchMethod.Audio && (it.errorRate ?: 0.0) > minimum else it.method == MatchMethod.Fallback
        }.shuffled().take(count!!)
        val tracks = SpotifyCatalog(SpotifyAccount.transport, SpotifyAccount.require(app)).hydrate(fallbacks.map { it.trackId })
        for (track in tracks) {
            val match = Skibidisong.matchAgain(MatchQuery(track.id, track.title, track.subtitle, track.durationMs, track.album))
            Log.i(TAG, "survey \"${track.title}\" / ${track.subtitle} (${track.durationMs / 1000} s) → ${match?.videoId} ${match?.method} ber=${match?.errorRate}")
        }
        Log.i(TAG, "survey after: ${Skibidisong.summary()}")
    }

    /** Lists the tracks kept without an audio match, without reading them again. `-e skibidisongList true`. */
    @Test fun listFallbacks(): Unit = runBlocking {
        assumeTrue(args.getString("skibidisongList") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val matches = Skibidisong.matches().filter { it.method != MatchMethod.Audio }
        val tracks = SpotifyCatalog(SpotifyAccount.transport, SpotifyAccount.require(app)).hydrate(matches.map { it.trackId })
        tracks.forEach { track -> Log.i(TAG, "list ${Skibidisong.matchOf(track.id)?.method} | ${track.title} | ${track.subtitle} | ${Skibidisong.matchOf(track.id)?.videoId}") }
        Log.i(TAG, "list ${Skibidisong.summary()}")
    }

    /** Matches again the stored tracks whose title contains one of `-e skibidisongTitles a;b`, with every step in the log. */
    @Test fun matchTitles(): Unit = runBlocking {
        val titles = args.getString("skibidisongTitles")?.split(';')?.filter(String::isNotBlank)
        assumeTrue(titles != null)
        restrictedAccount()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val all = SpotifyCatalog(SpotifyAccount.transport, SpotifyAccount.require(app)).hydrate(Skibidisong.matches().filter { it.method != MatchMethod.Audio }.map { it.trackId })
        for (track in all.filter { track -> titles!!.any { track.title.contains(it, ignoreCase = true) } }) {
            Log.i(TAG, "replay ${track.id} \"${track.title}\" / ${track.subtitle} album=\"${track.album}\" ${track.durationMs} ms, stored ${Skibidisong.matchOf(track.id)}")
            val match = Skibidisong.matchAgain(MatchQuery(track.id, track.title, track.subtitle, track.durationMs, track.album))
            Log.i(TAG, "replay result ${match}")
        }
    }

    private companion object { const val TAG = "SkibidisongTest" }
}
