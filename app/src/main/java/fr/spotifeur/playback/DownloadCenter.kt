package fr.spotifeur.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.*
import fr.spotifeur.BuildConfig
import fr.spotifeur.MainActivity
import fr.spotifeur.R
import fr.spotifeur.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DownloadedTrack(
    val track: Music, val isComplete: Boolean, val percent: Float,
    val bytes: Long = 0, val totalBytes: Long = 0, val failed: Boolean = false,
    val queued: Boolean = false, val paused: Boolean = false,
)

/** Persistent user requests share track files; a foreground service owns preparation and transfer. */
@UnstableApi
object DownloadCenter {
    private var cache: SimpleCache? = null
    private var manager: DownloadManager? = null
    private var app: Context? = null
    private var records = linkedMapOf<String, DownloadCollection>()
    private val failed = hashSetOf<String>()
    private val failures = hashMapOf<String, () -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var helperUpstream: SpotifyHlsDataSource.Factory? = null
    private val mutable = MutableStateFlow<Map<String, DownloadedTrack>>(emptyMap())
    val downloads = mutable.asStateFlow()
    private val mutableCollections = MutableStateFlow<Map<String, CollectionProgress>>(emptyMap())
    val collections = mutableCollections.asStateFlow()
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var speed = 0.0
    private var loaded = false
    private var migrateLegacy = false
    private var preparation: Pair<String, Job>? = null
    private var sweep: Job? = null
    private val mutableBytes = MutableStateFlow(0L)
    /** Bytes actually held by explicit downloads, refreshed once removals have been swept. */
    val storedBytes = mutableBytes.asStateFlow()

    @Synchronized fun cache(context: Context): SimpleCache = cache ?: SimpleCache(
        File(context.applicationContext.noBackupFilesDir, "offline-audio"), NoOpCacheEvictor(), AudioCache.database(context),
    ).also { cache = it }

