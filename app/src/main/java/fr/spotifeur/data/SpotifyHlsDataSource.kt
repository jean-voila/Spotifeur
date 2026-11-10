package fr.spotifeur.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import fr.spotifeur.playback.AudioCache
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Resolves the APK's spotify-audio audio identifier on the loader thread, before HLS parsing. */
@UnstableApi
internal class SpotifyHlsDataSource private constructor(
    private val upstream: HttpDataSource,
    private val factory: Factory,
) : HttpDataSource by upstream {
    @Volatile private var resolution: Job? = null
    private var memory: java.io.ByteArrayInputStream? = null
    private var memoryUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme == "spotify") {
            val master = try {
                runBlocking {
                    resolution = coroutineContext[Job]
                    withTimeout(30_000) { factory.manifest(uri.toString()) }
                }
            } catch (failure: Exception) {
                throw if (failure is IOException) failure else IOException("Track preparation was interrupted.", failure)
            } finally { resolution = null }
            return openMemory(factory.master(uri.toString(), master), Uri.parse(master), dataSpec)
        }
        if (uri.host == API_HOST && uri.path.orEmpty().endsWith(".m3u8")) {
            return openMemory(factory.playlist(uri.toString()), uri, dataSpec)
        }
        val file = audioFileHex(uri.path) ?: return upstream.open(dataSpec)
        if (!NetworkMonitor.online.value) throw SpotifyFailure("This track is not available offline.")
        val address = factory.signedAddress(file)
        try { return upstream.open(if (address != null) dataSpec.withUri(Uri.parse(address)) else dataSpec) }
        catch (failure: HttpDataSource.InvalidResponseCodeException) {
            // A paused or cached playlist outlives its signature, never its file.
            if (failure.responseCode !in EXPIRED_SIGNATURE) throw failure
            upstream.close()
            val renewed = factory.renewAddress(file) ?: throw failure
            return upstream.open(dataSpec.withUri(Uri.parse(renewed)))
        }
    }

    /** Playlists are served from their stored text: they never change for a given file. */
    private fun openMemory(text: String, address: Uri, dataSpec: DataSpec): Long {
        val bytes = text.toByteArray()
        val start = dataSpec.position.toInt().coerceIn(0, bytes.size)
        val available = bytes.size - start
        val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(dataSpec.length.toInt(), available)
        memory = java.io.ByteArrayInputStream(bytes, start, length)
        memoryUri = address
        return length.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val stored = memory ?: return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        val count = stored.read(buffer, offset, length)
        return if (count < 0) C.RESULT_END_OF_INPUT else count
    }

    override fun getUri(): Uri? = memoryUri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = if (memory != null) emptyMap() else upstream.responseHeaders

    override fun close() {
        resolution?.cancel()
        if (memory != null) { memory = null; memoryUri = null } else upstream.close()
    }

    class Factory(context: Context, private val nativeAudio: Boolean = false, private val forDownloads: Boolean = false) : HttpDataSource.Factory, AutoCloseable {
        internal val app = context.applicationContext
        private val manifests = ConcurrentHashMap<String, String>()
        private val closed = AtomicBoolean()
        private val index = AudioIndex.get(app)
        private val signedAddresses = ConcurrentHashMap<String, String>()
        private val client = OkHttpClient.Builder()
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val body = request.body
                if (request.url.host != API_HOST || body == null || body.contentLength() < 500 ||
                    request.header("Content-Encoding") != null || request.header("X-Transfer-Encoding") != null) {
                    chain.proceed(request)
                } else {
                    // zq50 in the APK compresses request bodies at this same threshold.
                    val raw = okio.Buffer().apply { body.writeTo(this) }.readByteArray()
                    val bytes = java.io.ByteArrayOutputStream().apply {
                        java.util.zip.GZIPOutputStream(this).use { it.write(raw) }
                    }.toByteArray()
                    chain.proceed(request.newBuilder().header("Content-Encoding", "gzip")
                        .method(request.method, bytes.toRequestBody(body.contentType())).build())
                }
            }
            .addNetworkInterceptor { chain ->
                val original = chain.request()
                if (!original.url.isHttps) throw IOException("A secure audio connection is required.")
                // Applied on every network hop, including redirects to a CDN or provisioning host.
                val request = original.newBuilder().removeHeader("Authorization").removeHeader("client-token")
                    .header("User-Agent", SpotifyAndroid.userAgent)
                if (original.url.host == API_HOST) {
                    val headers = runBlocking { SpotifyAccount.require(app).headers(API_HOST) }
                    request.header("Authorization", "Bearer ${headers.user}").header("client-token", headers.client)
                        .header("Spotify-App-Version", SpotifyAndroid.VERSION)
                        .header("X-Client-Id", SpotifyAndroid.CLIENT_ID)
                        .header("App-Platform", "Android")
                        .header("Accept-Language", java.util.Locale.getDefault().toLanguageTag())
                }
                chain.proceed(request.build())
            }.build()
        private val http = OkHttpDataSource.Factory(client).setUserAgent(SpotifyAndroid.userAgent)
        private val networkListener = NetworkMonitor.onConnectionsInvalidated {
            if (!closed.get()) {
                if (!NetworkMonitor.online.value) client.dispatcher.cancelAll()
                kotlin.concurrent.thread { client.connectionPool.evictAll() }
            }
        }

        /** The track's master playlist, reduced to the variant this device keeps. */
        internal fun master(track: String, masterUrl: String): String {
            val text = playlist(masterUrl)
            val base = masterUrl.toHttpUrl()
            variantBandwidths(text).forEach { (variant, bandwidth) -> base.resolve(variant)?.let {
                index.rememberPlaylistOwner(it.toString(), track)
                AudioGrant.rememberVariant(it.toString(), bandwidth)
            } }
            val settings = fr.spotifeur.data.preferences.SettingsStore.get(app).settings.value
            val target = if (forDownloads) AudioQuality.of(settings.downloadQuality).bandwidth(metered = false)
                else AudioQuality.of(if (metered()) settings.streamingQualityMobile else settings.streamingQualityWifi).bandwidth(metered())
            val maximum = runBlocking { SpotifyAccount.require(app).maximumAudioBandwidth() }
            return selectVariant(text, target, maximum) { variant, _ ->
                base.resolve(variant)?.let { index.fileOfPlaylist(it.toString()) }?.let {
                    // A download keeps to its own copies; playback uses whatever is already on the device.
                    if (forDownloads) AudioCache.downloadedBytesOfFile(app, it.hex) else AudioCache.cachedBytesOfFile(app, it.hex)
                } ?: 0
            }
        }

        private fun metered(): Boolean =
            app.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

        /** Stored text first; the service is only asked once per playlist, or to renew a signature. */
        internal fun playlist(url: String, fresh: Boolean = false): String {
            if (!fresh) index.readPlaylist(url)?.let { stored ->
                describeMedia(url, stored, signed = false)
                return stored
            }
            if (!NetworkMonitor.online.value) throw SpotifyFailure("This track is not available offline.")
            val text = fetch(url)
            index.writePlaylist(url, text)
            describeMedia(url, text, signed = true)
            return text
        }

        private fun describeMedia(url: String, text: String, signed: Boolean) {
            val (file, address) = parseMediaPlaylist(text, url) ?: return
            index.attachFile(url, file)
            AudioGrant.rememberFile(url, file.hex)
            val absolute = url.toHttpUrl().resolve(address)?.toString() ?: return
            if (signed) signedAddresses[file.hex] = absolute else signedAddresses.putIfAbsent(file.hex, absolute)
        }

        private fun fetch(url: String, retried: Boolean = false): String {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code == 401 && !retried) runBlocking {
                    val session = SpotifyAccount.require(app)
                    session.headers(API_HOST, session.headers(API_HOST))
                } else if (!response.isSuccessful) {
                    fr.spotifeur.data.diagnostics.DiagnosticLog.record("audio", "playlist status=${response.code}")
                    throw SpotifyFailure("Spotify did not provide this audio stream.", response.code)
                }
                else return response.body?.string() ?: throw SpotifyFailure("Spotify did not provide this audio stream.")
            }
            return fetch(url, retried = true)
        }

        internal fun signedAddress(hex: String): String? = signedAddresses[hex]

        internal fun renewAddress(hex: String): String? {
            val url = index.playlistOfFile(hex) ?: return null
            playlist(url, fresh = true)
            return signedAddresses[hex]
        }

        internal class ResolvedAudio(val file: AudioFileRecord, val address: String)

        /** The file a track would be played from, for reading it ahead of the player. */
        internal suspend fun resolve(track: String): ResolvedAudio? {
            val masterUrl = manifest(track)
            val variant = variantUris(master(track, masterUrl)).firstOrNull() ?: return null
            val url = masterUrl.toHttpUrl().resolve(variant)?.toString() ?: return null
            val (file, address) = parseMediaPlaylist(playlist(url), url) ?: return null
            return ResolvedAudio(file, signedAddresses[file.hex] ?: url.toHttpUrl().resolve(address)?.toString() ?: return null)
        }

        internal suspend fun manifest(uri: String): String {
            val track = canonicalMusicUri(uri)?.takeIf { it.startsWith("spotify:track:") }
                ?: throw SpotifyFailure("This content is not a Spotify music track.")
            val session = SpotifyAccount.require(app)
            val key = session.username() + ":" + track
            manifests[key]?.let { return it }
            val mode = if (nativeAudio) 4 else 1
            // Tracks indexed before loudness was kept ask the service once more.
            index.audioId(track)?.takeIf { (index.playableTrack(track) != null && index.loudness(track) != null) || !NetworkMonitor.online.value }?.let { known ->
                return "https://$API_HOST/sneaktables/v2/hls/$mode/$known/master.m3u8".also { manifests[key] = it }
            }
            if (!NetworkMonitor.online.value) throw SpotifyFailure("This track is not available offline.")
            // Playlist entries can retain an old, now unavailable release. Track metadata
            // supplies the replacement authorized by Spotify; its audio extension must be used
            // while the original playlist/queue identity is kept unchanged.
            val playable = index.playableTrack(track) ?: run {
                val metadataBody = proto { writeByteArray(2, proto {
                    writeString(1, track); writeByteArray(2, proto { writeEnum(1, 10) })
                }) }
                val metadata = authenticatedSpotifyRequest(SpotifyAccount.transport, session,
                    spotifyUrl("extended-metadata/v0/extended-metadata"), "Track availability", metadataBody)
                val bytes = decodeMetadataExtensions(metadata)[track]
                    ?: throw SpotifyFailure("This track is not available for your account.")
                playableTrackUri(track, Proto(bytes))
            }
            val body = proto { writeByteArray(2, proto {
                writeString(1, playable)
                writeByteArray(2, proto { writeEnum(1, 5) })
            }) }
            val response = authenticatedSpotifyRequest(SpotifyAccount.transport, session,
                spotifyUrl("extended-metadata/v0/extended-metadata"), "Track preparation", body)
            val extension = decodeMetadataExtensions(response).values.firstOrNull()?.let(::Proto)
                ?: throw SpotifyFailure("The audio stream for this track is not available for your account.")
            val id = extension.bytes(4)?.takeIf { it.isNotEmpty() && it.size <= 64 }
                ?: throw SpotifyFailure("Spotify did not provide the audio ID for this track.")
            val encoded = id.joinToString("") { "%02x".format(it.toInt() and 255) }
            val normalization = extension.message(2)
            index.rememberLoudness(track, normalization?.float(1) ?: Float.NaN, normalization?.float(2) ?: Float.NaN)
            index.rememberAudioId(track, encoded, playable)
            return "https://$API_HOST/sneaktables/v2/hls/$mode/$encoded/master.m3u8".also { manifests[key] = it }
        }

        override fun createDataSource(): HttpDataSource = SpotifyHlsDataSource(http.createDataSource(), this)
        override fun setDefaultRequestProperties(properties: Map<String, String>): Factory {
            http.setDefaultRequestProperties(properties); return this
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            networkListener.close()
            manifests.clear()
            // Closing a TLS socket may send close_notify. Service.onDestroy runs
            // on the main thread, so even connection-pool teardown belongs on IO.
            val executor = client.dispatcher.executorService
            executor.execute {
                try { index.flush(); client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
                finally { executor.shutdown() }
            }
        }
    }

    private companion object {
        const val API_HOST = "spclient.wg.spotify.com"
        val EXPIRED_SIGNATURE = setOf(401, 403, 410)
    }
}
