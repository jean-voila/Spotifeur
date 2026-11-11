package fr.spotifeur.playback

import android.content.Context
import dalvik.system.DexClassLoader
import fr.spotifeur.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import java.io.File
import java.util.function.Consumer

/** The native SDK and its Java dependencies share one isolated loader. */
internal class SpotifyNativeRuntime private constructor(private val engine: Any, context: Context) : AutoCloseable {
    private val network = context.getSystemService(android.net.ConnectivityManager::class.java)
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private fun updateNetwork() {
        if (closed.get()) return
        val state = engineNetworkState(network)
        runCatching {
            engine.javaClass.getMethod("setOfflineForTest", Boolean::class.javaPrimitiveType).invoke(engine, !NetworkMonitor.online.value)
            engine.javaClass.getMethod("updateNetwork", String::class.java, ByteArray::class.java).invoke(engine, state.first, state.second)
        }
    }
    private val monitor = NetworkMonitor.onConnectionsInvalidated(::updateNetwork)
    private val callback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = updateNetwork()
        override fun onLost(network: android.net.Network) = updateNetwork()
        override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) = updateNetwork()
    }
    val playbackId: String = java.util.UUID.randomUUID().toString().replace("-", "")
    val sessionId: String = java.util.UUID.randomUUID().toString().replace("-", "")
    val processor = NativeSampleProcessor(checkNotNull(engine.javaClass.getMethod("getProcessorFactory").invoke(engine)))
    suspend fun request(method: String, body: ByteArray): Proto = withContext(Dispatchers.IO) {
        Proto(engine.javaClass.getMethod("requestSingle", String::class.java, String::class.java, ByteArray::class.java)
            .invoke(engine, NativeAudioProtocol.SERVICE, method, body) as ByteArray)
    }
    /** Instrumented tests only: cuts the engine's HTTP while it keeps what it holds in memory. */
    fun setOfflineForTest(offline: Boolean) {
        engine.javaClass.getMethod("setOfflineForTest", Boolean::class.javaPrimitiveType).invoke(engine, offline)
    }
    suspend fun serviceRequest(service: String, method: String, body: ByteArray): Proto = withContext(Dispatchers.IO) {
        val call = engine.javaClass.getMethod("requestService", String::class.java, String::class.java, ByteArray::class.java)
        // The engine's own failure, not the reflection wrapper around it, says what went wrong.
        try { Proto(call.invoke(engine, service, method, body) as ByteArray) }
        catch (wrapped: java.lang.reflect.InvocationTargetException) { throw wrapped.targetException ?: wrapped }
    }
    suspend fun manifest(id: String): Proto = request("GetMediaManifest", proto {
        writeString(1, id); writeString(2, playbackId); writeString(3, sessionId); writeEnum(4, 0); writeEnum(5, 1)
    })
    init { network.registerDefaultNetworkCallback(callback) }
    fun subscribe(fileId: String, next: (MediaHandleResult) -> Unit, error: (Throwable) -> Unit): AutoCloseable =
        stream(NativeAudioProtocol.METHOD, NativeAudioProtocol.handleRequest(fileId), { next(NativeAudioProtocol.handleResponse(it)) }, error)
    fun stream(method: String, body: ByteArray, next: (ByteArray) -> Unit, error: (Throwable) -> Unit): AutoCloseable =
        engine.javaClass.getMethod("subscribe", String::class.java, String::class.java, ByteArray::class.java,
            Consumer::class.java, Consumer::class.java).invoke(engine, NativeAudioProtocol.SERVICE, method,
            body, Consumer<ByteArray> { next(it) },
            Consumer<Throwable> { error(it) }) as AutoCloseable

    override fun close() = runBlocking {
        poolLock.withLock {
            if (references <= 0) return@withLock
            references--
            if (references > 0) return@withLock
            shared = null
            sharedSession = null
            destroy()
        }
    }
    private var references = 1
    private fun destroy() {
        if (!closed.compareAndSet(false, true)) return
        monitor.close()
        network.unregisterNetworkCallback(callback)
        try { processor.close() } finally { (engine as AutoCloseable).close() }
    }

    companion object {
        private val poolLock = Mutex()
        private const val OFFLINE_ATTRIBUTE = "offline"
        /** Instrumented tests only: entitlements of another account type, given to the engine. */
        @Volatile internal var attributeOverridesForTest: Map<String, String> = emptyMap()
        private var shared: SpotifyNativeRuntime? = null
        private var sharedSession: SpotifySession? = null
        // The native key loader must dispatch local envelope requests even without a network.
        // HTTP remains physically blocked by SdkHttpFactory in this state.
        private fun engineNetworkState(network: android.net.ConnectivityManager): Pair<String, ByteArray> =
            if (!NetworkMonitor.online.value) "WLAN" to proto { writeByteArray(1, proto { writeEnum(1, 0) }) }
            else networkState(network)
        private fun networkState(network: android.net.ConnectivityManager): Pair<String, ByteArray> {
            val capabilities = network.getNetworkCapabilities(network.activeNetwork)
            val reachable = !fr.spotifeur.BuildConfig.FORCE_OFFLINE && capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val type = when {
                !reachable -> "NONE"
                capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "WLAN"
                capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "MOBILE_4G"
                capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
                else -> "OTHER"
            }
            val bytes = if (reachable) proto { writeByteArray(1, proto {
                if (capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) writeEnum(1, 0)
                if (type == "MOBILE_4G") writeEnum(1, 1)
            }) } else proto { writeByteArray(2, proto { writeByteArray(2, byteArrayOf()) }) }
            return type to bytes
        }
        private var loader: ClassLoader? = null
        @Synchronized private fun load(app: Context): Class<*> {
            loader?.let { return it.loadClass("probe.bootstrap.NativeEngine") }
            val directory = File(app.codeCacheDir, "spotify-runtime").apply { mkdirs() }
            fun copy(name: String): File {
                val file = File(directory, name)
                if (file.exists()) check(file.delete())
                app.assets.open("spotify-runtime/$name").use { source ->
                    file.outputStream().use { output ->
                        check(file.setReadOnly())
                        source.copyTo(output)
                    }
                }
                return file
            }
            val dex = copy("engine.jar").path + File.pathSeparator + copy("bootstrap.dex").path
            val child = DexClassLoader(dex, directory.path, app.applicationInfo.nativeLibraryDir, Any::class.java.classLoader)
            val entry = child.loadClass("probe.bootstrap.NativeEngine")
            entry.getMethod("load", String::class.java).invoke(null,
                File(app.applicationInfo.nativeLibraryDir, "liborbit-jni-spotify.so").path)
            child.loadClass("probe.bootstrap.SdkHttpFactory").getField("keyResponseListener")
                .set(null, java.util.function.BiConsumer<String, Int> { file, status -> AudioGrant.onKeyResponse(file, status) })
            loader = child
            return entry
        }
        /** Attributes include a playback licence. Refresh online for each new engine session;
         * retain the last snapshot for local playback without network. */
        private suspend fun sessionAttributes(app: Context, session: SpotifySession): Map<String, String> {
            val accountId = java.security.MessageDigest.getInstance("SHA-256").digest(session.username().toByteArray())
                .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
            val file = File(app.noBackupFilesDir, "native-attributes-$accountId.json")
            val known = runCatching {
                val json = org.json.JSONObject(file.readText())
                json.keys().asSequence().associateWith(json::getString)
            }.getOrNull()
            if (known != null && !fr.spotifeur.data.NetworkMonitor.online.value) return known
            return try {
                val url = spotifyUrl("nebula/v1/configuration/attributes")
                val headers = session.headers(url.host)
                val bytes = SpotifyAccount.transport.send(Request.Builder().url(url)
                    .header("Authorization", "Bearer ${headers.user}").header("client-token", headers.client)
                    .header("Accept", "application/x-protobuf").header("Spotify-App-Version", SpotifyAndroid.VERSION)
                    .header("X-Client-Id", SpotifyAndroid.CLIENT_ID).header("App-Platform", "Android").build(), "Native audio session")
                val fetched = decodeAttributes(bytes)
                file.writeText(org.json.JSONObject(fetched).toString())
                fetched
            } catch (auth: SpotifyAuthRequired) { throw auth }
            catch (failure: java.io.IOException) { known ?: throw failure }
        }
        suspend fun open(context: Context): SpotifyNativeRuntime = withContext(Dispatchers.IO) { poolLock.withLock {
            val app = context.applicationContext
            val session = SpotifyAccount.require(app)
            shared?.let { runtime ->
                check(sharedSession === session) { "The previous audio session is still closing." }
                runtime.references++
                return@withLock runtime
            }
            val account = sessionAttributes(app, session) + attributeOverridesForTest
            // Live: with offline=false (Spotify Free without the new-account trial), the engine's
            // Download service accepts the key, then the media handle fails when the first encrypted
            // sample arrives, just after the clear lead-in. Its handles serve both streaming and
            // downloads here, so it must always see the offline entitlement.
            val attributes = account + (OFFLINE_ATTRIBUTE to "true")
            android.util.Log.i("SpotifeurNative", "Session attributes=${attributes.size} playerLicense=${attributes.containsKey("player-license")}")
            fr.spotifeur.data.diagnostics.DiagnosticLog.record("audio", "engine session ${attributeSummary(account)}")
            val directory = File(app.noBackupFilesDir, "native-audio").apply { mkdirs() }
            val input = session.nativeInput(directory.path, attributes)
            val network = app.getSystemService(android.net.ConnectivityManager::class.java)
            val state = engineNetworkState(network)
            val keys = NativeKeyStore(app, session.username())
            val sentHeaders = java.util.concurrent.ConcurrentHashMap<String, SpotifySession.Headers>()
            input["apiHeaders"] = java.util.function.BiFunction<String, Boolean, Map<String, String>> { host, rejected ->
                runBlocking {
                    check(SpotifyAccount.require(app) === session) { "The audio account changed." }
                    val current = if (rejected) session.headers(host, sentHeaders[host]) else session.headers(host)
                    sentHeaders[host] = current
                    mapOf("Authorization" to "Bearer ${current.user}", "client-token" to current.client)
                }
            }
            input["keyLoad"] = java.util.function.Function<String, ByteArray?> { keys.load(it) }
            input["keySave"] = java.util.function.BiConsumer<String, ByteArray> { id, bytes -> keys.save(id, bytes) }
            input["offline"] = !NetworkMonitor.online.value
            input["connectionType"] = state.first
            input["reachability"] = state.second
            try { SpotifyNativeRuntime(checkNotNull(load(app).getMethod("open", Map::class.java).invoke(null, input)), app).also {
                shared = it; sharedSession = session
            } }
            finally { (input["credential"] as ByteArray).fill(0); input.clear() }
        } }
    }
}
