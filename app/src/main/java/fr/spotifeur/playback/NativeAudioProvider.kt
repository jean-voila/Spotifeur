package fr.spotifeur.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.SynchronousMediaCodecAdapter
import fr.spotifeur.data.AudioGrant
import fr.spotifeur.data.AudioQuality
import kotlinx.coroutines.runBlocking
import java.util.concurrent.*

/** Holds subscriptions for as long as the codec may use their opaque media handles. */
@UnstableApi
internal class NativeAudioProvider(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var runtime = CompletableFuture<SpotifyNativeRuntime>()
    private class Media(@Volatile var scheme: NativeAudioScheme?, val fileId: String, val runtime: CompletableFuture<SpotifyNativeRuntime>) {
        val handle = CompletableFuture<Int>()
        @Volatile var subscription: AutoCloseable? = null
        @Volatile var unavailable = false
        @Volatile var currentHandle = 0
        @Volatile var lastUsed = System.nanoTime()
    }
    private val media = ConcurrentHashMap<String, Media>()
    @Volatile private var codecMediaId: String? = null
    @Volatile private var closed = false
    private var started = false
    private val accountListener = fr.spotifeur.data.SpotifyAccount.onSessionReplaced(::resetSession)

    @Synchronized private fun resetSession() {
        if (closed) return
        val previous = runtime
        val entries = media.values.toList()
        entries.forEach { it.unavailable = true; it.handle.completeExceptionally(NativeAudioException("Session replaced.")) }
        media.clear()
        runtime = CompletableFuture()
        started = false
        executor.execute {
            entries.forEach { runCatching { it.subscription?.close() } }
            if (previous.isDone && !previous.isCompletedExceptionally) previous.get().close()
        }
    }

    @Synchronized fun prepare(key: ByteArray, scheme: NativeAudioScheme, fileId: String) {
        check(!closed)
        open(NativeAudioProtocol.keyId(key), scheme, fileId)
    }

    /**
     * Readies a file before it is read. Native response envelopes are encrypted on the device;
     * a fresh engine can interpret them locally after a restart, without exporting a media key.
     */
    @Synchronized fun warm(fileId: String) {
        if (closed) return
        open(fileId.take(KEY_ID_LENGTH), null, fileId)
    }

    fun isReady(fileId: String): Boolean = media[fileId.take(KEY_ID_LENGTH)]?.let { !it.unavailable && it.currentHandle != 0 } == true

    private fun open(id: String, scheme: NativeAudioScheme?, fileId: String) {
        if (runtime.isCompletedExceptionally) {
            runtime = CompletableFuture()
            started = false
        }
        val current = runtime
        if (!started) {
            started = true
            executor.execute {
                try { current.complete(runBlocking { SpotifyNativeRuntime.open(app) }) }
                catch (failure: Throwable) { current.completeExceptionally(failure) }
            }
        }
        media[id]?.let { old ->
            if (!old.unavailable && !old.handle.isCompletedExceptionally) {
                if (scheme != null) old.scheme = scheme
                old.lastUsed = System.nanoTime()
                return
            }
            executor.execute { old.subscription?.close() }
        }
        val item = Media(scheme, fileId, current)
        media[id] = item
        // Extractors may prepare ahead, but a queue must not hold thousands of live handles.
        media.entries.sortedBy { it.value.lastUsed }.filter { it.key != id && it.key != codecMediaId }
            .take((media.size - 8).coerceAtLeast(0)).forEach { entry ->
                if (media.remove(entry.key, entry.value)) executor.execute { entry.value.subscription?.close() }
            }
        executor.execute {
            try {
                if (!closed && media[id] === item) item.subscription = current.get().subscribe(item.fileId, { result ->
                    when (result) {
                        is MediaHandleResult.Ready -> {
                            item.currentHandle = result.handle
                            item.handle.complete(result.handle)
                        }
                        MediaHandleResult.Failed, MediaHandleResult.Sunset -> {
                            fr.spotifeur.data.diagnostics.DiagnosticLog.record("audio",
                                "media handle ${result.toString().lowercase()} bitrate=${fr.spotifeur.data.AudioGrant.describe(item.fileId)}")
                            item.unavailable = true
                            item.handle.completeExceptionally(NativeAudioException("Spotify rejected audio preparation."))
                            executor.execute { item.subscription?.close() }
                        }
                        else -> Unit
                    }
                }, {
                    fr.spotifeur.data.diagnostics.DiagnosticLog.record("audio", "media handle error=${it.javaClass.simpleName}")
                    item.unavailable = true; item.handle.completeExceptionally(it)
                })
            } catch (failure: Throwable) { item.handle.completeExceptionally(failure) }
        }
    }

    val codecFactory = MediaCodecAdapter.Factory { config ->
        val codec = SynchronousMediaCodecAdapter.Factory().createAdapter(config)
        object : MediaCodecAdapter by codec {
            override fun queueSecureInputBuffer(index: Int, offset: Int, info: CryptoInfo, timeUs: Long, flags: Int) {
                try {
                    val id = NativeAudioProtocol.keyId(checkNotNull(info.key))
                    codecMediaId = id
                    val item = media[id] ?: throw NativeAudioException("Missing audio context.")
                    if (item.unavailable || closed) throw NativeAudioException("The audio session expired.")
                    // This thread drives the whole player: waiting for a network that is not there would freeze it.
                    item.handle.get(if (fr.spotifeur.data.NetworkMonitor.online.value) ONLINE_WAIT_SECONDS else OFFLINE_WAIT_SECONDS, TimeUnit.SECONDS)
                    if (item.unavailable || closed) throw NativeAudioException("The audio session expired.")
                    val handle = item.currentHandle
                    val size = try {
                        item.runtime.get().processor.processSample(handle, checkNotNull(item.scheme), checkNotNull(codec.getInputBuffer(index)),
                            offset, info.numSubSamples, info.numBytesOfClearData, checkNotNull(info.numBytesOfEncryptedData),
                            info.encryptedBlocks, info.clearBlocks, checkNotNull(info.iv))
                    } catch (refusal: NativeProcessorRefused) {
                        if (AudioGrant.bandwidthOf(item.fileId) == AudioQuality.NORMAL_BANDWIDTH) throw NativePlaybackRefused(refusal)
                        throw refusal
                    }
                    codec.queueInputBuffer(index, offset, size, timeUs, flags)
                } catch (failure: Exception) {
                    if (failure is android.media.MediaCodec.CodecException || failure is android.media.MediaCodec.CryptoException) throw failure
                    if (failure is TimeoutException) info.key?.let { key -> media[NativeAudioProtocol.keyId(key)]?.let {
                        it.unavailable = true
                        it.handle.completeExceptionally(failure)
                    } }
                    // ExoPlayer translates CryptoException into a recoverable player error.
                    // An arbitrary exception escaping its codec loop would crash the process.
                    throw android.media.MediaCodec.CryptoException(android.media.MediaCodec.CryptoException.ERROR_NO_KEY,
                        "Native audio preparation failed.").apply { initCause(failure) }
                }
            }
        }
    }

    fun cancelPending() {
        media.values.forEach { it.handle.completeExceptionally(NativeAudioException("Playback stopped.")) }
    }

    @Synchronized fun retryFailed() {
        if (closed) return
        media.entries.toList().filter { it.value.unavailable || it.value.handle.isCompletedExceptionally }.forEach { (id, item) ->
            open(id, item.scheme, item.fileId)
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        accountListener.close()
        cancelPending()
        executor.execute {
            media.values.forEach { runCatching { it.subscription?.close() } }
            media.clear()
            if (runtime.isDone && !runtime.isCompletedExceptionally) runtime.get().close()
        }
        executor.shutdown()
    }

    /** Instrumented tests only. */
    internal fun activeHandlesForTest(): Int = media.size

    /** Instrumented tests only. */
    internal fun setOfflineForTest(offline: Boolean) {
        if (runtime.isDone && !runtime.isCompletedExceptionally) runtime.get().setOfflineForTest(offline)
    }

    private companion object {
        const val KEY_ID_LENGTH = 32
        const val ONLINE_WAIT_SECONDS = 10L
        const val OFFLINE_WAIT_SECONDS = 2L
    }
}
