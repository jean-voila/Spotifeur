package fr.spotifeur.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import fr.spotifeur.data.AudioIndex
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.NetworkMonitor
import fr.spotifeur.data.SpotifyAccount
import fr.spotifeur.data.SpotifyAuthRequired
import fr.spotifeur.data.SpotifyHlsDataSource
import fr.spotifeur.data.isLocalTrack
import fr.spotifeur.data.preferences.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Completes heard tracks and the queue, using the same encrypted files as actual playback. */
@UnstableApi
internal class QueuePreloader(
    context: Context,
    private val player: Player,
    private val source: SpotifyHlsDataSource.Factory,
    private val cacheSources: CacheDataSource.Factory,
    private val scope: CoroutineScope,
    private val audio: NativeAudioProvider?,
    private val youtubeSources: CacheDataSource.Factory? = null,
) : Player.Listener, AutoCloseable {
    private val app = context.applicationContext
    private val index = AudioIndex.get(app)
    private val settings = SettingsStore.get(app)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val heard = LinkedHashMap<String, Music>()
    private var warmJob: Job? = null
    private var networkJob: Job? = null
    private var settingsJob: Job? = null
    private var downloadJob: Job? = null
    private var connectionListener: AutoCloseable? = null
    private var job: Job? = null
    private var revision = 0L
    private var describedId: String? = null
    private var closed = false

    fun start() {
        player.addListener(this)
        connectionListener = NetworkMonitor.onConnectionsInvalidated {
            scope.launch { job?.cancel(); job = null; schedule() }
        }
        // Resume incomplete listens after a process restart too.
        scope.launch {
            val interrupted = withContext(Dispatchers.IO) {
                index.all().filter { it.lastPlayedMs > 0 && it.track.id.startsWith("spotify:track:") && it.file?.isYouTube != true && !AudioCache.isAvailableOffline(app, it.track.id) }
                    .sortedByDescending { it.lastPlayedMs }.map { it.track }
            }
            interrupted.forEach { heard.putIfAbsent(it.id, it) }
            schedule()
        }
        networkJob = scope.launch {
            NetworkMonitor.online.collect { online ->
                if (online) { schedule(); warmRecentFiles() }
                else { job?.cancel(); job = null; warmJob?.cancel() }
            }
        }
        settingsJob = scope.launch {
            settings.settings.collect {
                // Respect Wi-Fi-only immediately, including a fill already in progress.
                job?.cancel(); job = null
                schedule()
            }
        }
        downloadJob = scope.launch {
            DownloadCenter.downloads.collect { warmRecentFiles() }
        }
    }

    private fun warmRecentFiles() {
        if (audio == null) return
        if (!NetworkMonitor.online.value || closed) return
        warmJob?.cancel()
        warmJob = scope.launch {
            delay(WARM_START_DELAY_MS)
            val current = player.currentMediaItemIndex.coerceAtLeast(0)
            val ids = (current until minOf(current + 11, player.mediaItemCount)).map { player.getMediaItemAt(it).mediaId }.toSet()
            // Runs at player start, including on the sign-in screen: no session means nothing to warm.
            val session = SpotifyAccount.restore(app) ?: return@launch
            val files = try {
                withContext(Dispatchers.IO) {
                    val kept = DownloadCenter.downloads.value.values.filter { it.isComplete }
                        .filter { it.track.id in ids }
                        .mapNotNull { index.record(it.track.id)?.file }.filterNot { it.isYouTube }
                    val recent = AudioCache.entries(app).filter { it.isComplete && it.track.id in ids }
                        .mapNotNull { index.record(it.track.id)?.file }.filterNot { it.isYouTube }
                    val keys = NativeKeyStore(app, session.username())
                    (kept + recent).map { it.hex }.distinct().filter { keys.load(it) == null }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@launch }
            for (hex in files) {
                if (!NetworkMonitor.online.value) return@launch
                try { OfflinePreparation.retain(app, hex) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { delay(5_000) }
                delay(WARM_GAP_MS)
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { markPlayed(); schedule() }
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) schedule()
    }
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) { markPlayed(); schedule() }
    }

    private fun markPlayed() {
        val item = player.currentMediaItem ?: return
        if (describedId == item.mediaId || !player.isPlaying) return
        describedId = item.mediaId
        val track = musicOf(item).takeUnless(Music::isLocalTrack) ?: return
        // Only tracks read through Spotify are completed from Spotify.
        if (item.localConfiguration?.uri?.scheme == "spotify") heard[track.id] = track
        scope.launch(Dispatchers.IO) { index.describe(track, played = true) }
    }

    /** A queue change reprioritizes the next file; it never abandons the one being completed. */
    private fun schedule() {
        revision++
        if (closed || !NetworkMonitor.online.value || job?.isActive == true) return
        job = scope.launch {
            delay(SETTLE_MS)
            do {
                val observed = revision
                val s = settings.settings.value
                val current = player.currentMediaItemIndex
                val items = if (current in 0 until player.mediaItemCount) {
                    preloadRange(current, player.mediaItemCount, s.preloadTracks).map { player.getMediaItemAt(it) }
                } else emptyList()
                val queue = items.filter { it.localConfiguration?.uri?.scheme == "spotify" }.map(::musicOf)
                val targets = (queue.take(1) + queue.drop(1) + heard.values.toList().asReversed().take(10))
                    .filterNot(Music::isLocalTrack).distinctBy(Music::id)
                fillYouTube(items.drop(1).filter { YouTubeUris.isYouTube(it.localConfiguration?.uri) }.take(MAX_YOUTUBE_AHEAD), s)
                // Keep the prioritized prefix within the cache budget. A huge queue must not
                // evict its first files just to fetch its tail, then download them all again.
                var plannedBytes = 0L
                for ((position, track) in targets.withIndex()) {
                    ensureActive()
                    if (!NetworkMonitor.online.value) break
                    if (position > 0 && !s.preloadOnMobile && connectivity.isActiveNetworkMetered) break
                    try {
                        val size = withContext(Dispatchers.IO) {
                            index.describe(track, played = false)
                            val resolved = source.resolve(track.id) ?: return@withContext 0L
                            val length = resolved.file.expectedBytes
                            val key = AudioCache.keys.buildCacheKey(DataSpec.Builder().setUri(resolved.address).build())
                            val size = if (length > 0 && DownloadCenter.cache(app).isCached(key, 0, length)) 0L else length
                            if (position > 0 && plannedBytes + size > s.audioCacheLimitMb * BYTES_PER_MB) return@withContext -1L
                            if (length > 0) fill(resolved)
                            size
                        }
                        if (size < 0) break
                        plannedBytes += size
                        if (withContext(Dispatchers.IO) { AudioCache.isAvailableOffline(app, track.id) }) heard.remove(track.id)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: SpotifyAuthRequired) { return@launch }
                    catch (_: IOException) { /* A future network or queue change resumes the holes. */ }
                    catch (_: Exception) { break /* A failed native preparation must not flood the rest of the queue. */ }
                    // Do not keep spending bandwidth on an outdated queue after this file.
                    if (revision != observed) break
                }
                withContext(Dispatchers.IO) { index.flush() }
            } while (revision != observed && currentCoroutineContext().isActive)
        }
    }

    /** The next YouTube Music tracks of the queue, read ahead into the recent cache. */
    private suspend fun fillYouTube(items: List<MediaItem>, s: fr.spotifeur.data.preferences.AppSettings) {
        val sources = youtubeSources ?: return
        for (item in items) {
            if (!NetworkMonitor.online.value || (!s.preloadOnMobile && connectivity.isActiveNetworkMetered)) return
            val uri = item.localConfiguration?.uri ?: continue
            val available = withContext(Dispatchers.IO) { AudioCache.isAvailableOffline(app, item.mediaId) }
            if (available) continue
            val spec = DataSpec.Builder().setUri(uri).setKey(YouTubeUris.cacheKey(item.mediaId)).build()
            val writer = CacheWriter(sources.createDataSourceForDownloading(), spec, null, null)
            try { withContext(Dispatchers.IO) { runInterruptible { writer.cache() } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { /* Played from the network when its turn comes. */ }
            finally { writer.cancel() }
        }
    }

    private suspend fun fill(resolved: SpotifyHlsDataSource.Factory.ResolvedAudio) {
        val file = resolved.file
        if (audio != null) OfflinePreparation.retain(app, file.hex)
        if (AudioCache.isFileComplete(app, file.hex, file.expectedBytes)) return
        val spec = DataSpec.Builder().setUri(Uri.parse(resolved.address)).setPosition(0).setLength(file.expectedBytes).build()
        repeat(3) { attempt ->
            val writer = CacheWriter(cacheSources.createDataSourceForDownloading(), spec, null, null)
            try { runInterruptible { writer.cache() }; return }
            catch (failure: IOException) {
                if (attempt == 2 || !NetworkMonitor.online.value) throw failure
                delay((attempt + 1) * 1_000L)
            } finally { writer.cancel() }
        }
    }

    override fun close() {
        closed = true
        job?.cancel(); warmJob?.cancel(); networkJob?.cancel(); settingsJob?.cancel(); downloadJob?.cancel()
        connectionListener?.close()
        player.removeListener(this)
    }

    private companion object {
        const val SETTLE_MS = 1_000L
        const val WARM_START_DELAY_MS = 2_000L
        const val WARM_GAP_MS = 400L
        const val BYTES_PER_MB = 1024L * 1024
        /** Read-ahead stays short: anonymous YouTube traffic is rate-limited. */
        const val MAX_YOUTUBE_AHEAD = 2
    }
}

/** Brings playback back by itself when the network does, and moves on to what is on the device without it. */
@UnstableApi
internal class PlaybackRecovery(
    context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
    private val audio: NativeAudioProvider?,
) : Player.Listener, AutoCloseable {
    private val app = context.applicationContext
    private var attempts = 0
    private var lastFailureAt = 0L
    private var retry: Job? = null

    fun start() {
        player.addListener(this)
        scope.launch {
            NetworkMonitor.online.collect { online ->
                if (online && player.playerError?.hasNativePlaybackRefusal() == false) { attempts = 0; player.prepare() }
            }
        }
    }

    // READY alone does not reset the count: a track that fails after its clear lead-in becomes READY
    // again at every retry, and would otherwise stop and restart forever.
    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { attempts = 0 }

    override fun onPlayerError(error: PlaybackException) {
        retry?.cancel()
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFailureAt > STABLE_PLAYBACK_MS) attempts = 0
        lastFailureAt = now
        if (!player.playWhenReady) return
        if (!NetworkMonitor.online.value) { skipToAvailable(); return }
        if (error.hasNativePlaybackRefusal()) return
        if (attempts >= MAX_ATTEMPTS || (!error.isConnectionFailure() && !error.hasNativePreparationFailure())) return
        attempts++
        retry = scope.launch {
            delay(attempts * RETRY_STEP_MS)
            if (player.playerError?.hasNativePlaybackRefusal() == false) { audio?.retryFailed(); player.prepare() }
        }
    }

    private fun skipToAvailable() {
        val current = player.currentMediaItemIndex
        val targets = (current + 1 until player.mediaItemCount).map { it to player.getMediaItemAt(it) }
        retry = scope.launch {
            for ((position, item) in targets) {
                if (player.currentMediaItemIndex != current || NetworkMonitor.online.value) return@launch
                val scheme = item.localConfiguration?.uri?.scheme
                if (scheme == YouTubeUris.SCHEME) {
                    if (!withContext(Dispatchers.IO) { AudioCache.isAvailableOffline(app, item.mediaId) }) continue
                } else if (scheme == "spotify") {
                    val file = withContext(Dispatchers.IO) {
                        AudioIndex.get(app).record(item.mediaId)?.file?.takeIf { AudioCache.isAvailableOffline(app, item.mediaId) }
                    } ?: continue
                    val provider = audio ?: continue
                    provider.warm(file.hex)
                    val ready = withTimeoutOrNull(4_000) { while (!provider.isReady(file.hex)) delay(50); true }
                    if (ready != true) continue
                }
                if (player.currentMediaItemIndex != current || player.playerError == null || !player.playWhenReady) return@launch
                player.seekToDefaultPosition(position)
                player.prepare()
                return@launch
            }
        }
    }

    private fun PlaybackException.isConnectionFailure() = errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
        errorCode == PlaybackException.ERROR_CODE_TIMEOUT

    private fun Throwable.hasNativePreparationFailure(): Boolean = generateSequence(this) { it.cause }
        .take(12).any { it is NativeAudioException || it is java.util.concurrent.TimeoutException }

    override fun close() {
        retry?.cancel()
        player.removeListener(this)
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
        const val RETRY_STEP_MS = 1_500L
        const val STABLE_PLAYBACK_MS = 60_000L
    }
}

/** Retries at a steady pace: a lost connection usually comes back within seconds, and fails again just as fast. */
@UnstableApi
internal class ResilientLoadPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        // A track YouTube Music does not have, or refuses to every client, will not appear by retrying.
        val youtube = generateSequence<Throwable>(loadErrorInfo.exception) { it.cause }.take(8)
            .filterIsInstance<fr.spotifeur.data.youtube.YouTubeFailure>().firstOrNull()
        if (youtube != null && youtube.status in PERMANENT_YOUTUBE_FAILURES) return C.TIME_UNSET
        val base = super.getRetryDelayMsFor(loadErrorInfo)
        return if (base == C.TIME_UNSET) base else minOf(base, MAX_RETRY_DELAY_MS)
    }
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = if (NetworkMonitor.online.value) ONLINE_RETRIES else OFFLINE_RETRIES

    private companion object {
        const val MAX_RETRY_DELAY_MS = 2_000L
        const val ONLINE_RETRIES = 6
        const val OFFLINE_RETRIES = 1
        val PERMANENT_YOUTUBE_FAILURES = setOf("NO_MATCH", "NO_AUDIO_FORMAT", "ALL_CLIENTS_EXCLUDED", "UNPLAYABLE", "ERROR", "LOGIN_REQUIRED")
    }
}

internal fun musicOf(item: MediaItem) = Music(item.mediaId, item.mediaMetadata.title?.toString().orEmpty(),
    item.mediaMetadata.artist?.toString().orEmpty(), MusicKind.Track, imageUrl = item.mediaMetadata.artworkUri?.toString(),
    durationMs = item.mediaMetadata.extras?.getInt("duration") ?: 0, artwork = item.mediaMetadata.extras?.getInt("artwork") ?: 0,
    album = item.mediaMetadata.extras?.getString("album").orEmpty(), mediaUri = item.mediaMetadata.extras?.getString("mediaUri"),
    quality = item.mediaMetadata.extras?.getString("quality").orEmpty())

/** The queue length never expands the prefetch window beyond twenty following tracks. */
internal fun preloadRange(current: Int, count: Int, following: Int): IntRange =
    if (current !in 0 until count) IntRange.EMPTY else current..minOf(current + following.coerceIn(0, 20), count - 1)