    @Synchronized fun manager(context: Context): DownloadManager = manager ?: run {
        val application = context.applicationContext
        app = application
        if (!loaded) {
            val file = AtomicFile(File(application.noBackupFilesDir, "download-collections.json"))
            val stored = runCatching { file.openRead().bufferedReader().use { it.readText() } }.getOrNull()
            migrateLegacy = stored == null
            records = runCatching { decodeDownloadCollections(stored.orEmpty()) }
                .getOrDefault(emptyList()).associateByTo(linkedMapOf()) { it.item.id }
            loaded = true
        }
        val upstream = SpotifyHlsDataSource.Factory(application, nativeAudio = BuildConfig.NATIVE_RUNTIME, forDownloads = true).also { helperUpstream = it }
        val recent = CacheDataSource.Factory().setCache(AudioCache.cache(application)).setCacheKeyFactory(AudioCache.keys)
            .setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS)
        val writer = CacheDataSource.Factory().setCache(cache(application)).setCacheKeyFactory(AudioCache.keys)
            .setUpstreamDataSourceFactory(recent).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS)
        val executor = Executors.newFixedThreadPool(2)
        val spotifyDownloads = DefaultDownloaderFactory(writer, executor)
        // YouTube Music files have no length before they are opened: their own chain writes them whole.
        val youtubeWriter = CacheDataSource.Factory().setCache(cache(application)).setCacheKeyFactory(AudioCache.keys)
            .setUpstreamDataSourceFactory(CacheDataSource.Factory().setCache(AudioCache.cache(application)).setCacheKeyFactory(AudioCache.keys)
                .setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(YouTubeDataSource.Factory(application)))
        val downloaders = DownloaderFactory { request ->
            if (request.uri.scheme == YouTubeUris.SCHEME) ProgressiveDownloader(
                MediaItem.Builder().setUri(request.uri).setCustomCacheKey(request.customCacheKey).build(), youtubeWriter, executor,
            ) else spotifyDownloads.createDownloader(request)
        }
        DownloadManager(application, DefaultDownloadIndex(AudioCache.database(application)), downloaders).also { created ->
            manager = created
            created.maxParallelDownloads = 2
            created.addListener(object : DownloadManager.Listener {
                override fun onInitialized(downloadManager: DownloadManager) {
                    // Existing 1.1.0 downloads have no collection metadata: preserve them as singles.
                    publish()
                    if (migrateLegacy) mutable.value.values.filter { entry -> records.values.none { record -> record.tracks.any { it.id == entry.track.id } } }
                        .forEach { records[it.track.id] = DownloadCollection(it.track, listOf(it.track)) }
                    migrateLegacy = false
                    val kept = records.values.flatMap { it.tracks }.mapTo(hashSetOf()) { it.id }
                    val orphaned = mutableListOf<String>()
                    downloadManager.downloadIndex.getDownloads().use { cursor -> while (cursor.moveToNext()) {
                        if (cursor.download.request.id !in kept) orphaned.add(cursor.download.request.id)
                    } }
                    orphaned.forEach(downloadManager::removeDownload)
                    save(); publish()
                    // Also reclaims audio left behind by removals made before this sweep existed.
                    scheduleSweep()
                }
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                    publish()
                    if (download.state == Download.STATE_COMPLETED) scope.launch(Dispatchers.IO) { AudioIndex.get(application).flush() }
                }
                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) { publish(); scheduleSweep() }
            })
            scope.launch { fr.spotifeur.data.skibidisong.Skibidisong.active.collect { active -> if (active) convertToYouTube(application, created) } }
            scope.launch { NetworkMonitor.online.collect { online ->
                if (online) created.resumeDownloads() else created.pauseDownloads()
                samples.clear(); speed = 0.0; publish()
            } }
        }
    }

    private fun scheduleSweep() {
        val context = app ?: return
        val current = manager ?: return
        sweep?.cancel()
        sweep = scope.launch(Dispatchers.IO) {
            delay(SWEEP_DELAY_MS)
            sweepOrphanedAudio(context, current)
        }
    }

    /** Media3 removes HLS segments by reading the manifest back from this cache, but manifests
     * bypass it (unset length): the segments of a removed download would stay on the device.
     * Audio is keyed by file, so every audio key no live download refers to is released. */
    private fun sweepOrphanedAudio(context: Context, current: DownloadManager) {
        val store = cache(context)
        val index = AudioIndex.get(context)
        val live = hashSetOf<String>()
        current.downloadIndex.getDownloads().use { cursor -> while (cursor.moveToNext()) {
            val download = cursor.download
            if (download.state == Download.STATE_REMOVING) continue
            // A live download whose file is unknown could share any key: keep everything.
            val hex = index.record(download.request.id)?.file?.hex ?: run { mutableBytes.value = store.cacheSpace; return }
            live += AudioCache.key(hex)
        } }
        store.keys.filter { AudioCache.isAudioKey(it) && it !in live }.forEach(store::removeResource)
        mutableBytes.value = store.cacheSpace
    }

    private fun save() {
        val context = app ?: return
        val file = AtomicFile(File(context.noBackupFilesDir, "download-collections.json"))
        val stream = file.startWrite()
        try { stream.write(encodeDownloadCollections(records.values).toByteArray()); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }

    internal fun publish() {
        val current = manager ?: return
        val active = current.currentDownloads.associateBy { it.request.id }
        val found = linkedMapOf<String, DownloadedTrack>()
        val wanted = records.values.flatMap { it.tracks }.associateBy { it.id }
        current.downloadIndex.getDownloads().use { cursor -> while (cursor.moveToNext()) {
            val download = active[cursor.download.request.id] ?: cursor.download
            val track = decodePins(download.request.data.decodeToString()).firstOrNull() ?: continue
            if (!migrateLegacy && track.id !in wanted) continue
            if (download.state == Download.STATE_REMOVING) continue
            val complete = download.state == Download.STATE_COMPLETED
            val expected = download.contentLength.takeIf { it > 0 } ?: app?.let { AudioIndex.get(it).record(track.id)?.file?.expectedBytes } ?: 0
            val percent = if (complete) 100f else maxOf(download.percentDownloaded.coerceAtLeast(0f),
                if (expected > 0) (download.bytesDownloaded * 100f / expected).coerceAtMost(99.9f) else 0f)
            found[track.id] = DownloadedTrack(track, complete,
                percent, download.bytesDownloaded, expected,
                download.state == Download.STATE_FAILED, paused = !NetworkMonitor.online.value)
        } }
        wanted.forEach { (id, track) -> if (id !in found) found[id] = DownloadedTrack(
            track, false, 0f, failed = id in failed, queued = true, paused = !NetworkMonitor.online.value,
        ) }
        mutable.value = found
        val now = SystemClock.elapsedRealtime()
        val bytes = found.values.sumOf { it.bytes }
        if (samples.lastOrNull()?.second?.let { bytes < it } == true) samples.clear()
        samples.addLast(now to bytes)
        while (samples.size > 2 && samples.first().first < now - 15_000) samples.removeFirst()
        val first = samples.first()
        speed = if (now - first.first >= 1_000) (bytes - first.second) * 1000.0 / (now - first.first) else 0.0
        mutableCollections.value = records.mapValues { (_, value) ->
            collectionProgress(value, found, failed, !NetworkMonitor.online.value, speed)
        }
    }

    fun start(context: Context) {
        manager(context); publish()
        if (context !is SpotifeurDownloadService && hasWork()) ContextCompat.startForegroundService(context, Intent(context, SpotifeurDownloadService::class.java))
    }

    fun enqueue(context: Context, item: Music, tracks: List<Music>, onFailure: () -> Unit = {}) {
        manager(context)
        val spotify = tracks.filterNot(Music::isLocalTrack)
        if (spotify.isEmpty()) return
        records[item.id] = DownloadCollection(item, spotify)
        spotify.forEach { track ->
            failed.remove(track.id); failures[track.id] = onFailure
            if (mutable.value[track.id]?.failed == true) manager?.removeDownload(track.id)
        }
        save(); publish()
        ContextCompat.startForegroundService(context.applicationContext, Intent(context, SpotifeurDownloadService::class.java))
    }

    /**
     * In Skibidisong mode a Spotify file stops after its clear lead-in: kept tracks are downloaded again
     * as their YouTube Music recording.
     */
    private suspend fun convertToYouTube(context: Context, current: DownloadManager) {
        while (!current.isInitialized) delay(200)
        val spotify = withContext(Dispatchers.IO) {
            buildList { current.downloadIndex.getDownloads().use { cursor -> while (cursor.moveToNext()) {
                val download = cursor.download
                if (download.request.uri.scheme == "spotify" && download.state != Download.STATE_REMOVING) add(download.request.id)
            } } }
        }
        if (spotify.isEmpty()) return
        android.util.Log.i("Skibidisong", "downloads: ${spotify.size} Spotify files replaced by their YouTube Music recording")
        spotify.forEach(current::removeDownload)
        publish()
        start(context)
    }

    /** Compatibility for callers requesting an individual track. */
    fun add(context: Context, track: Music, onFailure: () -> Unit) = enqueue(context, track, listOf(track), onFailure)

    internal suspend fun prepareNext(): Boolean = coroutineScope {
        if (!NetworkMonitor.online.value) return@coroutineScope false
        val current = manager ?: return@coroutineScope false
        if (!current.isInitialized) return@coroutineScope false
        val track = records.values.flatMap { it.tracks }.firstOrNull { mutable.value[it.id]?.queued == true && it.id !in failed } ?: return@coroutineScope false
        val operation = async(start = CoroutineStart.LAZY) { prepareTrack(track, current) }
        preparation = track.id to operation
        try { operation.start(); operation.await() }
        catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
        finally { if (preparation?.second === operation) preparation = null }
        publish()
        true
    }

    private suspend fun prepareTrack(track: Music, current: DownloadManager) {
        val context = checkNotNull(app)
        try {
            withContext(Dispatchers.IO) { AudioIndex.get(context).describe(track, played = false) }
            // YouTube Music tracks, and Spotify tracks without an account or in Skibidisong mode, are kept as their YouTube Music file.
            YouTubeUris.of(track)?.let { uri ->
                val request = DownloadRequest.Builder(track.id, uri).setCustomCacheKey(YouTubeUris.cacheKey(track.id))
                    .setData(encodePins(listOf(track)).toByteArray()).build()
                if (records.values.any { record -> record.tracks.any { it.id == track.id } }) {
                    current.addDownload(request)
                    withTimeout(5_000) { while (current.downloadIndex.getDownload(track.id) == null) delay(50) }
                }
                return
            }
            val item = MediaItem.Builder().setUri(Uri.parse(track.id)).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            val request = suspendCancellableCoroutine<DownloadRequest> { continuation ->
                val helper = DownloadHelper.forMediaItem(context, item, DefaultRenderersFactory(context), checkNotNull(helperUpstream))
                continuation.invokeOnCancellation { scope.launch { helper.release() } }
                helper.prepare(object : DownloadHelper.Callback {
                    override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
                        try {
                            val request = helper.getDownloadRequest(track.id, encodePins(listOf(track)).toByteArray())
                            if (continuation.isActive) continuation.resume(request)
                        } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                        finally { helper.release() }
                    }
                    override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                        helper.release(); if (continuation.isActive) continuation.resumeWithException(e)
                    }
                })
            }
            if (BuildConfig.NATIVE_RUNTIME) {
                val file = withContext(Dispatchers.IO) { checkNotNull(AudioIndex.get(context).record(track.id)?.file) }
                OfflinePreparation.retain(context, file.hex)
            }
            // A user can cancel a collection while its manifest/key is being prepared.
            if (records.values.any { record -> record.tracks.any { it.id == track.id } }) {
                current.addDownload(request)
                // Wait for the persistent index before selecting the next missing track.
                withTimeout(5_000) { while (current.downloadIndex.getDownload(track.id) == null) delay(50) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (NetworkMonitor.online.value) { failed.add(track.id); failures.remove(track.id)?.invoke() }
        }
    }

    internal fun hasWork(): Boolean = records.values.any { collection -> collection.tracks.any { track ->
        val state = mutable.value[track.id]
        track.id !in failed && state?.failed != true && state?.isComplete != true
    } } || manager?.currentDownloads?.any { it.state == Download.STATE_REMOVING } == true

    fun remove(context: Context, itemId: String) {
        manager(context)
        // Removing a group or an explicit single preserves files referenced by other requests.
        val removed = records.remove(itemId) ?: return
        removeUnreferenced(removed.tracks.map { it.id })
    }

    fun cancel(context: Context, itemId: String) {
        manager(context)
        val removed = records[itemId] ?: return
        val completed = mutable.value.filterValues { it.isComplete }.keys
        records = cancelDownloadCollection(records, itemId, completed).toMap(linkedMapOf())
        removeUnreferenced(removed.tracks.map { it.id })
    }

    private fun removeUnreferenced(candidates: List<String>) {
        save()
        val kept = records.values.flatMap { it.tracks }.mapTo(hashSetOf()) { it.id }
        preparation?.takeIf { it.first !in kept }?.second?.cancel()
        candidates.filterNot { it in kept }.forEach { manager?.removeDownload(it); failed.remove(it); failures.remove(it) }
        publish()
    }
    fun removeAll(context: Context) {
        manager(context); records.clear(); preparation?.second?.cancel(); failed.clear(); failures.clear(); save(); manager?.removeAllDownloads(); publish()
    }
    fun sizeBytes(context: Context): Long = cache(context).cacheSpace
    private const val SWEEP_DELAY_MS = 500L
}

