package fr.spotifeur.data

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class SpotifySession(
    private val context: Context,
    private val transport: SpotifyTransport,
    private val vault: SessionVault,
    private val deviceId: String,
) {
    private val lock = Mutex()
    private var clientGrant: Grant? = null
    private val login = Login5(transport, SpotifyAndroid.CLIENT_ID, deviceId)
    private var userGrant: LoginGrant? = null
    private var stored: StoredCredential? = null
    private val audioPolicyLock = Mutex()
    @Volatile private var audioBandwidth: Long? = null
    @Volatile private var audioPolicyKey: String? = null
    @Volatile private var audioPolicySignature: String? = null
    /** Entitlements read with the audio policy, for the diagnostic report. */
    @Volatile var lastAttributes: Map<String, String>? = null
        private set

    /** Keep the user's quality preference, but select a file this account can prepare. */
    suspend fun maximumAudioBandwidth(): Long = audioPolicyLock.withLock {
        audioBandwidth?.let { return@withLock it }
        val account = username()
        val preferences = context.getSharedPreferences("spotify_audio_policy", Context.MODE_PRIVATE)
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(account.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        audioPolicyKey = key
        val known = preferences.getLong(key, Long.MAX_VALUE)
        if (!NetworkMonitor.online.value) return@withLock known
        try {
            val attributes = spotifyAttributes(transport, this, "Audio policy")
            lastAttributes = attributes
            val signature = "${attributes["type"]}|${attributes["audio-quality"]}"
            audioPolicySignature = signature
            var limit = accountAudioBandwidth(attributes["audio-quality"], attributes["type"])
            // A key refused for these same entitlements stays refused; a plan change clears it.
            if (preferences.getString("$key:refusedFor", null) == signature) limit = minOf(limit, preferences.getLong("$key:refused", limit))
            else preferences.edit().remove("$key:refused").remove("$key:refusedFor").apply()
            preferences.edit().putLong(key, limit).apply()
            fr.spotifeur.data.diagnostics.DiagnosticLog.record("account", "${attributeSummary(attributes)} limit=${describeBandwidth(limit)}")
            audioBandwidth = limit
            limit
        } catch (auth: SpotifyAuthRequired) { throw auth }
        catch (failure: java.io.IOException) {
            fr.spotifeur.data.diagnostics.DiagnosticLog.record("account", "attributes unavailable (${failure.message}) limit=${describeBandwidth(known)}")
            known
        }
    }

    /** The key service refused a file at this account's current limit: prepare lower variants from now on. */
    fun restrictAudioBandwidth(limit: Long, status: Int) {
        val current = audioBandwidth ?: Long.MAX_VALUE
        if (limit >= current) return
        audioBandwidth = limit
        val key = audioPolicyKey ?: return
        context.getSharedPreferences("spotify_audio_policy", Context.MODE_PRIVATE).edit().putLong(key, limit)
            .putLong("$key:refused", limit).putString("$key:refusedFor", audioPolicySignature ?: "unknown").apply()
        fr.spotifeur.data.diagnostics.DiagnosticLog.record("audio", "limit lowered to ${describeBandwidth(limit)} after key status=$status")
    }

    class Headers(val client: String, val user: String)
    val deviceIdentifier: String get() = deviceId
    /** A short-lived in-process snapshot; credentials never cross an IPC or file boundary. */
    suspend fun nativeInput(directory: String, attributes: Map<String, String>): MutableMap<String, Any> = lock.withLock {
        val credential = stored ?: throw SpotifyAuthRequired("Sign in to your Spotify account again.")
        // Without network the engine starts on the last known tokens and renews them itself later on.
        val client = try { client().forHost("spclient.wg.spotify.com") }
        catch (auth: SpotifyAuthRequired) { throw auth }
        catch (_: java.io.IOException) { clientGrant?.token.orEmpty() }
        val grant = try { userGrant?.takeIf(LoginGrant::valid) ?: renew(credential) }
        catch (auth: SpotifyAuthRequired) { throw auth }
        catch (_: java.io.IOException) { userGrant }
        mutableMapOf("directory" to directory, "username" to credential.username,
            "credential" to credential.bytes.copyOf(), "accessToken" to grant?.access.orEmpty(),
            "expiresAt" to (System.currentTimeMillis() + (grant?.expiresAt ?: 0) - SystemClock.elapsedRealtime()) / 1000L,
            "deviceId" to deviceId, "attributes" to attributes, "clientToken" to client)
    }
    suspend fun username(): String = lock.withLock {
        stored?.username ?: userGrant?.credential?.username
            ?: throw SpotifyAuthRequired("Sign in to your Spotify account again.")
    }
    private class Grant(val token: String, val validUntil: Long, val domains: Set<String>) {
        fun valid() = SystemClock.elapsedRealtime() < validUntil
        fun forHost(host: String): String {
            if (domains.isNotEmpty() && domains.none { host == it || host.endsWith(".$it") }) {
                throw SpotifyFailure("The Android token does not cover the $host service.")
            }
            return token
        }
    }

    suspend fun connect(username: String, password: String, code: suspend (Int) -> String) = lock.withLock {
        val client = client().forHost("login5.spotify.com")
        save(login.password(client, username, password, code), null)
    }

    suspend fun connectEmail(email: String, flowId: String, code: suspend (Int) -> String) = lock.withLock {
        val client = client().forHost("login5.spotify.com")
        save(login.email(client, email, flowId, code), null)
    }

    suspend fun connectGoogle(authCode: String?, idToken: String?, redirectUri: String, code: suspend (Int) -> String) = lock.withLock {
        val client = client().forHost("login5.spotify.com")
        save(login.google(client, authCode, idToken, redirectUri, code), null)
    }

    /** Reopens the saved account without any request: tokens renew when a request first needs them. */
    suspend fun restore(credential: StoredCredential) = lock.withLock {
        audioBandwidth = null
        stored = credential
        restoreTokens()
    }

    private fun restoreTokens() {
        val tokens = vault.loadTokens() ?: return
        val toElapsed = SystemClock.elapsedRealtime() - System.currentTimeMillis()
        try {
            userGrant = LoginGrant(tokens.getString("access"), tokens.getLong("accessUntil") + toElapsed, null)
            val domains = tokens.getJSONArray("domains")
            clientGrant = Grant(tokens.getString("client"), tokens.getLong("clientUntil") + toElapsed,
                (0 until domains.length()).map(domains::getString).toSet())
        } catch (_: org.json.JSONException) { userGrant = null; clientGrant = null }
    }

    private fun persistTokens() {
        val user = userGrant ?: return
        val client = clientGrant ?: return
        val toEpoch = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        try {
            vault.saveTokens(org.json.JSONObject().put("access", user.access).put("accessUntil", user.expiresAt + toEpoch)
                .put("client", client.token).put("clientUntil", client.validUntil + toEpoch)
                .put("domains", org.json.JSONArray(client.domains)))
        } catch (_: java.security.GeneralSecurityException) { /* The next start renews them. */ }
    }

    suspend fun headers(host: String, rejected: Headers? = null): Headers = lock.withLock {
        if (rejected != null && userGrant?.access == rejected.user) userGrant = null
        val client = client().forHost(host)
        val user = userGrant?.takeIf(LoginGrant::valid) ?: renew(
            stored ?: throw SpotifyAuthRequired("Sign in to your Spotify account again.")
        )
        Headers(client, user.access)
    }

    private suspend fun renew(credential: StoredCredential): LoginGrant =
        save(login.restore(client().forHost("login5.spotify.com"), credential), credential)

    private fun save(grant: LoginGrant, previous: StoredCredential?): LoginGrant {
        if (grant.credential?.username != null && grant.credential.username != stored?.username) audioBandwidth = null
        grant.credential?.let(vault::save)
        stored = grant.credential ?: previous
        userGrant = grant
        persistTokens()
        return grant
    }

    private suspend fun client(): Grant =
        clientGrant?.takeIf(Grant::valid) ?: acquireClientToken().also { clientGrant = it }

    private suspend fun acquireClientToken(): Grant {
        val platform = deviceData()
        var request = proto {
            writeEnum(1, 1)
            writeByteArray(2, proto {
                writeString(1, SpotifyAndroid.VERSION)
                writeString(2, SpotifyAndroid.CLIENT_ID)
                writeByteArray(3, proto {
                    writeByteArray(1, proto { writeByteArray(1, platform) })
                    writeString(2, deviceId)
                })
            })
        }
        repeat(3) {
            val response = post(CLIENT_TOKEN, request, "ClientToken")
            response.message(2)?.let { grant ->
                val expires = grant.number(2) ?: 0
                val refresh = grant.number(3)?.takeIf { it > 0 } ?: expires
                val domains = grant.messages(4).mapNotNull { it.text(1)?.trimStart('.')?.lowercase() }
                    .filter(String::isNotBlank).toSet()
                return Grant(grant.token(1), deadline(minOf(expires, refresh)), domains)
            }
            val challenges = response.message(3) ?: throw SpotifyFailure("ClientToken: unknown response.")
            val state = challenges.text(1)?.takeIf(String::isNotBlank)
                ?: throw SpotifyFailure("ClientToken: missing challenge state.")
            val answers = challenges.messages(2).map { challenge ->
                val hashcash = challenge.message(4)?.takeIf { challenge.number(1) == 3 }
                    ?: throw SpotifyFailure("ClientToken: unsupported challenge ${challenge.number(1) ?: "unknown"}.")
                val prefix = hashcash.text(2)?.let(::decodeHex) ?: throw SpotifyFailure("Invalid Hashcash prefix.")
                val answer = solveHashcash(byteArrayOf(), prefix, hashcash.number(1) ?: 0)
                val suffix = answer.joinToString("") { "%02X".format(it) }
                proto {
                    writeEnum(1, 3)
                    writeByteArray(4, proto { writeString(1, suffix) })
                }
            }
            if (answers.isEmpty()) throw SpotifyFailure("ClientToken: empty challenge.")
            request = proto {
                writeEnum(1, 2)
                writeByteArray(3, proto {
                    writeString(1, state)
                    answers.forEach { writeByteArray(2, it) }
                })
            }
        }
        throw SpotifyFailure("ClientToken: the server keeps renewing the challenge.")
    }

    private suspend fun post(url: String, bytes: ByteArray, operation: String): Proto {
        val request = Request.Builder().url(url)
            .header("Accept", PROTOBUF)
            .post(bytes.toRequestBody(PROTOBUF.toMediaType()))
        return Proto(transport.send(request.build(), operation))
    }

    private fun deviceData(): ByteArray {
        val resources = context.resources
        val display = resources.displayMetrics
        val audio = context.getSystemService(AudioManager::class.java)
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        return proto {
            writeByteArray(1, proto {
                writeInt32(1, display.widthPixels)
                writeInt32(2, display.heightPixels)
                writeInt32(3, resources.configuration.smallestScreenWidthDp)
                writeInt32(4, display.densityDpi)
                writeInt32(5, android.util.DisplayMetrics.DENSITY_DEVICE_STABLE)
            })
            writeString(2, Build.VERSION.RELEASE)
            writeUInt32(3, Build.VERSION.SDK_INT)
            writeString(4, Build.DEVICE)
            writeString(5, Build.MODEL)
            writeString(6, Build.BRAND)
            writeString(7, Build.MANUFACTURER)
            writeUInt32(8, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            installer?.let { writeString(10, it) }
        }
    }

    private fun deadline(seconds: Int): Long {
        if (seconds <= 0) throw SpotifyFailure("Invalid token lifetime.")
        return SystemClock.elapsedRealtime() + seconds * 1000L - minOf(30_000L, seconds * 100L)
    }

    private fun Proto.token(field: Int): String = text(field)
        ?.takeIf { it.isNotBlank() && it.all { char -> char in '!'..'~' } }
        ?: throw SpotifyFailure("The server returned an invalid token.")

    private companion object {
        const val CLIENT_TOKEN = "https://clienttoken.spotify.com/v1/clienttoken"
        const val PROTOBUF = "application/x-protobuf"
    }
}

internal fun describeBandwidth(limit: Long): String = if (limit == Long.MAX_VALUE) "none" else "${limit / 1000}k"

private fun decodeHex(text: String): ByteArray? {
    if (text.length % 2 != 0 || text.any { it.digitToIntOrNull(16) == null }) return null
    return ByteArray(text.length / 2) { index ->
        (text[index * 2].digitToInt(16) * 16 + text[index * 2 + 1].digitToInt(16)).toByte()
    }
}