/** Owns even the preparation phase, so leaving the activity never abandons a batch. */
@UnstableApi
class SpotifeurDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    private val notificationItems = linkedSetOf<String>()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.downloads_channel), NotificationManager.IMPORTANCE_LOW))
        DownloadCenter.start(this)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification())
        if (worker?.isActive != true) worker = scope.launch {
            var nativeLease: SpotifyNativeRuntime? = null
            val updates = launch { while (isActive) {
                DownloadCenter.publish()
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
                delay(1_000)
            } }
            try {
                do {
                    if (BuildConfig.NATIVE_RUNTIME && nativeLease == null && fr.spotifeur.data.NetworkMonitor.online.value && DownloadCenter.hasWork()) {
                        try { nativeLease = SpotifyNativeRuntime.open(this@SpotifeurDownloadService) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Individual preparations expose a retryable error. */ }
                    }
                    if (!DownloadCenter.prepareNext()) delay(500)
                } while (DownloadCenter.hasWork())
            } finally {
                updates.cancel()
                withContext(NonCancellable + Dispatchers.IO) { nativeLease?.close(); AudioIndex.get(this@SpotifeurDownloadService).flush() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }
    private fun notification(): Notification {
        val all = DownloadCenter.collections.value
        notificationItems.addAll(all.filterValues { !it.isComplete }.keys)
        val progress = all.filterKeys { it in notificationItems }.values.toList()
        val done = progress.sumOf { it.completed }
        val total = progress.sumOf { it.total }
        val percent = if (total == 0) 100 else progress.sumOf { it.percent * it.total } / total
        val eta = progress.mapNotNull { it.remainingSeconds }.maxOrNull()
        val text = when {
            progress.any { it.paused } -> getString(R.string.download_waiting_network)
            progress.any { it.failed } -> getString(R.string.download_retry_needed)
            else -> downloadProgressText(this, percent, done, total, eta)
        }
        val title = progress.singleOrNull()?.collection?.item?.title ?: getString(R.string.library_tab_downloads)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_spotifeur)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, percent, total == 0).setCategory(NotificationCompat.CATEGORY_PROGRESS).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { DownloadCenter.manager(this).pauseDownloads(); stopSelf() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private companion object { const val CHANNEL = "spotifeur_downloads"; const val NOTIFICATION = 7301 }
}

fun downloadProgressText(context: Context, percent: Int, completed: Int, total: Int, seconds: Long?): String {
    val eta = seconds?.let { if (it < 60) context.getString(R.string.download_eta_seconds, it)
        else context.getString(R.string.download_eta_minutes, (it + 59) / 60) }
        ?: context.getString(R.string.download_eta_calculating)
    return context.getString(R.string.collection_download_progress, percent, completed, total, eta)
}
